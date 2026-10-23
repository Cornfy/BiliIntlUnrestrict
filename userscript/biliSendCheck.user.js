// ==UserScript==
// @name         biliSendCheck
// @namespace    http://tampermonkey.net/
// @version      0.1.4
// @description  B站发评状态全自动检测与历史管理工具，精准识别正常/仅自己可见(Shadowban)/秒删/软屏蔽，支持关键词检索、历史复检与真实删评。
// @author       Cronfy & ChatGPT-6.1-Sol (thanks for freedom-introvert)
// @match        https://*.bilibili.com/*
// @run-at       document-idle
// @grant        unsafeWindow
// @grant        GM.xmlHttpRequest
// @grant        GM_setClipboard
// @grant        GM_getValue
// @grant        GM_setValue
// @connect      api.bilibili.com
// @license      MIT
// ==/UserScript==

(function () {
    'use strict';

    // ================= 0. 设置与存储键 =================
    const SORT_TIME = 2;
    const RECORDS_KEY = 'biliSendCheck_all_records';
    const SETTINGS_KEY = 'biliSendCheck_settings';
    const LOGS_KEY = 'biliSendCheck_logs';

    const DEFAULT_SETTINGS = {
        waitSeconds: 3.5,   // 发评后等待多久开始检测
        retryOnError: 2,    // 检测异常自动重试次数
        enableLog: true,    // 是否记录诊断日志
    };

    const originalFetch = unsafeWindow.fetch;

    // ==== 纯逻辑区（可独立测试）BEGIN ====
    function safeJsonParse(raw, fallback) {
        try {
            const value = JSON.parse(raw);
            return (value === null || value === undefined) ? fallback : value;
        } catch (e) {
            return fallback;
        }
    }

    /**
     * 与 JSON.parse 等价，但**不让超长整数 id 被改写**。
     *
     * 精度不是 JSON 丢的 —— 原文里的位数是完整的；丢在 JSON.parse：JS 的数字是 IEEE-754
     * 双精度，只能精确表示到 2^53-1，19 位动态 oid 的末几位会被静默改写
     * （1000000000000000001 → 1000000000000000000），相邻的两个 id 还会塌缩成同一个值。
     * JSON.parse 没有、也不会有任何开关能改变这一点 —— 数字类型本身就装不下。
     *
     * 唯一不丢精度的做法：解析前先把这类**裸整数**就地加上引号，让它以字符串形态进入 JS。
     * 逐字符扫描而不是正则替换：只有确认当前不在字符串内部时才改写，
     * 因此正文里形如 `"oid":123...` 的文字不会被误伤。
     */
    function parseJsonExact(text) {
        const src = String(text);
        let out = '';
        let i = 0;
        let inString = false;
        let escaped = false;
        while (i < src.length) {
            const ch = src[i];
            if (inString) {
                out += ch; i++;
                if (escaped) escaped = false;
                else if (ch === '\\') escaped = true;
                else if (ch === '"') inString = false;
                continue;
            }
            if (ch === '"') { inString = true; out += ch; i++; continue; }
            if (ch === '-' || (ch >= '0' && ch <= '9')) {
                let j = i;
                if (src[j] === '-') j++;
                while (j < src.length && src[j] >= '0' && src[j] <= '9') j++;
                const digits = src.slice(i, j);
                // 值位置：前面最近的非空白字符是 : [ , —— 键名走不到这里（键在字符串里）
                let p = i - 1;
                while (p >= 0 && /\s/.test(src[p])) p--;
                const prev = p >= 0 ? src[p] : '';
                const next = src[j];
                const endsCleanly = j >= src.length || next === ',' || next === '}' || next === ']' || /\s/.test(next);
                const isValuePos = prev === ':' || prev === '[' || prev === ',';
                out += (endsCleanly && isValuePos && /^-?\d{16,}$/.test(digits) && exceedsSafeInteger(digits))
                    ? '"' + digits + '"'
                    : digits;
                i = j;
                continue;
            }
            out += ch; i++;
        }
        return JSON.parse(out);
    }

    function clampNumber(raw, min, max, fallback) {
        const value = Number(raw);
        if (!isFinite(value)) return fallback;
        return Math.min(max, Math.max(min, value));
    }

    // 状态模型：宿主模块落库的是枚举名（SHADOW_BANNED / UNDER_REVIEW ...），
    // 旧版脚本落库的是小写短名。这里统一归一化，保证两个方向的 JSON 备份都能正确显示。
    const STATUS_META = {
        normal: { text: '正常显示', tag: 'tag-ok', toast: 'status-ok', title: '🟢 评论正常' },
        shadowban: { text: '仅自己可见', tag: 'tag-ban', toast: 'status-ban', title: '🔴 仅自己可见 (Shadowban)' },
        deleted: { text: '已失效 / 已被删除', tag: 'tag-del', toast: 'status-del', title: '⚫ 疑似被秒删' },
        invisible: { text: '软屏蔽(Invisible)', tag: 'tag-inv', toast: 'status-inv', title: '🟠 被系统软屏蔽 (Invisible)' },
        review: { text: '疑似审核中', tag: 'tag-inv', toast: 'status-inv', title: '🟡 疑似审核中' },
        unknown: { text: '状态待确定', tag: 'tag-inv', toast: '', title: '⚪ 状态待确定' },
        root_gone: { text: '根评论已消失', tag: 'tag-inv', toast: 'status-inv', title: '🟣 根评论已消失' },
    };

    function normalizeStatus(raw) {
        const key = String(raw === null || raw === undefined ? '' : raw).toLowerCase().replace(/[\s_-]/g, '');
        switch (key) {
            case 'normal': case 'ok': case 'pass': return 'normal';
            case 'shadowban': case 'shadowbanned': case 'ban': return 'shadowban';
            case 'deleted': case 'del': case 'removed': return 'deleted';
            case 'invisible': case 'inv': return 'invisible';
            case 'underreview': case 'review': case 'auditing': return 'review';
            case 'rootgone': case 'rootmissing': case 'parentgone': return 'root_gone';
            default: return 'unknown';
        }
    }

    function statusMeta(raw) {
        return STATUS_META[normalizeStatus(raw)] || STATUS_META.unknown;
    }

    function daysSince(record) {
        const base = record.post_time || record.timestamp || 0;
        const current = record.timestamp || Date.now();
        if (base <= 0 || current <= base) return 0;
        return Math.floor((current - base) / 86400000);
    }

    function resolveTypeName(type) {
        switch (Number(type)) {
            case 1: return '视频 (type=1)';
            case 12: return '专栏 (type=12)';
            case 11: case 17: return `动态 (type=${type})`;
            default: return `业务 (type=${type})`;
        }
    }

    /** 风控定性文案：区分"初检正常、后期被降级"的秋后算账场景 */
    function resolveAssessment(record) {
        const initial = normalizeStatus(record.initial_status);
        const current = normalizeStatus(record.status);
        const days = daysSince(record);
        if (current === 'unknown') return { text: '⚪ 状态待确定 (本轮未能定性)', tag: 'tag-inv' };
        if (initial === 'normal' && current === 'shadowban') {
            return {
                text: days > 0 ? `⚠️ 秋后算账 (发评 ${days} 天后转为仅自己可见)` : '⚠️ 延迟拦截 (初检正常，后转为仅自己可见)',
                tag: 'tag-warn'
            };
        }
        if (initial === 'normal' && current === 'deleted') {
            return {
                text: days > 0 ? `⚠️ 评论已失效 (发评 ${days} 天后已被删除或清理)` : '⚠️ 延迟失效 (初检正常，后已被删除或清理)',
                tag: 'tag-warn'
            };
        }
        if (current === 'root_gone') {
            // 不是这条评论本身被删，而是它挂靠的根评论不见了 → 单独说明，不混进"已失效"
            return {
                text: days > 0 ? `🟣 根评论已消失 (发评 ${days} 天后，所属根评论已不存在)` : '🟣 根评论已消失 (所属根评论已不存在，子评论无法核查)',
                tag: 'tag-inv'
            };
        }
        const meta = statusMeta(current);
        return { text: meta.text, tag: meta.tag };
    }

    /** 列表徽章：状态本身，若属于"初检正常后降级"则加警示样式 */
    function resolveBadge(record) {
        const initial = normalizeStatus(record.initial_status);
        const current = normalizeStatus(record.status);
        const meta = statusMeta(current);
        const escalated = initial === 'normal' && (current === 'shadowban' || current === 'deleted');
        return { text: escalated ? `⚠️ ${meta.text}` : meta.text, tag: escalated ? 'tag-warn' : meta.tag };
    }

    function escapeHtml(text) {
        return String(text === null || text === undefined ? '' : text).replace(/[&<>"']/g, function (ch) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
        });
    }

    // 本地检索：关键词匹配评论正文与来源标识（忽略大小写），整串数字额外精确匹配 rpid / oid
    const CommentSearch = {
        parse(raw) {
            const keyword = String(raw === null || raw === undefined ? '' : raw).trim();
            return { keyword: keyword, numeric: /^\d+$/.test(keyword) ? keyword : null };
        },
        isEmpty(query) {
            return !query || !query.keyword;
        },
        matches(record, query) {
            if (this.isEmpty(query)) return true;
            if (query.numeric && (String(record.rpid) === query.numeric || String(record.oid) === query.numeric)) return true;
            return containsIgnoreCase(record.message, query.keyword) || containsIgnoreCase(record.source_id, query.keyword);
        },
        filter(records, queryOrRaw) {
            // 同时接受解析后的 Query 与原始字符串，避免误传时静默返回全部记录
            const query = (typeof queryOrRaw === 'string') ? this.parse(queryOrRaw) : queryOrRaw;
            if (this.isEmpty(query)) return records;
            return records.filter(function (record) { return CommentSearch.matches(record, query); });
        },
        /** 命中区间，按出现顺序、互不重叠，供高亮使用 */
        ranges(text, keyword) {
            const target = String(keyword === null || keyword === undefined ? '' : keyword).trim();
            const source = String(text === null || text === undefined ? '' : text);
            if (!target || target.length > source.length) return [];
            const haystack = source.toLowerCase();
            const needle = target.toLowerCase();
            const out = [];
            let from = 0;
            while (from <= source.length - target.length) {
                const start = haystack.indexOf(needle, from);
                if (start < 0) break;
                out.push({ start: start, end: start + target.length });
                from = start + target.length;
            }
            return out;
        }
    };

    function containsIgnoreCase(value, keyword) {
        if (typeof value !== 'string' || !value) return false;
        return value.toLowerCase().indexOf(String(keyword).toLowerCase()) >= 0;
    }

    /** 正文渲染：转义 + 换行转换 + 命中高亮（先算区间再切片转义，避免转义改变下标） */
    function renderHighlighted(text, keyword) {
        const source = String(text === null || text === undefined ? '' : text);
        const ranges = CommentSearch.ranges(source, keyword);
        const br = function (s) { return escapeHtml(s).replace(/\n/g, '<br>'); };
        if (!ranges.length) return br(source);
        let html = '';
        let cursor = 0;
        for (const range of ranges) {
            html += br(source.slice(cursor, range.start));
            html += `<mark class="hl">${br(source.slice(range.start, range.end))}</mark>`;
            cursor = range.end;
        }
        return html + br(source.slice(cursor));
    }

    // ---------- 评论检索与双视角裁定（纯逻辑，可独立测试） ----------

    /** 在回复树的任意层级里找目标 rpid */
    function findReply(replies, rpid) {
        if (!Array.isArray(replies)) return null;
        const targetStr = String(rpid);
        for (let reply of replies) {
            if (!reply) continue;
            if (String(reply.rpid) === targetStr || String(reply.rpid_str) === targetStr) return reply;
            if (reply.replies) {
                const subFound = findReply(reply.replies, rpid);
                if (subFound) return subFound;
            }
        }
        return null;
    }

    /**
     * 枚举响应里所有可能承载评论的容器。
     * 目标评论未必只出现在 data.replies —— 置顶评论在 top/top_replies、楼中楼接口还会回带 data.root。
     * 只查 data.replies 会把"其实路人可见"的评论漏掉，进而被误判成仅自己可见。
     */
    function collectReplyContainers(data) {
        if (!data || typeof data !== 'object') return [];
        const out = [];
        const add = function (value) {
            if (!value) return;
            out.push(Array.isArray(value) ? value : [value]);
        };
        add(data.replies);
        add(data.top_replies);
        add(data.hots);
        add(data.top);
        if (data.upper) add(data.upper.top);
        add(data.root);   // 楼中楼接口回带的根评论
        return out;
    }

    function findReplyDeep(data, rpid) {
        for (const container of collectReplyContainers(data)) {
            const hit = findReply(container, rpid);
            if (hit) return hit;
        }
        return null;
    }

    // 以下长 ID 均为手工构造的精度示例，不来自用户记录。
    // ---------- 精确 id：19 位动态 oid 超过 JS 安全整数上限，JSON.parse 会把它静默改写 ----------
    // 例：1000000000000000003 解析后 String() 出来是 1000000000000000000（肉眼看不出来），
    // 相邻的 id 还会塌缩成同一个值 —— 一旦存了改写后的值，查的就是"另一个动态"。
    function exceedsSafeInteger(digits) {
        const text = String(digits === null || digits === undefined ? '' : digits);
        return /^\d{16,}$/.test(text) && Number(text) > Number.MAX_SAFE_INTEGER;
    }

    /**
     * 列出记录里「不可信」的 id 字段。
     *
     * 19 位 id（动态 oid 的典型长度）经 JSON.parse 会被双精度浮点改写末几位
     * （`Number("1000000000000000001")` → `1000000000000000000`）。
     * 拿这种失真值去查询，服务端只会说「这个对象不存在」，而那条响应
     * 恰恰就是 -404「啥都木有」—— 它是我们判定"已删除"的**确定信号**。
     * 也就是：失真 id 会凭空伪造出一个"确定没有"，把结论推向"已删除"。
     *
     * 所以：**数值**超安全整数上限（且非字符串）时，一律视为不可信，拒绝用它发请求。
     * 反过来，字符串形态的 id 一定是精确的 —— 它只会来自 parseJsonExact（原样数字串），
     * 不可能是双精度改写后的产物，所以直接放行。
     */
    function untrustedIds(record) {
        const keys = ['oid', 'rpid', 'root'];
        if (!record) return keys.slice();
        return keys.filter(key => {
            const value = record[key];
            if (!value) return false;                              // 0 / null 表示不适用（根评论的 root）
            if (typeof value === 'string') return false;           // 字符串 id = 精确数字串 → 可信
            return exceedsSafeInteger(String(value));              // 裸数值超上限 → 末位已被改写
        });
    }

    // ---------- 响应码分类：把"确定没有"与"暂时拿不到"严格分开 ----------

    /**
     * ok          —— 拿到数据，可以判断"这个视角里有没有这条评论"
     * gone        —— 评论区 / 评论对象本身不存在（-404 啥都木有、12002 评论功能已关闭、
     *                12022 根评论已删除）。这是"确定没有"，不是"暂时拿不到"
     * unavailable —— 网络异常 / 风控 / 其它非 0 码。本次无法判断，可重试，绝不落锤
     */
    // 「确定没有」的响应码。注意 12006「没有该评论」是楼中楼接口 (x/v2/reply/reply) 专用的：
    // 它表示 root 评论本身在评论区里查不到（实测 2026-10-15：root 存在且 rcount=0 时返回 code=0，
    // 只有 root 不存在、或 root 不属于该 oid 时才返回 12006）。
    const GONE_CODES = [-404, 12002, 12022, 12006];

    function classifyCode(code) {
        if (code === 0) return 'ok';
        return GONE_CODES.indexOf(code) >= 0 ? 'gone' : 'unavailable';
    }

    /**
     * 取一页楼中楼 data.replies 的发布时间区间 [minCtime, maxCtime]。
     *
     * 必须取**真实 min/max**，而不是 replies[0] / replies[last]：
     * 首末元素只有在"页内严格按 ctime 升序"时才等于极值，而置顶、审核插入等
     * 都可能打乱页内顺序。取 min/max 对顺序不敏感 —— 零额外请求，直接消掉这个假设。
     * 无有效 ctime 时返回 null（调用方据此视为"本次定位不可信"）。
     */
    function pageCtimeRange(replies) {
        if (!replies || !replies.length) return null;
        let min = 0, max = 0;
        for (const reply of replies) {
            const ctime = Number(reply && reply.ctime) || 0;
            if (ctime <= 0) continue;
            if (min === 0 || ctime < min) min = ctime;
            if (max === 0 || ctime > max) max = ctime;
        }
        return min > 0 ? [min, max] : null;
    }

    /**
     * 统一解释一次视角探针的结果。关键是把"请求本身没成功"（unavailable）与
     * "这个视角确定看不到"（gone / 成功请求但未命中）严格分开——前者一律不判定。
     * 返回 { kind, ok, code, hit, invisible, ctime }
     */
    function readProbe(resp, rpid) {
        const code = (resp && typeof resp.code === 'number') ? resp.code : -1;
        const kind = classifyCode(code);
        if (kind !== 'ok') return { kind: kind, ok: false, code: code, hit: false, invisible: false };
        if (!resp.data || typeof resp.data !== 'object' || Array.isArray(resp.data)) {
            return { kind: 'unavailable', ok: false, code, hit: false, invisible: false };
        }
        const found = findReplyDeep(resp.data, rpid);
        return {
            kind: 'ok',
            ok: true,
            code: 0,
            hit: !!found,
            invisible: !!(found && found.invisible),
            ctime: found ? found.ctime : undefined
        };
    }

    /**
     * 双视角裁定（纯逻辑）。anon / auth / recheck 均为 readProbe 的结果。
     * anonComplete=false 表示路人视角只做了抽样扫描（拿不到发布时间、翻页被截断等），
     * 此时"未见"不足以支撑任何结论。
     *
     * 落锤规则：
     *   1. 路人可见                → normal / invisible
     *   2. 路人视角不可用          → unknown（可重试）
     *   3. 路人扫描不完整          → unknown（未见不足为凭）
     *   4. 作者视角可见            → shadowban 候选，延迟复验路人视角后落锤
     *   5. 评论区 / 评论对象不存在  → deleted
     *   6. 路人未见 + 作者视角不可用 → unknown（"仅自己可见"与"已删除"无法区分）
     *   7. 路人未见 + 作者确认没有  → deleted
     */
    function resolveVerdict(anon, auth, recheck, anonComplete) {
        if (anon && anon.hit) {
            return { status: anon.invisible ? 'invisible' : 'normal', reason: 'anon-visible', ctime: anon.ctime };
        }
        if (!anon || anon.kind === 'unavailable') {
            return { status: 'unknown', reason: 'anon-unavailable' };
        }
        if (anonComplete === false) {
            return { status: 'unknown', reason: 'anon-scan-incomplete' };
        }
        if (auth && auth.hit) {
            if (recheck) {
                if (recheck.hit) {
                    return { status: recheck.invisible ? 'invisible' : 'normal', reason: 'recheck-visible', ctime: recheck.ctime };
                }
                // 复验成功但未命中或返回 gone，才与作者可见合并落 shadowban；
                // 「暂时拿不到」(unavailable) 仍是拿不准，退回待确定。两者 ok 都为 false，必须按 kind 区分。
                if (recheck.kind === 'unavailable') return { status: 'unknown', reason: 'recheck-unavailable' };
                return { status: 'shadowban', reason: 'confirmed-shadowban', ctime: auth.ctime };
            }
            return { status: 'shadowban', reason: 'need-recheck', ctime: auth.ctime, needRecheck: true };
        }
        if (anon.kind === 'gone') {
            // 12006「没有该评论」在楼中楼接口里表示 root 评论本身不见了：
            // 这只是"父评论消失"，这条子评论自身是否还在无法核查 → 单独成组，不并进"已失效"。
            if (anon.code === 12006) return { status: 'root_gone', reason: 'root-missing' };
            return { status: 'deleted', reason: 'section-gone' };
        }
        if (!auth || auth.kind === 'unavailable') {
            return { status: 'unknown', reason: 'auth-unavailable' };
        }
        return { status: 'deleted', reason: auth.kind === 'gone' ? 'both-gone' : 'both-missed' };
    }
    // Legacy-compatible exchange: numeric IDs on the wire, exact strings inside JavaScript.
    const CommentTransfer = {
        id(value, key, required = false) {
            if (value === undefined) value = required ? null : 0;
            if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new Error(`${key} 数值精度不可信`);
            if (!['string', 'number'].includes(typeof value) || !/^\d+$/.test(String(value))) throw new Error(`${key} 不是十进制非负整数`);
            const digits = String(value).replace(/^0+(?=\d)/, '');
            if ((required && digits === '0') || digits.length > 19 || (digits.length === 19 && digits > '9223372036854775807')) throw new Error(`${key} 无效或超出 Long 范围`);
            return digits;
        },
        record(value) {
            if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('记录必须是对象');
            const result = {};
            for (const key of ['rpid', 'oid', 'root', 'parent', 'uid']) result[key] = this.id(value[key], key, key === 'rpid' || key === 'oid');
            const type = this.id(value.type === undefined ? 1 : value.type, 'type', true);
            if (Number(type) > 2147483647) throw new Error('type 无效');
            result.type = Number(type);
            for (const key of ['message', 'source_id', 'origin_url', 'status', 'initial_status']) {
                if (value[key] != null && typeof value[key] !== 'string') throw new Error(`${key} 必须是字符串`);
                result[key] = value[key] ?? (key === 'message' ? '' : null);
            }
            for (const key of ['status', 'initial_status']) {
                if (result[key] == null) continue;
                const status = normalizeStatus(result[key]);
                if (status === 'unknown' && result[key].toLowerCase() !== 'unknown') throw new Error(`不支持的状态: ${result[key]}`);
                result[key] = status;
            }
            result.status = result.status || 'unknown';
            for (const key of ['post_time', 'timestamp']) {
                const text = this.id(value[key], key);
                const n = Number(text);
                if (!Number.isSafeInteger(n)) throw new Error(`${key} 超出安全整数范围`);
                result[key] = n;
            }
            return result;
        },
        decode(text) {
            const value = parseJsonExact(text);
            let rows = value;
            if (!Array.isArray(value)) {
                if (value?.format !== 'bili-comment-backup' || value.version !== 1 || !Array.isArray(value.records)) throw new Error('不支持的备份格式或版本');
                rows = value.records;
            }
            const seen = new Set();
            return rows.map(row => {
                const record = this.record(row);
                if (seen.has(record.rpid)) throw new Error('备份含重复评论');
                seen.add(record.rpid);
                return record;
            });
        },
        encode(records) {
            const names = { normal: 'NORMAL', shadowban: 'SHADOW_BANNED', deleted: 'DELETED', invisible: 'INVISIBLE', review: 'UNDER_REVIEW', root_gone: 'ROOT_GONE', unknown: 'UNKNOWN' };
            const idKeys = new Set(['rpid', 'oid', 'root', 'parent', 'uid']);
            const rows = records.map(row => {
                const value = this.record(row);
                value.status = names[value.status];
                if (value.initial_status != null) value.initial_status = names[value.initial_status];
                // Write validated ID tokens directly. Only known ID properties use this path;
                // content and every other field retain normal JSON string escaping.
                return '{\n' + Object.entries(value).map(([key, field]) =>
                    '    ' + JSON.stringify(key) + ': ' + (idKeys.has(key) ? this.id(field, key) : JSON.stringify(field))
                ).join(',\n') + '\n  }';
            });
            return '[\n  ' + rows.join(',\n  ') + '\n]';
        },
        merge(local, incoming) {
            const records = new Map(local.map(row => [String(row.rpid), { ...row }]));
            const changes = new Map();
            let added = 0, kept = 0;
            for (const item of incoming) {
                const old = records.get(item.rpid);
                if (!old) {
                    records.set(item.rpid, { ...item, initial_status: [item.initial_status, item.status].find(value => normalizeStatus(value) !== 'unknown') || 'unknown' });
                    added++; continue;
                }
                // Never query another comment section as the result of importing conflicting metadata.
                for (const key of ['oid', 'type', 'root', 'parent', 'uid']) {
                    const a = this.id(old[key] === undefined && key === 'type' ? 1 : old[key], key, key === 'oid' || key === 'type');
                    const b = String(item[key]);
                    if (a !== b && (key === 'oid' || key === 'type' || (a !== '0' && b !== '0'))) throw new Error(`评论 ${item.rpid} 的所属评论区或身份字段冲突，未导入任何记录`);
                }
                const retain = item.status === 'unknown' && normalizeStatus(old.status) !== 'unknown';
                if (retain) kept++;
                const current = retain ? normalizeStatus(old.status) : item.status;
                if (current !== normalizeStatus(old.status)) changes.set(item.rpid, { before: normalizeStatus(old.status), after: current });
                const merged = { ...old, status: current, timestamp: retain ? old.timestamp : item.timestamp };
                for (const key of ['root', 'parent', 'uid']) if (!old[key] || String(old[key]) === '0') merged[key] = item[key];
                for (const key of ['message', 'source_id', 'origin_url']) if (!String(old[key] || '').trim()) merged[key] = item[key];
                if (!(old.post_time > 0)) merged.post_time = item.post_time;
                merged.initial_status = [old.initial_status, item.initial_status, current].find(value => normalizeStatus(value) !== 'unknown') || 'unknown';
                records.set(item.rpid, merged);
            }
            return { records: [...records.values()], changes, added, kept };
        }
    };

    // ==== 纯逻辑区（可独立测试）END ====

    const SETTINGS = Object.assign({}, DEFAULT_SETTINGS, safeJsonParse(GM_getValue(SETTINGS_KEY, '{}'), {}));

    function saveSettings() {
        GM_setValue(SETTINGS_KEY, JSON.stringify(SETTINGS));
    }

    // ================= 1. 诊断日志（浏览器侧轻量实现） =================
    const Logger = {
        limit: 300,
        buffer: null,
        load() {
            if (this.buffer) return this.buffer;
            const stored = safeJsonParse(GM_getValue(LOGS_KEY, '[]'), []);
            this.buffer = Array.isArray(stored) ? stored : [];
            return this.buffer;
        },
        log(level, message) {
            const line = { t: Date.now(), level: level, message: String(message) };
            console.log(`[biliSendCheck][${level}] ${line.message}`);
            if (!SETTINGS.enableLog) return;
            const list = this.load();
            list.unshift(line);
            if (list.length > this.limit) list.length = this.limit;
            GM_setValue(LOGS_KEY, JSON.stringify(list));
        },
        info(message) { this.log('INFO', message); },
        warn(message) { this.log('WARN', message); },
        error(message) { this.log('ERROR', message); },
        all() { return this.load(); },
        clear() { this.buffer = []; GM_setValue(LOGS_KEY, '[]'); }
    };

    // ================= 2. B站原生 API 通信助手 =================
    function getCookie(name) {
        const match = document.cookie.match(new RegExp('(^| )' + name + '=([^;]+)'));
        return match ? match[2] : null;
    }

    function getCurrentBvid() {
        const match = window.location.pathname.match(/(BV[a-zA-Z0-9]+)/);
        return match ? match[1] : null;
    }

    /**
     * 统一请求入口。
     * 匿名视角优先走页面原生 fetch（credentials: "omit"，确保不带任何 Cookie），
     * forceGm = true 时强制走扩展通道再试一次（用于页面侧被风控拦截的情况）。
     */
    async function fetchBilibili(url, isAnonymous, method = "GET", body = null, isForm = false, forceGm = false) {
        if (isAnonymous && method === "GET" && !forceGm) {
            try {
                const res = await originalFetch(url, {
                    method: "GET",
                    signal: AbortSignal.timeout(6000),
                    credentials: "omit",   // 路人视角：不带 Cookie
                    cache: "no-store",     // 不吃浏览器缓存，避免拿到滞后的旧列表
                    headers: { "Accept": "application/json, text/plain, */*" }
                });
                if (!res.ok) throw new Error(`HTTP ${res.status}`);
                return parseJsonExact(await res.text());
            } catch (e) {
                // 原生 fetch 遇阻时静默降级为 GM_XHR（同样是匿名通道）
            }
        }

        return new Promise((resolve, reject) => {
            const options = {
                method: method, url: url, timeout: 6000,
                // 显式声明凭证策略：路人视角明确不带 Cookie，作者视角才带
                withCredentials: !isAnonymous,
                anonymous: isAnonymous,
                headers: { "Accept": "application/json, text/plain, */*", "Cache-Control": "no-cache", "Pragma": "no-cache" },
                onload: (res) => {
                    // 一律走 parseJsonExact：接口响应里可能带 19 位 id，普通 JSON.parse 会改写末几位
                    if (res.status < 200 || res.status >= 300) { reject(new Error(`HTTP ${res.status}`)); return; }
                    try { resolve(parseJsonExact(res.responseText)); }
                    catch (e) { reject(new Error("非JSON响应")); }
                },
                onerror: (err) => reject(err),
                ontimeout: () => reject(new Error("请求超时"))
            };
            if (body) {
                options.data = body;
                if (isForm) options.headers = { ...options.headers, "Content-Type": "application/x-www-form-urlencoded" };
            }
            GM.xmlHttpRequest(options);
        });
    }

    async function deleteBiliComment(oid, type, rpid) {
        const csrf = getCookie('bili_jct');
        if (!csrf) throw new Error("未找到登录凭证(bili_jct)");
        const data = new URLSearchParams({ oid, type, rpid, csrf }).toString();
        const res = await fetchBilibili('https://api.bilibili.com/x/v2/reply/del', false, "POST", data, true);
        if (res.code !== 0) throw new Error(res.message || "删评接口返回错误");
        return true;
    }

    // ================= 3. 原生 URL Scheme 构建算法 =================
    function generateBiliUrlScheme(record) {
        // oid 由 parseJsonExact 保真为字符串，直接使用即可跳对动态
        const oid = record.oid;
        const rpid = record.rpid;
        const type = record.type;
        const rootId = String(record.root || 0);
        if (type == 1) {
            return (rootId != 0)
                ? `bilibili://video/${oid}/?comment_root_id=${rootId}&comment_secondary_id=${rpid}`
                : `bilibili://video/${oid}/?comment_root_id=${rpid}`;
        } else {
            return (rootId != 0)
                ? `bilibili://comment/detail/${type}/${oid}/${rootId}?anchor=${rpid}`
                : `bilibili://comment/detail/${type}/${oid}/${rpid}`;
        }
    }

    // ================= 4. 油猴全域名统一存储引擎 (GM_storage) =================
    const DBManager = {
        key: RECORDS_KEY,

        init() {
            return Promise.resolve();
        },

        save(record) {
            record.timestamp = record.timestamp || Date.now();
            let list = this.getAllSync();
            const index = list.findIndex(item => String(item.rpid) === String(record.rpid));
            if (index >= 0) {
                // 保留已有字段，安全合并
                const previous = list[index];
                const keep = normalizeStatus(record.status) === 'unknown' && normalizeStatus(previous.status) !== 'unknown';
                list[index] = { ...previous, ...record };
                if (keep) { list[index].status = previous.status; list[index].timestamp = previous.timestamp; }
                if (normalizeStatus(previous.initial_status) !== 'unknown') list[index].initial_status = previous.initial_status;
            } else {
                list.unshift(record);
            }
            GM_setValue(this.key, JSON.stringify(list));
            return Promise.resolve();
        },

        undoKey: RECORDS_KEY + '_undo',

        canUndo() { return !!GM_getValue(this.undoKey, ''); },

        replaceWithUndo(next) {
            const previous = GM_getValue(this.key, '[]');
            const previousUndo = GM_getValue(this.undoKey, '');
            try {
                GM_setValue(this.undoKey, previous);
                GM_setValue(this.key, JSON.stringify(next));
            } catch (error) {
                GM_setValue(this.key, previous);
                GM_setValue(this.undoKey, previousUndo);
                throw error;
            }
        },

        undo() {
            const snapshot = GM_getValue(this.undoKey, '');
            if (!snapshot || !Array.isArray(JSON.parse(snapshot))) throw new Error('没有可恢复的备份');
            GM_setValue(this.key, snapshot);
            GM_setValue(this.undoKey, '');
        },

        importBackup(text) {
            const incoming = CommentTransfer.decode(text);
            const old = this.getAllSync();
            const plan = CommentTransfer.merge(old, incoming);
            if (JSON.stringify(old) !== JSON.stringify(plan.records)) this.replaceWithUndo(plan.records);
            return plan;
        },

        delete(rpid, local = true) {
            const list = this.getAllSync();
            const next = list.filter(item => String(item.rpid) !== String(rpid));
            if (next.length !== list.length) {
                if (local) this.replaceWithUndo(next);
                else GM_setValue(this.key, JSON.stringify(next));
            }
            return Promise.resolve();
        },

        clear() {
            if (this.getAllSync().length) this.replaceWithUndo([]);
            return Promise.resolve();
        },

        getByRpid(rpid) {
            return this.getAllSync().find(item => String(item.rpid) === String(rpid)) || null;
        },

        getAllSync() {
            const raw = GM_getValue(this.key, '[]');
            const parsed = safeJsonParse(raw, []);
            return Array.isArray(parsed) ? parsed : [];
        },

        getAll() {
            return Promise.resolve(this.getAllSync());
        }
    };

    // ================= 5. 纯净风控判定计算引擎 (含时序二分查找) =================
    // findReply / readProbe / resolveVerdict 等纯逻辑已上移到「纯逻辑区」，可独立测试。

    const RECHECK_DELAY_MS = 1500;   // 落锤"仅自己可见"前，复验路人视角的间隔
    const BINARY_STEP_CAP = 8;       // 时序二分的步数上限

    function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

    /** 加时间戳：绕开 CDN 对匿名响应的缓存——路人视角可能滞后于作者视角 */
    function stamp(url) { return `${url}&_=${Date.now()}`; }

    async function safeFetch(url, anonymous, forceGm = false) {
        try {
            return await fetchBilibili(url, anonymous, "GET", null, false, forceGm);
        } catch (e) {
            Logger.warn(`[探针] 请求异常: ${e.message}`);
            return null;
        }
    }

    /** 单次视角探针：请求 + 解释 + 日志。匿名通道被拦截或返回异常码时，换扩展通道（同样不带 Cookie）再核一次 */
    async function probeView(label, url, anonymous, rpid) {
        let probe = readProbe(await safeFetch(url, anonymous), rpid);
        // 仅当"请求本身没成功"时才换扩展通道重试。
        // 注意不能用 !probe.ok —— 对 GONE(-404/12022/12006…) 它同样是 false，
        // 那样一次确定的 -404 会被后面**失败**的兜底请求覆盖成 unavailable，把"已删除"误判成"状态待确定"。
        if (probe.kind === 'unavailable' && anonymous) {
            probe = readProbe(await safeFetch(url, true, true), rpid);
        }
        Logger.info(`[探针] ${label} code=${probe.code} kind=${probe.kind} 命中=${probe.hit}${probe.invisible ? ' invisible=true' : ''}`);
        return probe;
    }

    async function evaluateCommentStatus(oid, type, rpid, root = 0, sentAtSeconds = 0) {
        const rawRoot = String(root || 0);
        // 个别版本会把根评论的 root 填成它自己的 rpid，兜一下，避免误入楼中楼分支
        const rootId = (rawRoot !== '0' && rawRoot !== String(rpid)) ? rawRoot : 0;

        return rootId === 0
            ? evaluateRootComment(oid, type, rpid)
            : evaluateSubReply(oid, type, rpid, rootId, Number(sentAtSeconds) || 0);
    }

    /** 根评论检测：用 seek_rpid 单请求锚定目标，再走双视角裁定 */
    async function evaluateRootComment(oid, type, rpid) {
        const url = `https://api.bilibili.com/x/v2/reply/main?oid=${oid}&type=${type}&mode=${SORT_TIME}&next=0&seek_rpid=${rpid}&ps=20`;

        const anon = await probeView('路人视角', stamp(url), true, rpid);
        if (anon.hit) {
            return resolveVerdict(anon, null, null, true);
        }
        if (anon.kind === 'unavailable') {
            // 路人视角拿不到数据 ≠ 路人看不到。宁可判"待确定"，也绝不误报仅自己可见。
            Logger.warn(`[根评] 路人视角不可用 (code=${anon.code})，本轮不判定 rpid=${rpid}`);
            return { status: 'unknown', reason: 'anon-unavailable' };
        }

        const auth = await probeView('作者视角', stamp(url), false, rpid);
        let verdict = resolveVerdict(anon, auth, null, true);

        if (verdict.needRecheck) {
            Logger.warn(`[根评] 路人未见而作者可见，${RECHECK_DELAY_MS}ms 后复验路人视角，排除缓存/审核延迟...`);
            await sleep(RECHECK_DELAY_MS);
            const recheck = await probeView('路人视角(复验)', stamp(url), true, rpid);
            verdict = resolveVerdict(anon, auth, recheck, true);
        }

        Logger.info(`[根评] 判定 rpid=${rpid} -> ${verdict.status} (${verdict.reason})`);
        return verdict;
    }

    /**
     * 楼中楼检测。
     * 楼中楼接口既不支持 seek_rpid，ps 也被服务端锁死在 20，只能翻页：
     *   1) 末页直跳 + 倒数第 2 页容差
     *   2) 按发布时间做时序二分 —— 只登记候选页，**绝不凭"时间落在这一页区间内"就断定目标在此页**
     *      （二分时间区间必须只取自 data.replies；把 data.root 的 ctime 混进来会算错区间，
     *        会让二分在错误的页上提前收敛）
     *   3) 扫描不完整时（拿不到发布时间 / 请求异常）一律判"状态待确定"，不做穷举兜底
     *      —— 兜底只为把"待确定"救成确定结论，代价是拿不准时还多打一串请求；正确性优先
     */
    async function evaluateSubReply(oid, type, rpid, rootId, sentAtSeconds) {
        const urlBase = `https://api.bilibili.com/x/v2/reply/reply?oid=${oid}&type=${type}&root=${rootId}&ps=20&sort=0`;

        const firstRaw = await safeFetch(stamp(`${urlBase}&pn=1`), true);
        const firstProbe = readProbe(firstRaw, rpid);
        Logger.info(`[探针] 楼中楼首页 code=${firstProbe.code} kind=${firstProbe.kind} 命中=${firstProbe.hit}`);

        if (firstProbe.hit) {
            return { status: firstProbe.invisible ? 'invisible' : 'normal', reason: 'anon-visible', ctime: firstProbe.ctime };
        }
        if (firstProbe.kind === 'unavailable') {
            Logger.warn(`[楼中楼] 路人视角不可用 (code=${firstProbe.code})，本轮不判定 rpid=${rpid}`);
            return { status: 'unknown', reason: 'anon-unavailable' };
        }
        if (firstProbe.kind === 'gone') {
            // 整个评论区/根评论已不存在：仍请作者视角核一次，用于区分"仅自己可见的私密动态"
            const authGone = await probeView('作者视角', stamp(`${urlBase}&pn=1`), false, rpid);
            let v = resolveVerdict(firstProbe, authGone, null, true);
            // 与根评论路径一致：路人报 gone 而作者可见，这只是 shadowban **候选**，
            // 落锤前必须延迟复验路人视角，排除缓存 / 审核延迟造成的假阳性（这一支原先漏了）。
            if (v.needRecheck) {
                Logger.warn(`[楼中楼] 路人报 gone 而作者可见，${RECHECK_DELAY_MS}ms 后复验路人视角...`);
                await sleep(RECHECK_DELAY_MS);
                const recheckGone = await probeView('路人视角(复验)', stamp(`${urlBase}&pn=1`), true, rpid);
                v = resolveVerdict(firstProbe, authGone, recheckGone, true);
            }
            Logger.info(`[楼中楼] 判定 rpid=${rpid} -> ${v.status} (${v.reason})`);
            return v;
        }

        const data = firstRaw.data;
        const totalCount = data?.page?.count ?? data?.root?.rcount ?? data?.root?.count ?? 0;
        const actualPageSize = data?.page?.size > 0 ? data.page.size : 20;
        const lastPage = Math.max(1, Math.ceil(totalCount / actualPageSize));

        let targetPage = lastPage;
        let hitProbe = null;
        // 没有发布时间 → 时序二分用不了，末两页的"未见"不足为凭
        let searchIncomplete = (lastPage > 2 && !(sentAtSeconds > 0));

        // 1. 优先末页直跳 + 倒数第 2 页容差
        const lastProbe = readProbe(await safeFetch(stamp(`${urlBase}&pn=${lastPage}`), true), rpid);
        if (!lastProbe.ok) searchIncomplete = true;
        if (lastProbe.hit) {
            hitProbe = lastProbe;
        } else if (lastPage > 1) {
            const prevProbe = readProbe(await safeFetch(stamp(`${urlBase}&pn=${lastPage - 1}`), true), rpid);
            if (!prevProbe.ok) searchIncomplete = true;
            if (prevProbe.hit) { hitProbe = prevProbe; targetPage = lastPage - 1; }
        }

        // 2. 🌟 时序对折二分（区间只取自 data.replies，命中区间时核实相邻页而非直接落锤）
        if (!hitProbe && !searchIncomplete && lastPage > 2 && sentAtSeconds > 0) {
            const candidates = [];
            // 步数预算：log2(页数)+2 足以收敛，上限 8 —— 这是「减少查询次数」的关键优化
            const maxSteps = Math.min(BINARY_STEP_CAP, Math.floor(Math.log2(lastPage)) + 2);
            let low = 1, high = lastPage - 2, steps = 0;

            while (low <= high && steps < maxSteps) {
                steps++;
                const mid = Math.floor((low + high) / 2);
                const midRaw = await safeFetch(stamp(`${urlBase}&pn=${mid}`), true);
                const midProbe = readProbe(midRaw, rpid);
                if (!midProbe.ok) { searchIncomplete = true; break; }
                if (midProbe.hit) { hitProbe = midProbe; targetPage = mid; break; }

                // 区间取本页 data.replies 的**真实 min/max**（对齐模块 ctimeRangeIn）
                const range = pageCtimeRange((midRaw.data && midRaw.data.replies) || []);

                if (range) {
                    if (sentAtSeconds < range[0]) high = mid - 1;
                    else if (sentAtSeconds > range[1]) low = mid + 1;
                    // 落在本页时间区间内却没找到：目标多半在相邻页的边界上，登记候选后逐个核实
                    else { candidates.push(mid - 1, mid, mid + 1); break; }
                } else { searchIncomplete = true; break; }
            }

            if (low <= high && steps >= maxSteps && !candidates.length && !hitProbe) {
                searchIncomplete = true;
            }

            for (const page of candidates) {
                if (hitProbe) break;
                if (page < 1 || page > lastPage) continue;
                const probe = readProbe(await safeFetch(stamp(`${urlBase}&pn=${page}`), true), rpid);
                if (!probe.ok) { searchIncomplete = true; break; }
                if (probe.hit) { hitProbe = probe; targetPage = page; }
            }

            // 区间命中却相邻页都没命中 → 这次定位不可信：宁可不判（归入「状态待确定」），绝不误报
            if (!hitProbe && candidates.length && !searchIncomplete) {
                searchIncomplete = true;
                Logger.warn(`[楼中楼] 区间命中但候选页均未命中（定位不可信），本轮不落锤 rpid=${rpid}`);
            }
        }

        // 注：此处原有「扫描不完整时全量翻页兜底」，已移除。
        // 它只为把 UNKNOWN 救成确定结论而存在，代价是在不确定的情况下多打一串请求；
        // 正确性优先 —— 拿不准就归入「状态待确定」分组，等专门的补判逻辑处理，不做穷举。

        if (hitProbe) {
            return { status: hitProbe.invisible ? 'invisible' : 'normal', reason: 'anon-visible', ctime: hitProbe.ctime };
        }

        if (searchIncomplete) return { status: 'unknown', reason: 'anon-scan-incomplete' };

        // 4. 路人未见，作者视角对目标页复验（含上一页容差）
        let authFinal = await probeView('作者视角', stamp(`${urlBase}&pn=${targetPage}`), false, rpid);
        if (!authFinal.hit && authFinal.kind === 'ok' && targetPage > 1) {
            const authPrev = await probeView('作者视角(上一页)', stamp(`${urlBase}&pn=${targetPage - 1}`), false, rpid);
            authFinal = authPrev;
        }

        const anonLack = { kind: 'ok', ok: true, code: 0, hit: false, invisible: false };
        const anonComplete = !searchIncomplete;
        if (!anonComplete) {
            Logger.warn(`[楼中楼] 路人视角扫描不完整（楼层 ${lastPage} 页），本轮不落锤 rpid=${rpid}`);
        }
        let verdict = resolveVerdict(anonLack, authFinal, null, anonComplete);

        if (verdict.needRecheck) {
            Logger.warn(`[楼中楼] 路人未见而作者可见，${RECHECK_DELAY_MS}ms 后复验路人视角...`);
            await sleep(RECHECK_DELAY_MS);
            const current = await probeView('路人视角(复验)', stamp(`${urlBase}&pn=${targetPage}`), true, rpid);
            const previous = targetPage > 1
                ? await probeView('路人视角(复验上一页)', stamp(`${urlBase}&pn=${targetPage - 1}`), true, rpid) : null;
            const recheck = current.hit ? current : previous?.hit ? previous
                : current.kind === 'unavailable' ? current : previous?.kind === 'unavailable' ? previous : current;
            verdict = resolveVerdict(anonLack, authFinal, recheck, anonComplete);
        }

        Logger.info(`[楼中楼] 判定 rpid=${rpid} -> ${verdict.status} (${verdict.reason})`);
        return verdict;
    }

    // ================= 6. UI 渲染模块 (Shadow DOM) =================
    const UIManager = (function () {
        const host = document.createElement('div');
        host.id = 'bili-send-check-host';
        host.style.cssText = 'position: fixed; bottom: 24px; right: 24px; z-index: 999999; display: flex; flex-direction: column; gap: 12px; pointer-events: none;';
        document.body.appendChild(host);

        const shadow = host.attachShadow({ mode: 'closed' });
        const style = document.createElement('style');
        style.textContent = `
            .toast { width: 320px; background: #fff; border-radius: 8px; box-shadow: 0 4px 12px rgba(0,0,0,0.15); border-left: 4px solid #FB7299; padding: 16px; font-family: sans-serif; animation: slideIn 0.3s ease-out; pointer-events: auto; position: relative; overflow: hidden; }
            .toast.closing { opacity: 0; transform: translateX(100%); transition: all 0.3s; }
            @keyframes slideIn { from { opacity: 0; transform: translateX(100%); } to { opacity: 1; transform: translateX(0); } }
            .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
            .title { font-size: 15px; font-weight: bold; color: #18191C; margin: 0; }
            .close-btn { cursor: pointer; color: #9499A0; border: none; background: none; font-size: 16px; padding: 0; }
            .close-btn:hover { color: #18191C; }
            .message { font-size: 13px; color: #61666D; line-height: 1.5; margin: 0; }
            .quote { font-size: 12px; color: #9499A0; background: #F6F7F8; padding: 8px; border-radius: 4px; margin-top: 8px; cursor: pointer; position: relative; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
            .quote:hover { background: #E3E5E7; color: #18191C; }
            .quote::after { content: "点击复制"; position: absolute; right: 8px; top: 8px; background: rgba(0,0,0,0.6); color: #fff; padding: 2px 6px; border-radius: 4px; font-size: 10px; opacity: 0; transition: opacity 0.2s; }
            .quote:hover::after { opacity: 1; }
            .action-bar { display: flex; justify-content: flex-end; margin-top: 12px; gap: 8px; }
            .btn { cursor: pointer; padding: 4px 12px; font-size: 12px; border-radius: 4px; border: 1px solid #E3E5E7; background: #fff; color: #18191C; }
            .btn:hover { background: #F6F7F8; }
            .progress-line { position: absolute; bottom: 0; left: 0; height: 3px; background: #FB7299; width: 0%; transition: width 0.3s; }
            .progress-indeterminate { animation: loading 1.5s infinite linear; width: 30%; }
            @keyframes loading { 0% { left: -30%; } 100% { left: 100%; } }
            .status-ok { border-left-color: #00A1D6; } .status-ok .title { color: #00A1D6; }
            .status-ban { border-left-color: #F25D8E; } .status-ban .title { color: #F25D8E; }
            .status-del { border-left-color: #222222; } .status-del .title { color: #222222; }
            .status-inv { border-left-color: #FA8C16; } .status-inv .title { color: #FA8C16; }

            .float-btn { position: fixed; left: 24px; bottom: 24px; width: 44px; height: 44px; background: #FB7299; border-radius: 50%; box-shadow: 0 4px 12px rgba(251,114,153,0.4); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 20px; cursor: pointer; pointer-events: auto; transition: transform 0.2s; z-index: 999998; }
            .float-btn:hover { transform: scale(1.1); background: #FC8BAB; }
            .panel-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.6); display: none; justify-content: center; align-items: center; pointer-events: auto; z-index: 999999; backdrop-filter: blur(2px); }
            .panel { width: 750px; max-width: 92vw; height: 85vh; background: #fff; border-radius: 12px; display: flex; flex-direction: column; overflow: hidden; box-shadow: 0 8px 32px rgba(0,0,0,0.2); }

            .panel-header { padding: 12px 20px; border-bottom: 1px solid #E3E5E7; display: flex; justify-content: space-between; align-items: center; gap: 12px; background: #f9f9f9; }
            .panel-header h3 { margin: 0; font-size: 17px; color: #18191C; white-space: nowrap; }
            .panel-header-actions { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; justify-content: flex-end; }
            .top-btn { border: 1px solid #E3E5E7; background: #fff; padding: 5px 10px; border-radius: 6px; font-size: 12px; cursor: pointer; color: #61666D; transition: all 0.2s; white-space: nowrap; }
            .top-btn:hover { background: #F1F2F3; color: #18191C; }
            .top-btn.active { background: #FB7299; border-color: #FB7299; color: #fff; font-weight: bold; }
            .top-btn-danger:hover { background: #ffeeeef0; color: #FF4D4F; border-color: #FF4D4F; }

            .panel-toolbar { display: none; flex-direction: column; gap: 8px; padding: 12px 20px; border-bottom: 1px solid #E3E5E7; background: #fff; }
            .search-input { width: 100%; box-sizing: border-box; padding: 8px 12px; border: 1px solid #E3E5E7; border-radius: 8px; font-size: 13px; outline: none; color: #18191C; background: #fff; }
            .search-input:focus { border-color: #FB7299; box-shadow: 0 0 0 2px rgba(251,114,153,0.12); }
            .chip-row { display: flex; flex-wrap: wrap; gap: 8px; }
            .chip { border: 1px solid #E3E5E7; background: #fff; color: #61666D; border-radius: 999px; padding: 4px 12px; font-size: 12px; cursor: pointer; transition: all 0.2s; }
            .chip:hover { background: #F6F7F8; }
            .chip.active { background: #FB7299; border-color: #FB7299; color: #fff; font-weight: bold; }

            .panel-body { flex: 1; overflow-y: auto; padding: 20px; background: #F1F2F3; }
            .record-card { background: #fff; border-radius: 8px; padding: 16px; margin-bottom: 16px; box-shadow: 0 2px 6px rgba(0,0,0,0.03); }
            .record-header { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 12px; font-size: 12px; color: #9499A0; border-bottom: 1px dashed #E3E5E7; padding-bottom: 8px; }
            .record-msg { font-size: 14px; color: #18191C; margin: 0; line-height: 1.6; word-break: break-all; cursor: pointer; }
            .record-detail { border-top: 1px dashed #E3E5E7; margin-top: 12px; padding-top: 10px; display: flex; flex-direction: column; gap: 6px; }
            .detail-row { display: flex; font-size: 12px; line-height: 1.6; }
            .detail-k { color: #9499A0; flex: 0 0 auto; }
            .detail-v { color: #18191C; word-break: break-all; }
            .hl { background: rgba(0,161,214,0.18); color: #00A1D6; font-weight: bold; border-radius: 2px; }

            .tag { padding: 3px 8px; border-radius: 4px; font-size: 12px; color: #fff; font-weight: bold; white-space: nowrap; height: fit-content; }
            .tag-ok { background: #00A1D6; } .tag-ban { background: #F25D8E; } .tag-del { background: #222222; } .tag-inv { background: #FA8C16; } .tag-warn { background: #FA541C; }

            .record-actions { display: flex; justify-content: flex-end; gap: 8px; flex-wrap: wrap; margin-top: 14px; }
            .act-btn { border: none; background: #F6F7F8; color: #61666D; border-radius: 4px; padding: 6px 10px; font-size: 12px; cursor: pointer; transition: all 0.2s; }
            .act-btn:hover { background: #E3E5E7; color: #18191C; }
            .act-btn:disabled { opacity: 0.55; cursor: default; }
            .act-btn.btn-scheme { background: #E6F7FF; color: #00A1D6; font-weight: bold; }
            .act-btn.btn-scheme:hover { background: #BAE7FF; }
            .act-btn.btn-recheck { background: #F6FFED; color: #52C41A; font-weight: bold; }
            .act-btn.btn-recheck:hover { background: #D9F7BE; }
            .act-btn.del-bili { background: #FFF1F0; color: #FF4D4F; }
            .act-btn.del-bili:hover { background: #FFCCC7; }

            .empty { text-align: center; color: #9499A0; padding: 60px 20px; }
            .empty-title { font-size: 15px; margin-bottom: 6px; }
            .empty-hint { font-size: 12px; opacity: 0.8; }

            .card-block { background: #fff; border-radius: 8px; padding: 16px; box-shadow: 0 2px 6px rgba(0,0,0,0.03); }
            .card-block h4 { margin: 0 0 10px 0; font-size: 14px; color: #18191C; }
            .settings-row { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 10px 0; font-size: 13px; color: #61666D; border-bottom: 1px dashed #F1F2F3; }
            .settings-row input[type=number] { width: 90px; padding: 5px 8px; border: 1px solid #E3E5E7; border-radius: 6px; font-size: 13px; }
            .settings-actions { display: flex; gap: 8px; margin-top: 14px; }
            .settings-note { font-size: 12px; color: #9499A0; margin: 12px 0 0 0; line-height: 1.6; }

            .log-actions { display: flex; justify-content: flex-end; margin-bottom: 10px; }
            .log-list { display: flex; flex-direction: column; gap: 4px; max-height: 58vh; overflow-y: auto; }
            .log-line { display: flex; gap: 8px; font-size: 12px; font-family: monospace; padding: 4px 6px; border-radius: 4px; background: #F6F7F8; }
            .log-time { color: #9499A0; flex: 0 0 auto; }
            .log-level { flex: 0 0 auto; font-weight: bold; }
            .log-info .log-level { color: #00A1D6; }
            .log-warn .log-level { color: #FA8C16; }
            .log-error .log-level { color: #FF4D4F; }
            .log-msg { color: #61666D; word-break: break-all; }
        `;
        shadow.appendChild(style);

        const floatBtn = document.createElement('div');
        floatBtn.className = 'float-btn'; floatBtn.title = "biliSendCheck 历史记录"; floatBtn.innerHTML = "📝";
        shadow.appendChild(floatBtn);

        const fileInput = document.createElement('input');
        fileInput.type = 'file'; fileInput.accept = '.json'; fileInput.style.display = 'none';
        shadow.appendChild(fileInput);

        const overlay = document.createElement('div');
        overlay.className = 'panel-overlay';
        overlay.innerHTML = `
            <div class="panel">
                <div class="panel-header">
                    <h3>biliSendCheck 发评历史中心</h3>
                    <div class="panel-header-actions">
                        <button class="top-btn" data-nav="list">📚 记录</button>
                        <button class="top-btn" data-nav="settings">⚙️ 设置</button>
                        <button class="top-btn" data-nav="logs">📋 日志</button>
                        <button class="top-btn" id="btn-import">📥 导入</button>
                        <button class="top-btn" id="btn-export">📦 导出</button>
                        <button class="top-btn" id="btn-undo">↶ 撤销</button>
                        <button class="top-btn top-btn-danger" id="btn-clear">🧹 清空</button>
                        <button class="close-btn" id="close-panel">×</button>
                    </div>
                </div>
                <div class="panel-toolbar" id="toolbar"></div>
                <div class="panel-body" id="panel-body"></div>
            </div>
        `;
        shadow.appendChild(overlay);

        const toolbarEl = shadow.getElementById('toolbar');
        const bodyEl = shadow.getElementById('panel-body');

        const state = { view: 'list', keyword: '', filter: null, expanded: {} };
        let records = [];
        let importPlan = null;

        const CHIP_DEFS = [
            { key: null, label: '全部' },
            { key: 'normal', label: '🟢 正常' },
            { key: 'shadowban', label: '🔴 仅自己可见' },
            { key: 'deleted', label: '⚫ 已失效' },
            { key: 'invisible', label: '🟠 软屏蔽' },
            { key: 'review', label: '🟡 审核中' },
            { key: 'unknown', label: '⚪ 状态待确定' },
            { key: 'root_gone', label: '🟣 根评论已消失' }
        ];

        function flash(btn, text, restore) {
            btn.textContent = text;
            setTimeout(() => { btn.textContent = restore; }, 2000);
        }

        async function refresh() {
            records = await DBManager.getAll();
            // 🎯 优先按真实的官方发评时间倒序排列，彻底避免复检跳动
            records.sort((a, b) => (b.post_time || b.timestamp) - (a.post_time || a.timestamp));
            render();
        }

        function syncNav() {
            shadow.querySelectorAll('[data-nav]').forEach(btn => {
                btn.classList.toggle('active', btn.getAttribute('data-nav') === state.view);
            });
        }

        function render() {
            shadow.getElementById('btn-undo').disabled = !DBManager.canUndo();
            syncNav();
            toolbarEl.innerHTML = '';
            bodyEl.innerHTML = '';
            if (state.view === 'list') {
                renderToolbar();
                renderListBody();
            } else if (state.view === 'settings') {
                toolbarEl.style.display = 'none';
                renderSettings();
            } else {
                toolbarEl.style.display = 'none';
                renderLogs();
            }
        }

        // ---- 记录视图：搜索 + 状态筛选 ----
        function renderToolbar() {
            toolbarEl.style.display = 'flex';
            if (importPlan) {
                const notice = document.createElement('div');
                notice.style.cssText = 'width:100%;font-size:13px;';
                notice.textContent = `导入完成：新增 ${importPlan.added} 条，更新状态 ${importPlan.changes.size} 条，保留本地有效状态 ${importPlan.kept} 条。`;
                if (importPlan.changes.size) {
                    const view = document.createElement('button');
                    view.className = 'top-btn';
                    view.textContent = state.changesOnly ? '退出变化筛选' : '查看变化';
                    view.onclick = () => {
                        state.changesOnly = !state.changesOnly;
                        if (state.changesOnly) { state.keyword = ''; state.filter = null; }
                        render();
                    };
                    notice.appendChild(view);
                }
                const close = document.createElement('button');
                close.className = 'top-btn'; close.textContent = '关闭';
                close.onclick = () => { importPlan = null; state.changesOnly = false; render(); };
                notice.appendChild(close);
                toolbarEl.appendChild(notice);
            }

            const input = document.createElement('input');
            input.type = 'search';
            input.className = 'search-input';
            input.placeholder = '搜索评论内容、来源标识或评论 ID';
            input.value = state.keyword;
            input.oninput = () => {
                state.keyword = input.value;
                renderListBody();
                renderChips();
            };
            toolbarEl.appendChild(input);

            const chipRow = document.createElement('div');
            chipRow.className = 'chip-row';
            toolbarEl.appendChild(chipRow);
            chipRow.id = 'chip-row';
            renderChips();
        }

        function countByStatus() {
            const visible = CommentSearch.filter(records, CommentSearch.parse(state.keyword))
                .filter(record => !state.changesOnly || importPlan?.changes.has(String(record.rpid)));
            const counts = { all: visible.length };
            for (const record of visible) {
                const key = normalizeStatus(record.status);
                counts[key] = (counts[key] || 0) + 1;
            }
            return counts;
        }

        function renderChips() {
            const chipRow = shadow.getElementById('chip-row');
            if (!chipRow) return;
            const counts = countByStatus();

            // 选中的筛选项即使计数归零也保留，避免"筛选条件突然消失"
            const visible = CHIP_DEFS.filter(def =>
                def.key === null || (counts[def.key] || 0) > 0 || state.filter === def.key
            );

            chipRow.innerHTML = visible.map(def => {
                const count = def.key === null ? counts.all : (counts[def.key] || 0);
                const active = state.filter === def.key ? ' active' : '';
                return `<button class="chip${active}" data-filter="${def.key === null ? '' : def.key}">${def.label} (${count})</button>`;
            }).join('');

            chipRow.querySelectorAll('.chip').forEach(btn => {
                btn.onclick = () => {
                    const raw = btn.getAttribute('data-filter');
                    state.filter = raw ? raw : null;
                    renderChips();
                    renderListBody();
                };
            });
        }

        function renderListBody() {
            const query = CommentSearch.parse(state.keyword);
            const searched = CommentSearch.filter(records, query).filter(record => !state.changesOnly || importPlan?.changes.has(String(record.rpid)));
            const filtered = state.filter
                ? searched.filter(record => normalizeStatus(record.status) === state.filter)
                : searched;

            if (!filtered.length) {
                let title = '暂无任何发评记录';
                if (records.length && !searched.length) title = `未找到含「${escapeHtml(query.keyword)}」的评论`;
                else if (searched.length) title = '无匹配状态的评论';

                const hint = (records.length && !searched.length)
                    ? '试试更短的关键词，或直接搜索评论 ID'
                    : '发出的评论将在此处沉淀并追踪全生命周期状态';

                bodyEl.innerHTML = `<div class="empty"><div class="empty-title">${title}</div><div class="empty-hint">${hint}</div></div>`;
                return;
            }

            bodyEl.innerHTML = filtered.map(record => renderCard(record, query.keyword)).join('');
        }

        /** 手风琴折叠全息卡片 */
        function renderCard(record, keyword) {
            const badge = resolveBadge(record);
            const rpidKey = String(record.rpid);
            const expanded = !!state.expanded[rpidKey];
            const postTime = record.post_time || record.timestamp;
            const timeStr = new Date(postTime).toLocaleString();
            const sourceText = record.source_id || `OID: ${record.oid}`;

            const change = state.changesOnly ? importPlan?.changes.get(rpidKey) : null;
            let detailHtml = '';
            if (expanded) {
                const checkTimeStr = record.timestamp > 0 ? new Date(record.timestamp).toLocaleString() : '未记录';
                const rows = [
                    ['当前状态', statusMeta(record.status).text],
                    ['初始状态', record.initial_status ? statusMeta(record.initial_status).text : '未记录'],
                    ['风控定性', resolveAssessment(record).text],
                    ['评论区源 ID', record.source_id || '无'],
                    ['评论区 OID', String(record.oid)],
                    ['评论区类型', resolveTypeName(record.type)],
                    ['用户 UID', record.uid > 0 ? String(record.uid) : '未记录'],
                    ['评论 RPID', String(record.rpid)],
                    ['楼层 Root ID', record.root > 0 ? String(record.root) : '0 (根评论)'],
                    ['目标 Parent ID', record.parent > 0 ? String(record.parent) : '0'],
                    ['发送日期', timeStr],
                    ['检查日期', checkTimeStr]
                ];
                detailHtml = `<div class="record-detail">${rows.map(row =>
                    `<div class="detail-row"><span class="detail-k">${row[0]}：</span><span class="detail-v">${escapeHtml(row[1])}</span></div>`
                ).join('')}</div>`;
            }

            return `
                <div class="record-card">
                    <div class="record-header">
                        <span>🕒 ${escapeHtml(timeStr)} &nbsp;|&nbsp; 🏷️ ${escapeHtml(resolveTypeName(record.type))} &nbsp;|&nbsp; 📌 ${escapeHtml(sourceText)}</span>
                        <span class="tag ${badge.tag}">${escapeHtml(badge.text)}</span>
                    </div>
                    ${change ? `<div style="font-size:12px;color:#666">本次导入：${escapeHtml(statusMeta(change.before).text)} → ${escapeHtml(statusMeta(change.after).text)}</div>` : ''}
                    <p class="record-msg" data-act="copy" data-rpid="${rpidKey}" title="点击复制文案">${renderHighlighted(record.message, keyword)}</p>
                    ${detailHtml}
                    <div class="record-actions">
                        <button class="act-btn btn-recheck" data-act="recheck" data-rpid="${rpidKey}">🔄 再次检查</button>
                        <button class="act-btn btn-scheme" data-act="scheme" data-rpid="${rpidKey}">📱 复制 Scheme</button>
                        <button class="act-btn" data-act="copy" data-rpid="${rpidKey}">📑 复制文案</button>
                        <button class="act-btn" data-act="expand" data-rpid="${rpidKey}">${expanded ? '🔼 收起详情' : '🔽 展开详情'}</button>
                        <button class="act-btn" data-act="del-local" data-rpid="${rpidKey}">❌ 移除</button>
                        <button class="act-btn del-bili" data-act="del-bili" data-rpid="${rpidKey}">🗑️ B站删评</button>
                    </div>
                </div>
            `;
        }

        async function handleBodyClick(e) {
            const btn = e.target.closest('[data-act]');
            if (!btn) return;

            const act = btn.getAttribute('data-act');
            const rpid = btn.getAttribute('data-rpid');
            const record = records.find(item => String(item.rpid) === String(rpid));
            if (!record) return;

            if (act === 'expand') {
                if (state.expanded[rpid]) delete state.expanded[rpid];
                else state.expanded[rpid] = true;
                renderListBody();
                return;
            }

            if (act === 'copy') {
                GM_setClipboard(record.message);
                if (btn.tagName === 'BUTTON') flash(btn, '✅ 已复制！', '📑 复制文案');
                return;
            }

            if (act === 'scheme') {
                const scheme = generateBiliUrlScheme(record);
                GM_setClipboard(scheme);
                flash(btn, '✅ 已复制！', '📱 复制 Scheme');
                window.location.href = scheme;
                return;
            }

            if (act === 'del-local') {
                try { await DBManager.delete(rpid); }
                catch (err) { alert('删除失败: ' + err.message); return; }
                importPlan = null; state.changesOnly = false;
                delete state.expanded[rpid];
                await refresh();
                return;
            }

            if (act === 'del-bili') {
                if (!confirm("🚨 警告：这将会永久删除这条评论！确认继续吗？")) return;
                // 破坏性操作：oid 必须用精确值，否则删的根本不是这条评论
                if (untrustedIds(record).length) {
                    alert("❌ 该记录的评论 ID 已失真（超出 JS 安全整数上限且无精确值），无法定位到正确对象，已拒绝删评请求。");
                    return;
                }
                const original = btn.textContent;
                btn.textContent = "⏳ 正在删评...";
                btn.disabled = true;
                try {
                    await deleteBiliComment(record.oid, record.type, record.rpid);
                    Logger.info(`删评成功 rpid=${rpid}`);
                    await DBManager.delete(rpid, false);
                    await refresh();
                    alert("✅ 删评成功！");
                } catch (err) {
                    Logger.error(`删评失败 rpid=${rpid}: ${err.message}`);
                    btn.textContent = original;
                    btn.disabled = false;
                    alert(`❌ 删评失败：${err.message}`);
                }
                return;
            }

            if (act === 'recheck') {
                const original = btn.textContent;
                btn.textContent = "⏳ 正在复检...";
                btn.disabled = true;
                try {
                    const bad = untrustedIds(record);
                    if (bad.length) {
                        Logger.warn(`复检中止 rpid=${rpid}: 记录缺精确 ID (${bad.join(', ')})，拒绝用失真 ID 查询（会伪造 -404 进而误判已删除）`);
                        alert(
                            `⚠️ 无法复检：这条记录捕获于旧版本，缺少精确 ID（${bad.join('、')}）。\n\n` +
                            `这类 ID 超出 JS 安全整数上限，已在存储时被改写。用改写后的值查询，服务端会当作"该对象不存在"` +
                            `而返回 -404「啥都木有」—— 那正是脚本用来判定"已删除"的确定信号，会凭空伪造出"已被删除"。\n\n` +
                            `因此脚本拒绝发出这个请求，这条记录保持"状态待确定"。\n` +
                            `如需重新判定，请在 B 站重新发送一次评论（新记录会带上精确 ID）。`
                        );
                        btn.textContent = original;
                        btn.disabled = false;
                        return;
                    }

                    const sentAt = record.post_time ? Math.floor(record.post_time / 1000) : 0;
                    const res = await evaluateCommentStatus(
                        record.oid, record.type, record.rpid, record.root, sentAt
                    );

                    const previousInitial = normalizeStatus(record.initial_status);
                    const patch = { status: res.status, timestamp: Date.now() };
                    // 官方 ctime 自愈补齐
                    if (res.ctime && !record.post_time) patch.post_time = res.ctime * 1000;
                    // 此前从未取得过真实初检结论（如首次检测失败）时，以这次结果作为初始状态
                    if (previousInitial === 'unknown' && res.status !== 'unknown') patch.initial_status = res.status;

                    await DBManager.save({ ...record, ...patch });
                    if (res.status === 'unknown' && normalizeStatus(record.status) !== 'unknown') alert('本次未能确认，已保留上次有效状态及检查时间。');
                    Logger.info(`复检 rpid=${rpid}: ${normalizeStatus(record.status)} -> ${res.status}`);
                    await refresh();
                } catch (err) {
                    Logger.error(`复检失败 rpid=${rpid}: ${err.message}`);
                    btn.textContent = original;
                    btn.disabled = false;
                    alert(`❌ 复检失败: ${err.message}`);
                }
            }
        }

        // ---- 设置视图 ----
        function renderSettings() {
            bodyEl.innerHTML = `
                <div class="card-block">
                    <h4>检测设置</h4>
                    <div class="settings-row">
                        <label for="set-wait">发评后等待检测（秒）</label>
                        <input type="number" id="set-wait" step="0.5" min="0" max="30" value="${SETTINGS.waitSeconds}">
                    </div>
                    <div class="settings-row">
                        <label for="set-retry">检测异常自动重试（次）</label>
                        <input type="number" id="set-retry" step="1" min="0" max="5" value="${SETTINGS.retryOnError}">
                    </div>
                    <div class="settings-row">
                        <label for="set-log">记录诊断日志</label>
                        <input type="checkbox" id="set-log" ${SETTINGS.enableLog ? 'checked' : ''}>
                    </div>
                    <div class="settings-actions">
                        <button class="top-btn" id="save-settings">💾 保存设置</button>
                        <button class="top-btn" id="reset-settings">↺ 恢复默认</button>
                    </div>
                    <p class="settings-note">设置保存在本地（GM 存储），只影响本脚本的检测节奏与日志，不改变判定逻辑。</p>
                </div>
            `;

            shadow.getElementById('save-settings').onclick = () => {
                SETTINGS.waitSeconds = clampNumber(shadow.getElementById('set-wait').value, 0, 30, DEFAULT_SETTINGS.waitSeconds);
                SETTINGS.retryOnError = Math.round(clampNumber(shadow.getElementById('set-retry').value, 0, 5, DEFAULT_SETTINGS.retryOnError));
                SETTINGS.enableLog = shadow.getElementById('set-log').checked;
                saveSettings();
                Logger.info(`设置已更新: 等待=${SETTINGS.waitSeconds}s, 重试=${SETTINGS.retryOnError}, 日志=${SETTINGS.enableLog}`);
                alert("✅ 设置已保存");
            };

            shadow.getElementById('reset-settings').onclick = () => {
                Object.assign(SETTINGS, DEFAULT_SETTINGS);
                saveSettings();
                renderSettings();
                alert("↺ 已恢复默认设置");
            };
        }

        // ---- 日志视图 ----
        function renderLogs() {
            const lines = Logger.all();
            const listHtml = lines.length
                ? lines.map(line => `
                    <div class="log-line log-${escapeHtml(line.level).toLowerCase()}">
                        <span class="log-time">${new Date(line.t).toLocaleTimeString()}</span>
                        <span class="log-level">${escapeHtml(line.level)}</span>
                        <span class="log-msg">${escapeHtml(line.message)}</span>
                    </div>
                `).join('')
                : '<div class="empty"><div class="empty-title">暂无诊断日志</div><div class="empty-hint">开启日志开关后，运行轨迹会记录在这里</div></div>';

            bodyEl.innerHTML = `
                <div class="card-block">
                    <div class="log-actions">
                        <button class="top-btn top-btn-danger" id="clear-logs">🧹 清空日志</button>
                    </div>
                    <div class="log-list">${listHtml}</div>
                </div>
            `;

            shadow.getElementById('clear-logs').onclick = () => { Logger.clear(); renderLogs(); };
        }

        // ---- 静态绑定 ----
        floatBtn.onclick = async () => {
            overlay.style.display = 'flex';
            state.view = 'list';
            await refresh();
        };
        shadow.getElementById('close-panel').onclick = () => { overlay.style.display = 'none'; };
        overlay.onclick = (e) => { if (e.target === overlay) overlay.style.display = 'none'; };

        shadow.querySelectorAll('[data-nav]').forEach(btn => {
            btn.onclick = () => {
                state.view = btn.getAttribute('data-nav');
                render();
            };
        });

        shadow.getElementById('btn-export').onclick = async () => {
            const list = await DBManager.getAll();
            let text;
            try { text = CommentTransfer.encode(list); }
            catch (err) { alert('导出失败: ' + err.message); return; }
            const blob = new Blob([text], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url; a.download = `biliSendCheck_backup_${Date.now()}.json`;
            a.click(); URL.revokeObjectURL(url);
            Logger.info(`导出备份 ${list.length} 条`);
        };

        shadow.getElementById('btn-import').onclick = () => fileInput.click();

        fileInput.onchange = async (e) => {
            const file = e.target.files[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = async (event) => {
                try {
                    const plan = DBManager.importBackup(event.target.result);
                    importPlan = plan;
                    state.changesOnly = false;
                    state.view = 'list';
                    Logger.info(`导入完成：新增 ${plan.added} 条，更新状态 ${plan.changes.size} 条，保留本地有效状态 ${plan.kept} 条`);
                    await refresh();
                } catch (err) {
                    Logger.error(`导入失败: ${err.message}`);
                    alert("❌ 导入失败: " + err.message);
                }
                fileInput.value = "";
            };
            reader.readAsText(file);
        };

        shadow.getElementById('btn-undo').onclick = async () => {
            if (!confirm('恢复上次操作前的完整本地列表？将覆盖之后的本地改动，无法恢复已在 B 站真实删除的评论。')) return;
            try {
                DBManager.undo(); importPlan = null; state.changesOnly = false;
                state.view = 'list'; await refresh();
            } catch (err) { alert('恢复失败: ' + err.message); }
        };
        shadow.getElementById('btn-clear').onclick = async () => {
            if (confirm("⚠️ 确定要清空本地所有记录吗？可撤销，但下一次本地删除、清空或导入会替换这份备份。")) {
                try { await DBManager.clear(); }
                catch (err) { alert('清空失败: ' + err.message); return; }
                importPlan = null; state.changesOnly = false;
                Logger.warn("已清空全部本地记录");
                await refresh();
            }
        };

        bodyEl.addEventListener('click', handleBodyClick);

        // ---- 提示浮层 ----
        return class Toast {
            constructor(initText, quoteText = "") {
                this._attempt = 0;
                this.el = document.createElement('div'); this.el.className = 'toast'; this.quoteText = quoteText;
                this.el.innerHTML = `<div class="header"><h4 class="title">biliSendCheck 检测中...</h4><button class="close-btn">×</button></div><p class="message">${escapeHtml(initText)}</p>${quoteText ? `<div class="quote" title="点击复制文案">${escapeHtml(quoteText)}</div>` : ''}<div class="action-bar" style="display:none;"></div><div class="progress-line progress-indeterminate"></div>`;
                this.titleEl = this.el.querySelector('.title'); this.msgEl = this.el.querySelector('.message'); this.actionEl = this.el.querySelector('.action-bar'); this.progressEl = this.el.querySelector('.progress-line');
                this.el.querySelector('.close-btn').onclick = () => this.close();
                const quoteEl = this.el.querySelector('.quote');
                if (quoteEl) quoteEl.onclick = () => { GM_setClipboard(this.quoteText); const orig = quoteEl.innerHTML; quoteEl.style.color = '#00A1D6'; quoteEl.innerHTML = '✅ 已复制！'; setTimeout(() => { quoteEl.style.color = ''; quoteEl.innerHTML = orig; }, 2000); };
                shadow.appendChild(this.el);
            }
            update(t, m, c = "") { this.titleEl.textContent = t; this.msgEl.innerHTML = escapeHtml(m).replace(/\n/g, '<br>'); this.progressEl.classList.remove('progress-indeterminate'); this.progressEl.style.width = '100%'; this.el.classList.remove('status-ok', 'status-ban', 'status-del', 'status-inv'); if (c) { this.el.classList.add(c); this.progressEl.style.display = 'none'; } }
            resetToLoading(t, m) { this.el.className = 'toast'; this.titleEl.textContent = t; this.msgEl.innerHTML = escapeHtml(m).replace(/\n/g, '<br>'); this.actionEl.style.display = 'none'; this.progressEl.style.display = 'block'; this.progressEl.classList.add('progress-indeterminate'); this.progressEl.style.width = '30%'; }
            showActions(onRetry = null) { this.actionEl.style.display = 'flex'; this.actionEl.innerHTML = `<button class="btn btn-close">知道了</button>`; if (onRetry) { this.actionEl.innerHTML = `<button class="btn btn-retry" style="background:#FB7299; color:#fff; border:none;">重新检测</button>` + this.actionEl.innerHTML; this.actionEl.querySelector('.btn-retry').onclick = () => { this._attempt = 0; this.resetToLoading("重新检测中...", "正在再次向服务器核实状态..."); onRetry(); }; } this.actionEl.querySelector('.btn-close').onclick = () => this.close(); }
            close() { this.el.classList.add('closing'); setTimeout(() => this.el.remove(), 300); }
        };
    })();

    // ================= 7. 动态 (Opus) 详情页全息扫描 =================
    async function checkDynamicOnLoad() {
        const hostname = window.location.hostname;
        const pathname = window.location.pathname;
        let id = null;

        if (hostname === 't.bilibili.com') {
            id = pathname.split('/')[1];
        } else if (hostname === 'www.bilibili.com' && pathname.startsWith('/opus/')) {
            id = pathname.split('/')[2];
        }

        if (id && /^\d+$/.test(id)) {
            const toast = new UIManager("正在检测动态风控状态...", `动态 ID: ${id}`);
            try {
                const url = `https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=${id}`;
                const resp = await fetchBilibili(url, true);
                const code = resp ? resp.code : -1;
                Logger.info(`动态检测 id=${id} 响应码=${code}`);

                if (code === -352 || code === 4101131) {
                    toast.update("🔴 动态仅自己可见", "检测到此动态被 Shadowban！\n路人视角被风控拦截或提示不存在。", "status-ban");
                    toast.showActions();
                } else if (code === 500) {
                    toast.update("🟡 动态疑似审核中", "动态被锁定，可能处于审核期，他人不可见。", "status-inv");
                    toast.showActions();
                } else if (code === 0) {
                    toast.update("🟢 动态正常显示", "该动态状态健康，未被限流或隐藏。", "status-ok");
                    setTimeout(() => toast.close(), 5000); // 正常显示 5 秒后自动隐退
                } else {
                    toast.update("⚫ 动态异常", `响应码: ${code}`, "status-del");
                    toast.showActions();
                }
            } catch (error) {
                Logger.error(`动态检测异常: ${error.message}`);
                toast.update("❌ 检测发生错误", "无法获取动态状态。");
                toast.showActions();
            }
        }
    }

    // ================= 8. 拦截与工作流 =================
    unsafeWindow.fetch = async function (...args) {
        const response = await originalFetch.apply(this, args);
        const url = args[0] instanceof Request ? args[0].url : args[0];
        if (typeof url === 'string' && url.includes("/x/v2/reply/add")) {
            // 取原文而非 clone().json()：clone().json() 内部就是 JSON.parse，
            // 19 位动态 oid 会被改写末几位，后续查的就是"另一个动态"。
            // parseJsonExact 让超长裸整数以字符串形态进来，位数原样保留。
            response.clone().text().then(text => {
                let res = null;
                try { res = parseJsonExact(text); } catch (e) { res = null; }
                if (res && res.code === 0 && res.data && res.data.reply) {
                    Logger.info(`捕获发评 rpid=${res.data.reply.rpid}`);
                    startCheckWorkflow(res.data.reply);
                } else if (res && res.code !== 0) {
                    Logger.warn(`发评接口返回非 0: code=${res.code} ${res.message || ''}`);
                }
            }).catch(err => Logger.warn(`发评响应解析失败: ${err.message}`));
        }
        return response;
    };

    /** 从发评响应提取入库元信息（浏览器侧直接取网页 URL 里的 BV 号） */
    function buildRecord(reply) {
        const currentBvid = getCurrentBvid();
        // oid 由 parseJsonExact 解析，超长 id（19 位动态）已是原样数字串，直接使用。
        const oid = reply.oid;
        const sourceId = currentBvid
            || (reply.type == 1 ? "av" + oid : (reply.type == 12 ? "cv" + oid : null));
        const record = {
            rpid: reply.rpid,
            oid: oid,
            type: reply.type,
            root: reply.root || 0,
            parent: reply.parent || 0,
            uid: reply.mid || 0,
            source_id: sourceId,
            origin_url: window.location.href,
            message: reply.content && reply.content.message ? reply.content.message : '',
            post_time: reply.ctime ? reply.ctime * 1000 : 0
        };
        return record;
    }

    const STATUS_TOAST_MESSAGE = {
        normal: '路人视角可见！',
        shadowban: '系统判定违规，他人不可见。',
        invisible: '评论存在于接口中，但前端被隐藏。',
        deleted: '发出的瞬间被删或正在审核。',
        review: '评论可能处于先审后发队列。',
        root_gone: '所属根评论已不存在，子评论自身是否仍在无法核查。',
        unknown: '路人视角本轮不可用（风控或网络波动），未作判定，请稍后重新检测。'
    };

    async function startCheckWorkflow(reply, existingToast = null, preparedBase = null) {
        const isRetry = existingToast !== null;
        const toast = existingToast || new UIManager(
            `等待 ${SETTINGS.waitSeconds} 秒后开始检测...`,
            reply.content ? reply.content.message : ''
        );
        const base = preparedBase || buildRecord(reply);

        try {
            if (!isRetry) {
                // 先以 UNKNOWN 入库：即使随后探测异常或页面被关闭，这条记录也不会丢
                await DBManager.save(Object.assign({}, base, {
                    initial_status: 'unknown',
                    status: 'unknown',
                    timestamp: Date.now()
                }));
                await new Promise(r => setTimeout(r, SETTINGS.waitSeconds * 1000));
            }

            // 失真的 id 会凭空伪造 -404（详见 untrustedIds）。宁可这一轮不判定，也不拿它去换一个假结论。
            const badIds = untrustedIds(base);
            if (badIds.length) {
                Logger.warn(`检测中止 rpid=${base.rpid}: 记录缺精确 ID (${badIds.join(', ')})，拒绝用失真 ID 查询`);
                toast.update("❓ 无法判定", `评论 ID 超出 JS 安全整数上限且本轮未能取回精确值（${badIds.join('、')}），脚本拒绝用失真的 ID 查询（会被服务端误当成"对象不存在"而误报已删除）。`, "status-inv");
                toast.showActions();
                await DBManager.save(Object.assign({}, base, { status: 'unknown', timestamp: Date.now() }));
                return;
            }

            const sentAt = reply.ctime || Math.floor(Date.now() / 1000);
            const res = await evaluateCommentStatus(
                base.oid, base.type, base.rpid, base.root, sentAt
            );
            const status = normalizeStatus(res.status);
            const meta = statusMeta(status);
            Logger.info(`检测结论 rpid=${base.rpid} -> ${status}${res.reason ? ' (' + res.reason + ')' : ''}`);

            // 本轮拿不到确定结论：不落锤，自动重试；仍失败则保留"待确定"，绝不误报仅自己可见
            if (status === 'unknown') {
                const attempt = (toast._attempt || 0) + 1;
                if (attempt <= SETTINGS.retryOnError) {
                    toast._attempt = attempt;
                    toast.resetToLoading(`未能取得明确结论，正在重试 (${attempt}/${SETTINGS.retryOnError})...`, '可能被风控或网络波动拦截，正在重试...');
                    setTimeout(() => startCheckWorkflow(reply, toast, base), 2000 * attempt);
                    return;
                }
                toast.update("❓ 未能取得明确结论", STATUS_TOAST_MESSAGE.unknown, "status-inv");
                toast.showActions(() => startCheckWorkflow(reply, toast, base));
                toast._attempt = 0;
                await DBManager.save(Object.assign({}, base, { status: 'unknown', timestamp: Date.now() }));
                return;
            }

            toast.update(meta.title, STATUS_TOAST_MESSAGE[status] || '检测完成。', meta.toast);
            if (status === 'shadowban' || status === 'deleted') {
                toast.showActions(() => startCheckWorkflow(reply, toast, base));
            } else {
                toast.showActions();
            }
            toast._attempt = 0;

            // 💾 记录真实 BV 号与全元信息
            const serverPostTime = reply.ctime
                ? reply.ctime * 1000
                : (res.ctime ? res.ctime * 1000 : (base.post_time || Date.now()));
            const previous = DBManager.getByRpid(base.rpid);
            const previousInitial = previous ? normalizeStatus(previous.initial_status) : 'unknown';

            await DBManager.save(Object.assign({}, base, {
                post_time: serverPostTime,
                // 初始状态 = 首次取得的真实结论；已有时保留，避免复检覆盖"秋后算账"的判定基准
                initial_status: previousInitial !== 'unknown' ? previous.initial_status : status,
                status: status,
                timestamp: Date.now()
            }));
            Logger.info(`检测完成 rpid=${base.rpid} source=${base.source_id || '-'} -> ${status}`);

        } catch (error) {
            Logger.error(`检测异常 rpid=${base.rpid}: ${error.message}`);

            const attempt = (toast._attempt || 0) + 1;
            if (attempt <= SETTINGS.retryOnError) {
                toast._attempt = attempt;
                toast.resetToLoading(`检测失败，正在自动重试 (${attempt}/${SETTINGS.retryOnError})...`, error.message);
                setTimeout(() => startCheckWorkflow(reply, toast, base), 1500 * attempt);
                return;
            }

            toast.update("❌ 检测发生错误", `请检查网络或稍后手动重试。\n${error.message}`);
            toast.showActions(() => startCheckWorkflow(reply, toast, base));
        }
    }

    window.addEventListener('load', async () => {
        try {
            await DBManager.init();
            checkDynamicOnLoad();
            Logger.info(`biliSendCheck v0.1.4 引擎就绪（等待 ${SETTINGS.waitSeconds}s，重试 ${SETTINGS.retryOnError} 次）`);
        } catch (e) {
            console.error("🔴 [biliSendCheck] 初始化失败:", e);
        }
    });

})();
