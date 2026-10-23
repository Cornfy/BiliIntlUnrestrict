# BiliIntl Unrestrict

> 专为哔哩哔哩海外版设计的发评反诈全息中心与功能增强模块。
> Xposed module for the international Bilibili app: know whether your comment really went public.

![License](https://img.shields.io/badge/license-MIT-green)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-blue)
![API](https://img.shields.io/badge/libxposed-102-orange)

## 这是什么

在 B 站国际版里发出的评论，你看到的"发送成功"未必等于别人也能看到。
本模块会在**评论发送后自动追踪它的真实存活状态**——用「路人视角」和「作者视角」两路查询做差分，
判断这条评论到底是正常展示、仅自己可见、已被删除还是处于审核队列，
并把结果连同评论正文一起沉淀成本地档案，可以随时检索、复检、导出。

除核心的发评追踪之外，还附带几项针对国际版的体验修正。

数据包名：`com.bilibili.app.in`（哔哩哔哩国际版）。
已验证版本：6.4.0 ~ 6.7.1

## 功能

### 1. 发评反诈全息中心（核心）

- 自动捕获自己发出的评论，记录正文、目标评论区、楼层、发送与检查时间。
- 延迟启动双视角探针，给每条评论定性：正常展示 / 仅自己可见 / 已失效 / 软屏蔽 / 审核中 / 待确认。
- 保留**初始状态**与**当前状态**两个快照，因此能识别出"秋后算账"：当初正常、几天后突然变成仅自己可见。
- 列表可按状态筛选，支持按评论正文、来源标识或评论 ID 检索并高亮命中位置。
- 单条支持：手动复检更新状态、复制正文、复制并唤起 Scheme 直达现场、本地移除、调用官方接口彻底删评。
- 全部记录可导出为 JSON 备份，也可从备份导入。

### 2. 青少年模式中和

国际版会因 COPPA 触发额外的青少年模式限制。开启后对其做中和处理，无需上传身份证件即可恢复完整的成人浏览体验。

### 3. 首页刷新保留历史视频流

默认情况下拉刷新会丢弃上一批推荐内容。开启后在保留历史视频的同时于顶部追加新视频，
并设置更高的保留上限，浏览记录不会因为一次刷新就消失。

### 4. 净化视频分享链接

复制或系统分享时输出「标题 + 换行 + 长链接」的清爽格式，保留分 P 参数，移除已知的追踪参数。

### 5. 运行诊断日志

模块在宿主进程内的挂载、拦截、探测与通信记录可写入本地日志页查看，支持动态开关，便于排查"某个版本怎么不生效了"。

除发评追踪（始终开启）之外，其余每一项在「功能增强」页都有独立开关，关闭后立即生效，不需要重启。

## 安装

前置条件：

- Android 8.0（API 26）及以上
- 已 Root，装有支持 libxposed API 102 的框架（如较新版本的 LSPosed）
- 已安装哔哩哔哩国际版 `com.bilibili.app.in`

步骤：

1. 从 Releases 下载 APK 并安装。
2. 在框架管理器中启用本模块，并把作用域勾选为 `com.bilibili.app.in`。
3. 强制停止哔哩哔哩国际版并重新打开。
4. 打开本模块的界面确认开关状态；在 B 站里发一条评论，几秒后即可在这里看到它的定性结果。

> 本仓库只包含模块与用户脚本自身的源代码，不包含任何第三方客户端的代码、资源或反编译产物。

## 评论检测改进

模块与浏览器脚本采用一致的双视角判定规则，并修正了几条可能误报的路径：

- 楼中楼时序二分达到请求上限而尚未收敛时，返回待确定。
- 作者视角上一页请求失败时保留失败结果，延迟复验同时覆盖目标页与上一页。
- 模块按 JSON 结构读取评论及 `invisible` 标记，避免嵌套对象或不完整响应导致误判。
- 复检未能取得明确结果时，保留已有有效结论及其检查时间；首次有效状态也会被保留。

请求渠道、登录凭证及检测时机仍随平台不同，因此两端不保证每次拿到相同响应。

## 浏览器用户脚本

新增 [biliSendCheck](userscript/README.md)（[安装文件](userscript/biliSendCheck.user.js)），可在桌面浏览器通过 Tampermonkey 使用，无需 Root 或 LSPosed。
在 GitHub 打开脚本文件的 **Raw** 页面即可交给脚本管理器安装，安装后刷新 B 站网页。

脚本提供发评捕获、双视角检测、历史检索、复检、JSON 导入导出及官方删评。
通过页面浮动按钮进入历史列表和设置；当前捕获覆盖网页 `fetch` 发评路径。
详细安装步骤、状态说明、限制和测试命令见[脚本 README（英文）](userscript/README.md)。

## 双端备份交换

模块与脚本各自在本机保存记录，通过「导出 JSON → 另一端导入」手动合并，不使用云同步，不传播本地删除。
JSON 顶层仍是数组，保留原有 13 个字段，APK 数据库未增加字段或升级版本：

```text
rpid, oid, type, root, parent, uid, source_id, origin_url,
message, initial_status, status, post_time, timestamp
```

ID 导出为精确数值；APK 内部使用 Long，脚本内部保留精确数字串后按 ID 字段写出数值，正文由标准 JSON 转义处理。
新版导入也兼容字符串 ID，并在写入前校验整份数据；无效、重复或身份字段冲突的记录会使整份导入失败。

| 本地状态 | 导入状态 | 合并结果 |
| --- | --- | --- |
| 未知 | 明确结论 | 采用导入状态 |
| 明确结论 | 未知 | 保留本地有效状态和检查时间 |
| 明确结论 | 不同的明确结论 | 采用导入状态及其原检查时间，即使它更旧 |

已有非空正文、原发布时间和有效初始状态保留，缺失信息由导入记录补齐。
导入完成后可点击「查看变化」，临时筛选本次状态发生变化的记录并显示原状态与导入状态，无需逐条确认。

本地删除、清空或实际改变记录的导入前，自动保留一份完整快照；下一次上述操作会替换快照，普通检测、导出和无变化导入不会占用它。
APK 将快照保存在私有目录，脚本使用独立的 GM 存储键。「撤销」恢复完整列表并覆盖操作后的本地改动，成功后消费快照。
这只能恢复本地记录，无法恢复已经在 B 站真实删除的评论。

### BiliPai 及旧版客户端

备份沿用 [BiliPai 评论反诈历史中心 PR #734](https://github.com/jay3-yy/BiliPai/pull/734) 中的 13 字段记录格式与数值 ID，可用于该格式的备份交换。
这里的兼容性依据该次贡献的实现，不代表已经逐一验证 BiliPai 当前各发行版，也不代表两者的检测算法或结果保持同步。

该实现导入时直接替换同 ID 的记录，且不认识 `ROOT_GONE`，会将其显示为未知。
旧版模块和 BiliPai 不会因为读取新版 JSON 就获得这里的合并保护或撤销功能；在旧端导入、复检前请先保留导出文件。

## 构建

需要 JDK 17 与 Android SDK。

默认将 Gradle 下载和依赖缓存放在项目的 `.gradle-local/` 中；也可以通过 `GRADLE_USER_HOME` 指定其他位置。临时构建并安装调试包：

```bash
export GRADLE_USER_HOME="$PWD/.gradle-local"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

如果设备上已有用发布密钥签名的同包名 APK，调试签名无法覆盖安装。要保留应用数据并原地升级，请改用本地发布签名构建：

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

发布签名构建需要下节说明的 `signing.properties`。若签名不同，Android 会拒绝覆盖安装；卸载旧版再安装会清除该应用的本地数据。

发布包需要私钥，在项目根目录准备 `signing.properties`（**不会进版本库**）：

```properties
KEYSTORE_FILE=my-release-key.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=...
KEY_PASSWORD=...
```

签名产物可用 `apksigner verify` 校验。仓库内的 GitHub Actions 工作流会在推送时自动编译调试包并作为构件上传。

## 项目结构

```
app/src/main/java/me/bili/unrestrict/
├── MainHook.kt                       # libxposed 102 入口，分两阶段挂载
├── hook/
│   ├── CommentCaptureHook.kt         # 发评捕获（多条数据栈）
│   ├── TeenagerBypassHook.kt         # 青少年模式中和
│   ├── FeedRetainHook.kt             # 首页刷新保留
│   ├── FeedRetainResolver.kt         # 推荐流配置的定位
│   ├── FeedRetainPolicy.kt           # 改写策略与降级路径
│   ├── ShareLinkHook.kt              # 分享链接净化
│   ├── ShareLinkFormatter.kt         # 链接格式化与参数清理
│   └── ConfigManager.kt              # 跨进程配置总线
├── detector/CommentProbeEngine.kt    # 双视角差分探针，负责给评论定性
├── data/
│   ├── db/                           # Room 实体与 DAO
│   ├── model/                        # 状态枚举与本地检索
│   └── repository/                   # 导入导出、复检、官方删评
├── ipc/                              # ContentProvider 与广播双通道同步
└── ui/                               # Compose 界面（历史、设置、日志）
```

宿主的实现细节随版本漂移，因此这里的定位尽量依赖**服务端下发的字段契约**与**业务包名锚点**，
而不是硬编码符号名；定位失败会留下明确日志，不做静默降级。

## 数据与隐私

- 所有记录保存在本机数据库中，不会上传到任何服务器。
- 「彻底删评」需要读取本机已存的 B 站登录凭证来调用官方删除接口，凭证不出本机。
- 诊断日志同样只落在本机，可由用户导出或清空。

## 免责声明

本项目仅用于个人设备上的学习研究，请以遵守当地法律法规与平台服务条款为前提使用。
作者不对账号风险、数据损失或任何形式的误用负责。请勿用于商业用途或面向他人的分发改造。

## 致谢

本模块「发评反诈」的核心思路——以「路人视角 / 作者视角」双路查询做差分，判定一条评论是正常展示、
仅自己可见还是已被删除——来自开源项目
[freedom-introvert/biliSendCommAntifraud](https://github.com/freedom-introvert/biliSendCommAntifraud)
（哔哩发评反诈，AGPL-3.0）及其作者对 B 站控评机制的公开研究，在此致谢。

本模块是面向哔哩哔哩国际版的 Xposed **独立实现**：代码全部自行编写，
未复制、未移植、未链接上述项目及其用户脚本的任何源代码，两者之间不存在代码层面的衍生关系，
本项目继续以 MIT 许可发布。

## 许可证

[MIT License](LICENSE) © Cornfy
