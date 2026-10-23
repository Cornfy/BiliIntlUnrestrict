const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync(require('node:path').join(__dirname, '../biliSendCheck.user.js'), 'utf8');
function engine(fetch) {
    const context = vm.createContext({ Logger: { info() {}, warn() {} }, fetchBilibili: fetch, Date, URL });
    const pure = source.split('// ==== 纯逻辑区（可独立测试）BEGIN ====')[1].split('// ==== 纯逻辑区（可独立测试）END ====')[0];
    const network = source.slice(source.indexOf('    function stamp('), source.indexOf('    // ================= 6.'));
    vm.runInContext('const SORT_TIME=2, RECHECK_DELAY_MS=0, BINARY_STEP_CAP=8; async function sleep() {}\n' + pure + network, context);
    return context;
}
const page = (count, replies = []) => ({ code: 0, data: { page: { count, size: 20 }, replies } });
test('exact root ID survives dispatch', async () => {
    const root = '1000000000000000001';
    const e = engine(async url => { assert.equal(new URL(url).searchParams.get('root'), root); return { code: 12006 }; });
    assert.equal((await e.evaluateCommentStatus('1', 1, '2', root, 1)).status, 'root_gone');
});
test('malformed success is unavailable', () => {
    assert.equal(engine().readProbe({ code: 0 }, '2').kind, 'unavailable');
});
test('nested invisible and page-only ctime range', () => {
    const e = engine();
    assert.equal(e.readProbe(page(1, [{ rpid: '2', content: {}, invisible: true }]), '2').invisible, true);
    assert.equal(e.readProbe({ code: 0, data: { config: { rpid: '2' } } }, '2').hit, false);
});
test('binary budget exhaustion cannot report deleted', async () => {
    let authorCalls = 0;
    const e = engine(async (url, anon) => {
        if (!anon) authorCalls++;
        const pn = Number(new URL(url).searchParams.get('pn'));
        return page(200000, [{ rpid: '9', ctime: pn * 100 }]);
    });
    assert.equal((await e.evaluateSubReply('1', 1, '2', '3', 9999999)).status, 'unknown');
    assert.equal(authorCalls, 0);
});
test('author previous-page failure cannot report deleted', async () => {
    const e = engine(async (url, anon) => {
        const pn = Number(new URL(url).searchParams.get('pn'));
        return !anon && pn === 1 ? null : page(40);
    });
    assert.equal((await e.evaluateSubReply('1', 1, '2', '3', 1)).status, 'unknown');
});
test('recheck includes previous page where public reply appears', async () => {
    let authorSeen = false;
    const e = engine(async (url, anon) => {
        const pn = Number(new URL(url).searchParams.get('pn'));
        if (!anon) { authorSeen = true; return page(40, [{ rpid: '2' }]); }
        return page(40, authorSeen && pn === 1 ? [{ rpid: '2' }] : []);
    });
    assert.equal((await e.evaluateSubReply('1', 1, '2', '3', 1)).status, 'normal');
});
test('page.count takes precedence over unrelated root.count', async () => {
    const e = engine(async url => {
        assert.equal(new URL(url).searchParams.get('pn'), '1');
        return { code: 0, data: { page: { count: 0, size: 20 }, root: { count: 10000 }, replies: [] } };
    });
    assert.equal((await e.evaluateSubReply('1', 1, '2', '3', 0)).status, 'deleted');
});
test('gone plus author hit must recheck before shadowban', async () => {
    let calls = 0;
    const e = engine(async (_, anon) => { calls++; return anon ? { code: 12006 } : page(1, [{ rpid: '2' }]); });
    assert.equal((await e.evaluateSubReply('1', 1, '2', '3', 1)).status, 'shadowban');
    assert.equal(calls, 3);
});
