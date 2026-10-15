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

> 本仓库只包含模块自身的源代码，不包含任何第三方客户端的代码、资源或反编译产物。

## 构建

需要 JDK 17 与 Android SDK。

```bash
./gradlew :app:testDebugUnitTest                      # 单元测试（本地检索、链接格式化、探针解析等纯逻辑）
./gradlew :app:assembleDebug                          # 调试包
./gradlew :app:assembleRelease                        # 发布包（需签名配置）
```

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
