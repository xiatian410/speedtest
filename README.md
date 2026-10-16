# BetterSpeedtest (Android)

Android 原生网络测速应用，以 [better-speedtest](https://github.com/Gaimoydev/better-speedtest) 的协议与引擎为基础，界面使用 [MeowUI](https://github.com/lingqiqi5211/MeowUI) 的 Material 3 Expressive / Miuix 双风格组件。

本文描述 **2026-10-11 当前代码**。`PROJECT.md` 保留开发历史，其中早期 CDN、自定义源及部分界面记录已不代表现状。

## 当前功能

- 全球网测 / Speedtest.net 两种服务，自动模式依据 IP 位置选择；未产生正式传输流量的节点失败时允许换点，自动模式可回退服务，已有正式传输流量后不自动重复大流量测试。
- 双向、仅下载、仅上传；手选节点、搜索、刷新和延迟预检；最多 4 个源聚合。
- 下载 / 上传实时曲线、平均与峰值速率、TCP 连接延迟、抖动、IP 与位置、过程日志。
- Mbps / MB/s / Gbps 统一显示；自动或固定量程、大字或紧凑数显、刻度与弧端进度点。
- 本地历史最多 200 条，复制完整结果、系统分享、确认清空；存储失败会显示错误。
- 深浅主题、动态色、Miuix Monet、AMOLED、可选模糊、界面缩放。
- 独立打流页（底部「打流」）：自定义 HTTP/HTTPS URL 持续传输、全球网测 / Speedtest.net 节点循环；下载、上传或双向同时；连接数、共享限速、时长和总流量限制；前台服务通知与停止按钮。

普通测速仍只有全球网测 / Speedtest.net，打流页单独提供自定义 URL。旧偏好数据不会被主动删除。没有 Go 原版的 AT 串口、MCC-MNC 表、UFI 插件和二进制自更新功能。

## 打流使用

入口为底部导航 **测速 / 打流 / 历史 / 设置** 中的「打流」，原测速首页布局不变。打流页独立展示运行状态、上下行速率卡片、累计流量和时间、开始 / 停止操作，参数位于下方。切换标签保留参数与运行状态，通知直接返回打流标签。

- 自定义 URL：下载使用 GET，上传向接收二进制数据的接口循环发送 1 MiB 的 `application/octet-stream` POST；需要有效的 HTTP/HTTPS URL，不是任意网页，也不是 iperf3/UDP 协议。上传不自动跟随重定向。
- 测速节点循环：支持自动选点或手选节点，每轮重新连接与校验，默认每轮 30 秒；双向模式同时运行下载与上传。节点循环单独统计，不写入普通测速历史。
- 默认每方向 2 个连接，运行 60 秒、总上限 256 MiB、速率不限。连接可选 1–16，每轮 5–120 秒，整个任务最长 6 小时。时长或流量任一条件达到即停止。
- 限速是上下行全部连接共享的应用层上限，0 表示不限，实际速率仍取决于网络和服务器。流量预算统计 HTTP 正文，不含请求头、TCP/TLS 开销、重传、选点控制请求和网络缓冲；不能当作运营商计费上限。
- 页面展示双向实时速率、累计字节、时间、轮数、失败次数和上传成功响应数。上传字节仅表示本机写出，服务端成功响应才计为确认；限额或时间结束可能截断最后一条上传请求。
- 临时错误最多重试 3 次，退避 1/2/4 秒。节点自动选择遇下载 HTTP 403 时排除该节点并切换，最多遇到 8 个拒绝节点后结束，整段过程共用时间和流量预算；手选节点、自定义 URL 不自动换目标。其他永久 HTTP 4xx（408/429 除外）直接结束并显示方向与状态码。停止时关闭连接并等待上传请求退出，统计不继续增长。
- 前台服务支持离开页面或应用后继续运行，通知可返回页面和停止。普通测速与打流互斥；进程被杀或系统强制停止后不会自动重新打流。拒绝通知权限后仍可在应用内停止。

后台传输采用 Android [`dataSync` 前台服务](https://developer.android.com/develop/background-work/services/fgs/service-types)，处理[系统运行时限](https://developer.android.com/develop/background-work/services/fgs/timeout)，并使用随任务释放的有时限唤醒锁；仍受系统与厂商后台策略约束。

## 默认测速参数与统计

| 参数 | 默认值 |
| --- | --- |
| 单方向时长 | 15 秒 |
| 预热 | 2 秒 |
| 下载连接 | 初始 6，上限 16 |
| 上传连接 | 初始 4，上限 8 |
| 多源聚合 | 1 |
| 采样 | 200ms |

均值来自有效测量区间的字节增量 / 时间；显示曲线与峰值使用约 1 秒滚动窗口，预热结束时重置统计窗口。流量按应用读写字节计算，包含预热，不包含协议头及底层网络开销。Ookla 选点采用可达性预筛加少量节点的短时吞吐比较，探测流量单独存为 `probeBytes`，并计入结果总流量；仅上传模式不做下载探测。

延迟是 TCP 连接耗时，不是 ICMP 或负载期间延迟；抖动是相邻成功样本耗时差的绝对值均值。上传主要计量本机写入数据，无法等同于服务端实际接收量。服务器拒绝、无数据或未收到确认时会反馈相应状态。

## 结构

单 `:app` 模块，包名 `dev.gaimoy.betterspeedtest`：

- `MainActivity.kt`：MeowUI 主题、页面导航、设置组装与操作入口。
- `SpeedtestViewModel.kt`：持有测速、历史和手选节点，配置重建保留状态，清理时关闭任务；换网时刷新定位缓存。
- `core/SpeedtestRunner.kt`：测速编排与 `StateFlow<TestUiState>`；本轮参数快照与 UI 同步。
- `core/Engine.kt`：传输 worker、连接回收、自适应爬坡、TCP ping。
- `core/TrafficRunner.kt`、`TrafficTransport.kt`、`TrafficBudget.kt`、`TrafficModels.kt`：打流会话、节点协议、共享流量预算与限速。
- `TrafficService.kt`：打流前台服务、通知、后台状态和资源释放。
- `core/TransferMeter.kt`：有效时间窗口、平均与平滑峰值。
- `core/HttpResponseReader.kt`：原始 HTTP 响应头、定长与 chunked 正文解析。
- `core/NetworkRequests.kt`：可取消的控制请求、有界响应读取和短时缓存。
- `core/BoundedDnsResolver.kt`：限制 DNS 线程与排队数量，取消时可立即结束等待。
- `core/Cnspeed.kt`、`Ookla.kt`、`IpLoc.kt`：第三方服务协议与定位。
- `core/NodeCatalog.kt`、`PingTargets.kt`：节点目录及 IPv4/IPv6/带端口主机解析。
- `data/`：可观察设置、原子历史持久化、错误反馈。
- `ui/`：测速、历史、节点、设置、外观页及自绘表盘/曲线。
- `app/src/test/`：协议、计量、取消、缓存、持久化、偏好、单位与分享回归测试。

`../_src/MeowUI-main` 与 `../_src/better-speedtest-main` 是参考源码。实际依赖 Maven `io.github.lingqiqi5211.meowui:meowui:0.1.5`，没有 composite build，也没有 Xposed 依赖。

## 构建与验证

JDK 21、Gradle 9.7.1、Android SDK platform 37.0；SDK 路径由本机 `local.properties` 指定。版本定义见 `gradle/libs.versions.toml`，不需要升级工具链。

PowerShell（当前工作目录为 Android 工程）：

```powershell
$env:JAVA_HOME = 'D:\JAVA\zulu21.30.15-ca-jdk21.0.1-win_x64'
& '..\tools\gradle-9.7.1\bin\gradle.bat' --offline --no-daemon :app:testDebugUnitTest :app:assembleRelease
```

首次构建或依赖缓存缺失时去掉 `--offline`。Release APK 为 `app/build/outputs/apk/release/app-release.apk`。当前 release 仍使用 debug 签名、未启用混淆，正式发布应配置自己的签名。

隔离 UI 验证可选：

```powershell
& '..\tools\gradle-9.7.1\bin\gradle.bat' --offline --no-daemon '-PqaAppIdSuffix=.qa' :app:assembleDebug
```

生成独立包 `dev.gaimoy.betterspeedtest.qa`，与原应用设置和历史隔离；未传属性时 debug 包名保持原值。APK 安装和视觉验证记录见 `PROJECT.md` 的最新阶段。

## MeowUI 接入约定

参照官方[设计规范](https://github.com/lingqiqi5211/MeowUI/blob/main/docs/design-guidelines.md)与[组件手册](https://github.com/lingqiqi5211/MeowUI/blob/main/docs/components.md)检查组件接线，同时保留本项目原有界面设计：

- 保留原有页面顺序、胶囊模式切换、指标、历史卡片和表盘样式；本轮只修复显示、适配、状态和交互缺陷。
- 设置、导航继续使用 MeowUI；不以修复为由更换原有按钮、卡片或配色。
- 页面只有一个主要纵向滚动容器，消费 Scaffold 的真实内边距；窄屏、大字与横屏可滚动。
- 高频采样仅驱动表盘与曲线重组，其余组件订阅去重后的状态切片；动画在绘制阶段读值，导航与设置不随速率每帧重组。
- 不套用另一种风格的固定配色，不重建额外的主题修色系统；模糊为可选增强，默认关闭。

## 当前边界

- 全球网测控制端为兼容自签证书，保留固定域名 `dlcv2.cnspeedtest.cn` 的 TLS 信任例外，禁止该客户端跟随重定向；尚无可信证书 pin，不能视为正常的证书认证。
- 普通测速随 Activity 配置重建保留，退出所在 Activity 后关闭；打流使用独立前台服务，进程被系统回收后不自动恢复。
- 服务端接口和公网网络条件会影响实际可用性；离线回归测试不能替代真机与多运营商验证。

## 打流功能验证（2026-10-11）

72 项 JVM 测试通过（含新增 13 项打流测试），Debug / Release 构建通过。Android 15 模拟器验证了本地双向 HTTP 传输、全球网测协议的 3 轮循环、流量 / 时间停止、通知返回与停止、后台服务和唤醒锁释放、小数限速输入以及 Miuix 大字体。公网节点的长期打流与真机广度尚未验证。

截图位于 `../_review/20261011-traffic/`，以 `final-` 前缀文件为最终界面依据。增加功能前快照位于 `../.snapshots/20261011-before-traffic`。模拟器验证使用独立 `.qa` 包，与原应用数据隔离。

下载 403 处理修复后共 75 项测试通过：新增自动换点、固定节点不换点、连续拒绝上限回归。小流量公网诊断复现了原测速协议也返回 403 的节点；没有声称所有公网节点已经恢复可用。

当前 APK SHA-256：`FC6BFB6619402DDCA8D528935709CFF358F742F8FC6FB44A9A808B99015549DD`。
