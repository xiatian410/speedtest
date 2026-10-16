# BetterSpeedtest (Android) — 项目完整文档

> 多源网络测速 Android 应用 | Kotlin + Jetpack Compose + MeowUI
> 原项目:[Gaimoydev/better-speedtest](https://github.com/Gaimoydev/better-speedtest)(Go CLI)的 Android 重制版
> UI 库:[lingqiqi5211/MeowUI](https://github.com/lingqiqi5211/MeowUI)(Material 3 Expressive / Miuix 双风格)
> 文档更新:2026-10-11（阶段二十九：保留原设计的缺陷修复与性能优化）
>
> **阅读说明：** 当前功能、架构、默认参数与构建方式以 [README.md](README.md) 为准。本文前十节及阶段一至二十八保留历史描述；其中“三类源 / CDN”“没有单元测试”等内容不再代表当前代码。最新改动见阶段二十九。

---

## 目录

1. [项目概述](#1-项目概述)
2. [功能特性](#2-功能特性)
3. [技术栈](#3-技术栈)
4. [项目结构](#4-项目结构)
5. [核心机制详解](#5-核心机制详解)
6. [UI 设计说明](#6-ui-设计说明)
7. [构建与开发指南](#7-构建与开发指南)
8. [调试与验证方法](#8-调试与验证方法)
9. [与原版 Go 的差异对照](#9-与原版-go-的差异对照)
10. [已知限制与注意事项](#10-已知限制与注意事项)
11. [任务进度进程](#11-任务进度进程)
12. [路线图 / 待办](#12-路线图--待办)

---

## 1. 项目概述

**是什么**:一个 Android 原生网络测速 App。聚合三类测速源,自动或手动选择,测量下载/上传吞吐、TCP 延迟与抖动,实时仪表盘展示,结果本地持久化。

**从哪来**:上游是 Go 写的跨平台 CLI 工具 better-speedtest(面向 UFI 随身 WiFi 设备与服务器)。本项目把它的测速引擎**逐行移植**为 Kotlin 协程实现,并按 Android 平台特性裁剪(移除 AT 串口定位等设备端逻辑),套上 MeowUI 双风格界面。

**核心能力**(全部继承自原版):

| 能力 | 说明 |
|---|---|
| 三类测速源 | ①全球网测 cnspeedtest.cn(分省分运营商,私有协议) ②Speedtest.net/Ookla ③内置 CDN 大文件源 |
| 自动选源 | IP 定位 → 大陆判定:大陆走 cnspeed 就近同运营商节点;海外/港澳台走 CDN+Ookla 并发探测择优 |
| 多源聚合 | 同时使用最快的 N 个源,吞吐求和,突破单源限速 |
| 自适应连接数 | 从较少连接起步,吞吐大涨时加倍、临近饱和 +25%,平台期(≤5% 增益)回滚到最优值 |
| 并发吞吐探测 | CDN/Ookla 源各实测 2.5s 吞吐排序——延迟反映不了对等连接质量 |
| 诚实统计 | 1s 滚动平滑、均值/峰值均剔除预热段、上传 socket 限制发送缓冲防"假峰" |

**明确不做**(见 §9 差异对照):AT 串口查运营商、MCC-MNC 表、手动定位、UFI 插件模式、NDJSON 输出、二进制自更新。

---

## 2. 功能特性

### 测速
- 测速模式:**双测 / 仅下行 / 仅上行**(主页滑动胶囊切换,即改即存)
- 测速源:自动 / 全球网测 / CDN 下载 / Speedtest.net;自动模式下隐藏节点选择(全权托付);切换源自动清除已选节点
- 节点选择:进入后**只列当前「源」对应的节点**(标题如「全球网测节点」),点选即返回并回写主页;「恢复自动选择」高亮当前是否自动;可搜索、可测延迟。源在主页决定,节点页不再重复选源
- 可调参数:单向时长(5-30s)、多源聚合数(1-4)、预热时长、下载/上传初始连接数与上限

### 展示
- 单个大表盘:渐变进度弧 + 24 格刻度盘(已达到格点亮)+ 峰值标记点 + 两端量程数字;下载阶段主题色、上传阶段青色;数值 180ms 线性补间,200ms 采样
- 下载/上传**两张独立实时曲线卡**(双测一左一右,单方向整宽):各自状态(进行中 秒数/总时长|完成|待测)、均值/峰值;曲线纵轴按本方向峰值自适应
- 延迟/抖动:表盘正下方紧凑胶囊
- 状态卡:阶段文案 + IP 定位信息(运营商/省市/公网 IP)
- 过程日志:节点校验、探测结果、错误信息
- **结果分享**:测速完成后主页「分享结果」按钮;历史列表点按卡片即可分享文本卡片(时间/节点/上下行均值峰值/延迟抖动/连接数);同时写入剪贴板并 Toast 提示(精简 ROM 无分享目标时也能拿到结果)

### 其它
- 历史记录:本地 JSON 持久化(最多 200 条),列表展示,可清空
- **节点延迟预检**:节点选择页顶栏「测延迟」,对当前列表前 12 个节点并发 TCP 连接时延(count=2, 1.5s 超时),卡片右侧显示 `xx ms` / `超时`,按延迟着色(绿/中/灰/红)
- 外观:自建外观页(深浅模式滑动胶囊、MeowColorPicker 取色、调色板风格/色彩标准、AMOLED 纯黑、Monet、背景模糊、预测式返回、界面缩放),Material 3 Expressive / Miuix 两套风格运行时可切换
- 测速可随时停止;阶段切换无残留(方向立即切换)

---

## 3. 技术栈

### 应用层
| 项 | 版本/选型 | 说明 |
|---|---|---|
| 语言 | Kotlin 2.4.20 | JVM target 21 |
| UI | Jetpack Compose(BOM 随 MeowUI) | 单 Activity,无 XML |
| UI 库 | **MeowUI 0.1.5**(Maven Central: `io.github.lingqiqi5211.meowui:meowui`) | 双风格组件/主题/偏好绑定;源码仓库比发布版新,API 有差异(见 §10) |
| 网络 | OkHttp 5.4.0 | 常规请求 + HTTP/1.1 传输专用客户端 + 自签证书信任(cnspeed 控制端 8443) |
| 异步 | kotlinx-coroutines 1.11.0 | 传输 worker 池 / 采样循环 / StateFlow 事件流 |
| 序列化 | kotlinx-serialization 1.11.0 | Ookla 配置、cnspeed 节点表、历史记录 |
| 持久化 | SharedPreferences(经 MeowUI PreferenceStore 接口包装) + JSON 文件(历史) | |

### 原始 socket(全球网测协议)
`java.net.Socket` 直连测速节点,手工拼 HTTP 报文(下载 GET / 上传 multipart POST 无限流),MD5 token、DES/ECB/PKCS5Padding 解密 serverlist。

### 构建工具链
| 项 | 版本 | 位置(本机) |
|---|---|---|
| JDK | Zulu 21.0.1 | `D:\JAVA\zulu21.30.15-ca-jdk21.0.1-win_x64` |
| Gradle | 9.7.1(直用 gradle.bat,未用 wrapper) | `E:\APP\speedtest\tools\gradle-9.7.1` |
| AGP | 9.4.1 | — |
| Android SDK | platform 37 / build-tools 36.0.0 / platform-tools 37.0.1 | `D:\Android\Sdk` |
| compileSdk/targetSdk | 37(compileSdkMinor 0) | — |
| minSdk | 26(Android 8.0) | MeowUI 要求;Manifest 已加 `tools:overrideLibrary="top.yukonga.miuix.kmp.blur"` |

### 网络镜像(国内环境)
- Gradle 发行版:腾讯镜像 `mirrors.cloud.tencent.com/gradle/`(实测 2.7MB/s)
- Maven:Aliyun google 镜像 + 腾讯 maven-public + Maven Central + 官方 google 兜底(见 `settings.gradle.kts`)
- GitHub 代码:curl 直下 zip(codeload,断点续传)

### 测试设备
MuMu 模拟器,Android 15 x86_64,1440×2560,ADB `127.0.0.1:16384`(另有 5555/emulator-5554),adb 位于 `D:\platform-tools\adb.exe`。

---

## 4. 项目结构

```
E:\APP\speedtest\
├── BetterSpeedtest\                 # Android 工程(本项目)
│   ├── settings.gradle.kts          # 镜像仓库配置、模块声明
│   ├── build.gradle.kts             # 根插件声明
│   ├── gradle\libs.versions.toml    # 版本目录
│   ├── gradle.properties
│   ├── local.properties             # sdk.dir=D:\Android\Sdk
│   ├── README.md                    # 简版说明
│   ├── PROJECT.md                   # 本文档
│   └── app\
│       ├── build.gradle.kts         # 应用模块(release 暂用 debug 签名)
│       ├── proguard-rules.pro
│       └── src\main\
│           ├── AndroidManifest.xml  # INTERNET、cleartext、blur override
│           ├── res\                 # 主题/图标/字符串(全部自绘矢量)
│           └── kotlin\dev\gaimoy\betterspeedtest\
│               ├── MainActivity.kt      # 入口:主题接线、导航栈、选项组装、外观持久化
│               ├── core\                # ── 测速核心(全部可独立复用) ──
│               │   ├── Engine.kt        # 传输池/自适应爬坡/采样平滑/TCP ping/统计 + 4 种 worker
│               │   ├── SpeedtestRunner.kt# 编排器:自动选源/校验/探测/聚合 → StateFlow<TestUiState>
│               │   ├── PingTargets.kt   # 节点 ping 目标解析 + quickPing(列表预检)
│               │   ├── Cnspeed.kt       # 全球网测协议:token/IMEI/DES serverlist/mobilematch/dovalid
│               │   ├── Ookla.kt         # Speedtest.net config-sdk 列表 + URL 构建
│               │   ├── CdnSources.kt    # 内置 CDN 源表(15 个,继承原版)
│               │   ├── IpLoc.kt         # IP 定位(cnspeed+ipip)/大陆判定/节点排序匹配
│               │   ├── HttpClients.kt   # OkHttp 客户端族(plain/insecure/HTTP1.1 传输)
│               │   └── Model.kt         # Node/IpLocation/SpeedtestResult/TestSource 等模型
│               ├── data\
│               │   ├── AppPreferences.kt# SharedPreferences 版 MeowUI PreferenceStore + 全部设置键
│               │   └── HistoryStore.kt  # 历史 JSON 读写(互斥锁,上限 200 条)
│               └── ui\
│                   ├── MainScreen.kt       # 主页:状态卡/模式胶囊/表盘/指标胶囊/双曲线卡/日志/分享
│                   ├── NodePickerScreen.kt # 节点选择(跟随主页源 + 搜索 + 延迟预检,点选即返回)
│                   ├── ResultShare.kt      # 结果文本卡片 + 系统分享 Intent
│                   ├── SettingsScreen.kt   # 设置(测速参数/引擎高级/外观入口/关于)
│                   ├── HistoryScreen.kt    # 历史列表(点按分享)
│                   ├── AppearanceScreen.kt # 自建外观页(深浅模式用滑动胶囊)
│                   ├── Gauge.kt            # 速度表盘 + 单方向曲线(Canvas 自绘)
│                   └── SegmentedControl.kt # 滑动胶囊分段控件(标准手势检测器)
├── _src\                            # 参考源码(better-speedtest-main、MeowUI-main 解压)
├── tools\gradle-9.7.1\              # 本地 Gradle
└── *.png                            # 验证截图(gauge_new/segmented_control/dark_mode/dual_side_by_side/home_scroll_under_bar)
```

---

## 5. 核心机制详解

### 5.1 测速编排流(SpeedtestRunner)

```
开始
 ├─ IP 定位(cnspeed getIpLocSP → 失败/不全再 ipip;全败不阻塞)
 ├─ 大陆判定(31 省前缀匹配或运营商名;港澳台→海外路径)
 ├─ 源解析:AUTO → 大陆=cnspeed / 海外=CDN+Ookla
 ├─ cnspeed 路径:
 │   ├─ 指定节点 → DES 解密全量 serverlist → 关键字匹配
 │   ├─ 否则 → mobilematch(省/市/运营商)→ 同运营商优先 + 同城>同省排序
 │   ├─ 单源:逐节点(≤8)TCP ping → dovalid 换 key → 下载(失败换下一节点)→ 上传
 │   └─ 多源(multi>1):校验满 multi 个节点 → 各节点工厂进同一个池,吞吐求和
 └─ CDN/Ookla 路径:
     ├─ 指定源 → 单源;否则并发 2.5s 实测吞吐排序(rankCdn)
     ├─ 下载源 = 前 multi 名;上传源 = 下载冠军优先,其次 Ookla,再次任意支持 POST 的源
     ├─ ping 目标 = 下载冠军(或上传源)
     └─ 上传支持判定:2xx 或接受 ≥64KB 流(Ookla /upload 只在结束时响应)
→ SpeedtestResult → 历史持久化
```

事件全部写入 `StateFlow<TestUiState>`(状态/双方向进度/样本/日志/IP 信息),UI 单向数据流订阅;`stop()` 随时取消,协程作用域保证 worker 全部退出。

### 5.2 传输引擎(Engine.runDirectionPool)

- worker 工厂列表 → ConnPool 轮转分配连接;每 worker 独立作用域,取消时守护协程立即关 socket/OkHttp call 解除阻塞
- 采样循环(200ms):`rawBps` 供自适应爬坡(保持原版决策语义);**展示/统计用 1s 滚动窗口平滑**(6 个窗口点)
- 爬坡规则(与 Go 一致):增益 >30% → 连接数翻倍;>5% → +25%(下限 4);≤5% → 回滚到历史最优并停止
- 峰值与均值都**剔除预热段**(连接建立/缓冲灌入不算链路能力)
- 4 种 worker:
  - `cnspeedDownload/Upload`:原始 TCP + 手工 HTTP;上传限 `SO_SNDBUF=128KB` 使 write 计数贴近真实上链速率
  - `cdnDownload/Upload`:OkHttp;上传为 chunked 无限随机流,非 2xx 退出 worker

### 5.3 全球网测私有协议(Cnspeed)

| 环节 | 实现 |
|---|---|
| IMEI | `"TS" + md5(md5(hex(8随机字节))).substring(8,24).upper()` |
| token | `md5(md5("model=Android&imei={imei}") + md5("stime={t}&band=200&rand=12345555"))` |
| 就近节点 | GET `dlcv2.cnspeedtest.cn:8443/dataServer/mobilematch_many.php`(自签证书→信任所有) |
| 全量表 | GET `down.cnspeedtest.cn:8043/TaierAndroid/Config/serverlist_encrypt.json` → hex → **DES/ECB/PKCS5**("dw!@#$%^")→ JSON |
| 换 key | GET `http://{node}:{port}/speed/dovalid?...` → 正则提取 `1-uuid` |
| 下载 | `GET /speed/File(1G).dl?r=&key=`(Chrome UA) |
| 上传 | `POST /speed/doAnalsLoad.do`,multipart 头 + 无限随机 chunk,`Content-Length: 900000000` |

### 5.4 诚实统计(重要迭代)

上传方向 write() 只代表"进了发送缓冲"。新连接建立瞬间内核缓冲可吞几百 KB → 200ms 窗口内呈现几百 Mbps 假峰(实测假峰 369,真实 59)。三层修复:1s 平滑 + 峰值剔预热 + SO_SNDBUF 128KB。修复后:均值 59.3 / 峰值 63.3。

---

## 6. UI 设计说明

### 遵守的 MeowUI 约定(踩坑后固化)
1. **`MeowPreferenceScreen` 自动消费 Scaffold 内边距**(`LocalMeowScaffoldContentPadding`)——基于它的页面**绝不手动垫** padding,否则双重留白
2. 自建滚动容器(`verticalScroll`/`LazyColumn`):自己消费 PaddingValues + **祖先加 `Modifier.meowScaffoldScroll()`**(顶栏才折叠);且内边距要放在**滚动内容内部**(Spacer),内容才会从磨砂顶栏/悬浮底栏下滚过——垫在外面磨砂等于没开
3. `MeowSearchBar` 展开态:空查询不铺全量列表
4. `MeowButton`/非列表项控件不放进 `MeowPreferenceSection` 分组
5. Miuix 分支下 `MeowCard` 会占满可用宽——wrap 宽度的胶囊类控件用自绘容器

### 自绘组件
- **SpeedGauge**:270° 渐变弧(sweepGradient + canvas 旋转变换对齐起点)、24 格刻度(达到即点亮)、峰值标记、两端量程标签;无指针设计
- **DirectionLineChart**:单方向折线,纵轴按本方向峰值自适应
- **SlidingSegmentedControl**:滑动胶囊;`detectTapGestures`(按下位置选段)+ `detectHorizontalDragGestures`(拖动/跨段触觉/松手吸附/被滚动接管弹回);文字颜色按滑块重叠度实时 lerp

### 主题
全部取色经 `MeowTheme.colors` 设计令牌,不写死颜色;Material/Miuix 双风格与深浅模式均适配(深色模式已用截图亮度法验证:236.6→32.2)。

---

## 7. 构建与开发指南

### 首次环境(本机已就绪,换机参考)
1. JDK 21 到任意目录,设 `JAVA_HOME`
2. Gradle 9.7.1 解压到 `E:\APP\speedtest\tools\`(或改用 wrapper)
3. Android SDK:`D:\Android\Sdk`;接受许可(`licenses/android-sdk-license` 含三个历史哈希)——AGP 会自动下载缺失的 platform 37 / build-tools
4. `local.properties` 写 `sdk.dir=D\:\\Android\\Sdk`

### 构建
```bat
cd /d E:\APP\speedtest\BetterSpeedtest
set JAVA_HOME=D:\JAVA\zulu21.30.15-ca-jdk21.0.1-win_x64
E:\APP\speedtest\tools\gradle-9.7.1\bin\gradle.bat --no-daemon :app:assembleDebug
:: 产物 app\build\outputs\apk\debug\app-debug.apk(约 23MB)
:: release(当前为 debug 签名,可直接装;正式分发请换签名配置): :app:assembleRelease(约 15.5MB)
```
增量构建约 20-25s(冷构建首拉依赖约 5 分钟)。

### 安装运行(MuMu)
```bat
D:\platform-tools\adb.exe connect 127.0.0.1:16384
D:\platform-tools\adb.exe -s 127.0.0.1:16384 install -r app\build\outputs\apk\debug\app-debug.apk
D:\platform-tools\adb.exe -s 127.0.0.1:16384 shell am start -n dev.gaimoy.betterspeedtest/.MainActivity
```

### 常见开发操作
- **加 CDN 源**:`core/CdnSources.kt` 的 `entries` 加一行(注意部分 CDN 需指定 `ua` 防 403)
- **改默认参数**:`data/AppPreferences.kt` 的 `Settings` 键默认值 + `core/SpeedtestRunner.kt` 的 `TestOptions`
- **调表盘/胶囊外观**:`ui/Gauge.kt` / `ui/SegmentedControl.kt`(弧宽、弹簧、刻度数等都是局部常量)
- **升级 MeowUI**:改 `gradle/libs.versions.toml` 的 `meowui` 版本;注意 0.1.5 与源码仓库 API 差异(§10),升级后需核对外观页/标签字段
- **换正式签名**:`app/build.gradle.kts` release 块替换 `signingConfig`

---

## 8. 调试与验证方法

- **UI 结构断言**:`adb shell uiautomator dump` + 解析脚本 `E:\APP\speedtest\parse_ui.ps1`(输出全部文本+bounds),用于布局/遮挡/状态机验证(adb 偶发 exit 139 但 dump 文件已生成,单独 pull 即可)
- **主题切换验证**:截屏前后用 System.Drawing 计算平均亮度
- **真机手势模拟**:`input tap`(纯点击)/`input swipe x1 y1 x2 y2 时长`(漂移点按、拖动、滚动)
- **注意**:`adb exec-out screencap > file` 重定向在本环境不可靠,用 `shell screencap -p /sdcard/x.png` + `pull`
- 模拟器可能被误转横屏:`settings put system accelerometer_rotation 0 && settings put system user_rotation 0`

### 已验证场景清单(全部在 MuMu 实测通过)
- [x] 完整双测(cnspeed 路径):定位→就近节点→dovalid→下载→上传→历史落库
- [x] 仅上行模式 + 假峰修复后统计(59.3/63.3)
- [x] 顶栏折叠/回弹、内容从磨砂栏下滚过、底部无遮挡
- [x] 分段控件:纯点击/拖动切换、滚动穿过无误触
- [x] 深色模式切换(亮度法)、自建外观页渲染
- [x] 自动源隐藏节点行、换源清节点
- [x] 历史页记录与展示
- [x] **节点延迟预检**:顶栏「测延迟」→ 前 12 节点并发 TCP ping,卡片右侧延迟徽章(上海 23–39ms / 乌鲁木齐 67ms / 兰州 38ms)
- [x] **结果分享文本格式**:历史点按分享 → 剪贴板内容粘贴验证完整(时间/节点/位置/上下行/延迟/抖动/连接)
- [x] **分享剪贴板兜底**:MuMu 无分享目标时仍写入剪贴板 + Toast「结果已复制到剪贴板」
- 实测数据样本:临沂移动2 节点 ↓942~942(峰值 970~1019)/ ↑59.3(峰值 63.3)/ 延迟 6-8ms / 抖动 0.4-3.3ms

---

## 9. 与原版 Go 的差异对照

### 保留(逐行移植)
传输池与自适应爬坡、多源聚合、rankCdn 并发探测、TCP ping/抖动、预热剔除均值、cnspeed 全协议(token/IMEI/DES/dovalid/mobilematch/serverlist/原始 socket 下载上传)、Ookla config-sdk、CDN 源表、IP 定位与大陆判定、节点排序/关键字匹配、上传支持判定(2xx 或 ≥64KB)。

### 移除(Android 无意义/无权限)
| 原版模块 | 移除原因 |
|---|---|
| AT 串口查运营商(`/dev/smd*`+AT+COPS/CIMI) | UFI 随身 WiFi 设备专用 |
| MCC-MNC 运营商表 + `update` 子命令 | 依赖上一条,手机上有 TelephonyManager 也不需要 |
| 手动定位覆盖(manual)、aapq 定位源 | 后者依赖 uTLS Chrome 指纹(OkHttp 无需) |
| `/proc/net/dev` 网卡采样、WAN 网卡探测 | Android 无权限 → 应用层字节计数(原版的回退路径) |
| UFI-TOOLS 插件模式(NDJSON/进度条输出、install 二进制下载) | 面板插件专用 |

### 替换/新增
| 原版 | 本项目 |
|---|---|
| CLI 参数/flag | 设置页 + 主页快捷控件(SharedPreferences) |
| report.Status/Line 终端输出 | StateFlow 事件流 → Compose 界面 |
| Go net/http + uTLS | OkHttp(+自签信任;不用 uTLS) |
| 无界面 | MeowUI 双风格全套 UI、历史、外观设置 |

### 口径增强(超出原版)
1s 滚动平滑、峰值剔除预热、上传 SO_SNDBUF 限制、200ms 采样 + 动画补间(原版 1s)、方向即时切换防串显。

---

## 10. 已知限制与注意事项

1. **MeowUI 0.1.5 ≠ 源码仓库**:发布版缺 `MeowAppearance.floatingNavigationBarEnabled`、`MeowColorScheme.tertiaryContainer`、`MeowBlur`、部分 `MeowAppearanceLabels` 字段(悬浮底栏/背景模糊标签)。自建外观页已绕开;升级时需回归核对
2. **cnspeed 协议脆弱性**:端点/加密/token 算法随时可能随官方 App 更新失效;speedtest.net 在大陆网络可能不可达(自动路径会兜底到 CDN)
3. **明文流量**:Manifest 开了 `usesCleartextTraffic`(CDN 源含 http 下载地址)
4. **法律与流量**:仅测量自有/授权网络;测速高带宽高流量,移动数据慎用(设置页有提示)
5. release 当前用 debug 签名,仅方便直装;分发前必须换签名
6. MCC-MNC 等运营商识别已删,海外网络的"就近"仅靠 IP 定位

---

## 11. 任务进度进程

状态:✅ 完成 | 🔧 修复迭代 | 📋 进行中 | ⬜ 未开始

### 阶段一:调研与环境(2026-09-26 上午)
- ✅ 通读 better-speedtest 全部 Go 源码(engine/nodes/geo/config/selector/report)
- ✅ 通读 MeowUI 文档(getting-started/components)与关键源码(Scaffold/Preference 机制)
- ✅ 网络勘测:官方源慢(dl.google ~18KB/s),选定腾讯 Gradle 镜像(2.7MB/s)+ Aliyun/腾讯 Maven 镜像
- ✅ 环境搭建:JDK21 定位、Gradle 9.7.1 下载解压、SDK 许可写入(AGP 自动装 platform 37/build-tools 36)

### 阶段二:核心移植与首版(同日下午)
- ✅ 工程骨架:AGP 9.4.1 + Kotlin 2.4.20 + compileSdk 37 + MeowUI 0.1.5
- ✅ Engine 移植(传输池/爬坡/ping/统计/4 worker)
- ✅ 节点源移植:cnspeed(DES/token/dovalid)、Ookla、CDN 表
- ✅ IpLoc(cnspeed+ipip)+ 大陆判定 + 节点排序(按要求移除 AT/MCC-MNC/手动定位)
- ✅ SpeedtestRunner 编排 → StateFlow;HistoryStore;SharedPreferenceStore
- ✅ 首版 UI(主页/节点/设置/历史)→ 首次构建修 3 轮编译错 → debug/release 双 APK

### 阶段三:第一轮真机反馈(用户报告 6 项)
- 🔧 英文界面 → MeowAppearanceLabels 中文化(后随自建外观页移除)
- 🔧 顶底被挡 → 应用 Scaffold PaddingValues(此轮认识不完整,阶段五深化)
- 🔧 表盘/曲线刷新率 → 采样 500→200ms + 数值 180ms 补间
- 🔧 指针与进度弧不匹配 → 指针角度多减 90° 的 bug
- 🔧 无速率刻度 → 5 档数字刻度
- 🔧 上下行独立 + 模式切换 → 首版做成"双表盘",**用户纠正:表盘单个、曲线拆两张**(方向做反)
- 🔧 图表时间轴按实际样本跨度(修仅上行曲线缩半边)

### 阶段四:布局规范(用户:务必遵守 MeowUI)
- 🔧 历史/设置页双重留白 → MeowPreferenceScreen 自动消费内边距,不再手动垫
- 🔧 顶栏不折叠 → 自建滚动加 `meowScaffoldScroll()`
- 🔧 搜索框空查询铺全量列表 → 只在非空时给结果
- 🔧 空闲表盘 "Mbps" 重复 → 空闲标题置空
- 📌 固化约定入长期记忆(meowui-layout-conventions)

### 阶段五:MuMu 实机验证轮(adb 16384)
- ✅ 端到端首测:IP 定位(移动/山东/临沂)→ 临沂移动2 → ↓940/↑58~59/延迟 6ms → 历史落库
- 🔧 顶底模糊消失 + 底栏遮挡 → 内边距移入滚动内容内部(内容从磨砂栏下滚过)

### 阶段六:表盘与控件精修
- 🔧 表盘重绘:去指针,渐变弧 + 24 格点亮刻度 + 峰值标记 + 两端量程
- 🔧 双测中下载阶段完成后上传卡仍"待测" → 每方向实时均值(与最终同口径:剔预热)
- 🔧 模式分段对比弱/无动画/不可拖 → 自建 SlidingSegmentedControl(弹簧滑动/拖动/触觉/文字颜色随滑块插值)
- 🔧 外观页深浅模式同样问题 → 按 MeowUI 认可方式自建外观页(反编译 AAR 核对 0.1.5 真实 API)
- 🔧 Miuix 下 MeowCard 满宽挤压 → 指标胶囊自绘

### 阶段七:布局与统计精修
- 🔧 双测曲线卡一左一右;延迟/抖动改表盘下紧凑胶囊
- 🔧 上传开始半秒串下载速率 → 阶段入口立即切 direction(原等首个 200ms tick)
- 🔧 抹去顶栏 "better-speedtest" 副标题
- 🔧 自动源禁选节点 + 换源清节点
- 🔧 **上传假峰**(峰值 369 vs 均值 59)→ 定位为发送缓冲灌入 + 200ms 粒度放大 → 1s 平滑 + 峰值剔预热 + SO_SNDBUF 128KB;复测 59.3/63.3
- 🔧 "双测点不到" → 纯点击分支失灵(手写手势缺陷)→ 换 detectTapGestures + detectHorizontalDragGestures 标准组合,滚动接管弹回不误触

### 阶段八:文档
- ✅ 本文档(README 保留为简版入口)

### 阶段九:节点预检与结果分享(2026-09-26 续)
- ✅ 节点延迟预检:NodePickerScreen 顶栏「测延迟」,前 12 节点并发 TCP ping(`quickPing`,count=2/1.5s),卡片右侧延迟着色显示;换源/刷新清空预检结果;加载/预检 Job 取消防竞态
- ✅ 结果分享:`ResultShare.kt` 文本卡片格式化 + 系统分享面板;主页完成态「分享结果」按钮;历史卡片点按分享
- 🔧 稳健性:`resolvePingTarget` 统一 ping 目标解析(port 非法回落 80/443);`Engine` 原始 socket 端口 `toIntOrNull`;去掉多余 `!!`/`?.` 与未用 import
- ✅ `:app:assembleDebug` 编译通过(警告清零)

### 阶段十:单位/表盘/CDN/历史重构(2026-09-27)

**已完成(MuMu 已验)**
- ✅ `Model.SpeedtestResult` 增加 `dlBytes/ulBytes/network/mode`
- ✅ `Settings` 增加 `SpeedUnit` / `GaugeRangeMode` / `GaugeRangeFixed` / `GaugeDigits` / `GaugeShowScale` / `CustomCdnJson`
- ✅ 新建 `core/SpeedFormat.kt`(auto/Mbps/MB/s/Gbps 换算 + `fmtBytes` 流量格式化)
- ✅ 新建 `data/CdnStore.kt`(自定义 CDN JSON 读写 + 与内置 `CdnSources` 合并)
- ✅ `SpeedtestRunner` 三处 `finish()` 写入 `dlBytes/ulBytes/mode`,新增 `modeLabel()`
- ✅ `Gauge.gaugeMax` 支持 `fixed` 量程;表盘主数字改 `SpeedFormat.split`(数字+单位拆开)
- ✅ `Gauge.SpeedGauge()` 签名补 `unit/rangeMode/rangeFixedMbps/showScale`
- ✅ `SettingsScreen` 加「显示」分组 + 「测速源」自定义 CDN 入口
- ✅ 新建 `CdnManageScreen.kt`(增删改写 `CustomCdnJson`)
- ✅ 节点页并入自定义 CDN 源(`CdnStore.allNodes`,自定义排前)

### 阶段十一:UI/UX 全面整改(2026-09-27)

用户实测后集中反馈,一次性修复:

**可见性问题(文字隐形)**
- 🔧 表盘单位 `Mbps` 隐形 → `onSurfaceVariant` 对比度过低,改用 `onSurface`
- 🔧 模式分段未选中项隐形 → `lerp` 到浅色,改固定 `Color(0xFF333333)`
- 🔧 状态卡文字隐形 → `MeowCard(containerColor=primary)` 未生效 + `onPrimary` 配灰底 → 改 `Box(background(primary))`
- 🔧 曲线卡/延迟抖动/日志等 `onSurfaceVariant` 全部改 `onSurface`

**布局问题**
- 🔧 蓝色大状态卡 → 用户要求「这玩意删了」→ 整个删除,不留状态行
- 🔧 延迟/抖动胶囊被挪到顶部 → 用户抗议「谁让你挪的」→ 搬回表盘正下方
- 🔧 IP 信息乱写 → 收成 `StatChip("IP", ip.ip)`,与延迟/抖动并排
- 🔧 今日统计卡 → 用户质疑「这是啥玩意」→ 删除
- 🔧 历史双标题(「历史」+「历史记录」)→ 删自绘标题,只留 Scaffold 标题

**交互问题**
- 🔧 点击任意内容打开「跑满了吗」→ `Intent.createChooser` 被第三方 App 截走 → 全部改剪贴板 + Toast
- 🔧 分享按钮改名「复制结果」,不弹分享面板
- 🔧 开始测速按钮无主题色(Miuix 白色)→ 自绘 `PrimaryButton`(primary 底 + onPrimary 字)

**风格一致性**
- 🔧 对话框中英混合 → `cancelText = "取消"` / `confirmText = "清空"`,全部中文
- 🔧 清空按钮正文文字链接 → 挪到顶栏 `Icons.Rounded.Delete` 图标
- 🔧 单向历史显示「双向」→ `modeTag` 从 dl/ul 数据推断(双向/仅下载/仅上传)
- 🔧 `modeLabel` 「上传」→「仅上传」

**延迟抖动实时化**
- ✅ `TestUiState` 新增 `livePingMs / liveJitter`
- ✅ Runner 三处 `pingNode` 后写入 `_state.update`,UI 优先读实时值

**节点页优化**
- 🔧 抽出 `NodeCard` 统一卡片布局(标题 + 省市/运营商/IP 副标题)
- 🔧 选中项主题色高亮 + ✓ 标记
- 🔧 全部文字 `onSurfaceVariant` → `onSurface`

**架构改进**
- `ResultShare.kt` 删除 `Intent.createChooser`,只留 `copyResultToClipboard()`
- `MainScreen.kt` 整体重写(415 行精简、无状态行、布局归位)
- `HistoryScreen.kt` 整体重写(MeowScaffold + 顶栏图标 + LazyColumn)

### 阶段十二:MeowUI 完整化 + 节点/CDN 重构(2026-09-27)

用户指令:阅读文档后修正「未完全使用 MeowUI」「历史页顶栏」,重写节点选择与 CDN 自定义,重构逻辑。

**逻辑重构**
- ✅ `CustomCdnEntry` 从 data 层移入 `core/Model.kt`(core 不再反向依赖 data);`data/CdnStore` 瘦身为纯持久化(load/save)
- ✅ 新建 `core/NodeCatalog.kt` —— 节点取数/建池的**单一事实源**:`nodes(source, custom)`(节点页列表)与 `pool(src, mainland, hasPinned, custom, onNotice)`(引擎候选池,原 Runner.buildPool 迁入);Runner 与节点页共用,消除两处 when(source) 漂移风险
- ✅ `TestOptions` 增加 `customCdn`,MainActivity 组装时注入;测速池自动含自定义源

**历史页顶栏修复**
- 🔧 根因:历史页作为主 Scaffold 分页却**内嵌自己的 MeowScaffold** → 双层顶栏/双层内边距,内层还不接外层折叠
- 🔧 重写:去掉内嵌 Scaffold;列表 `LazyColumn + meowScaffoldScroll` + contentPadding(含主 Scaffold 内边距);清空图标上移主 Scaffold 顶栏(按分页条件显示),确认走 MeowAlertDialog

**MeowUI 完整化**
- 🔧 `CdnManageScreen`:裸 `androidx.compose.ui.window.Dialog` + 自拼卡片 → **MeowPreferencePage**(补上此前缺失的顶栏/返回)+ 编辑表单进 **MeowBottomSheet** + 删除确认 **MeowAlertDialog**(Warning);输入行按设计令牌自绘(MeowUI 无公开输入框)
- 🔧 表单抽成共享组件 `CdnSourceSheet`(节点页/管理页共用)

**节点选择页重写**
- ✅ 取数走 NodeCatalog;CDN 源时列表分「自定义源(N)/内置源(M)」两组,自定义项带删除
- ✅ 顶栏按源出图标:CDN 源多「添加自定义源」「管理自定义源」;通用「测延迟」「刷新」
- ✅ 本页可快捷添加(BottomSheet 表单)/删除(确认框)自定义源,改动即时持久化并刷新列表

**MuMu 实测**
- [x] 历史页单层顶栏、滚动折叠正常、清空图标在主顶栏
- [x] 节点页 CDN 分组(自定义 2/内置 16)、添加表单保存后列表即时刷新
- [x] 管理页顶栏返回/添加、卡片删除
- [x] CDN 源完整测速:18 源并发探测 → ↓942(峰值 950)/延迟 24ms,自定义源参与池

### 阶段十三:M3E 对比度修复 + 表单/历史/入口整改(2026-09-27)

**根因定位(像素采样)**:Material 3 Expressive 深色下,tonal primary 与背景同为 #151B16——分段滑块、表盘进度弧、主按钮、胶囊全部"消失";surfaceVariant 也与背景同色。
- ✅ 新建 `ui/Accent.kt`:`accentOf()`(亮度差 <0.32 时向主题前景极性逐步提亮)、`trackColorOf()`(surfaceVariant 失效时退 outline 45%)、`onAccentOf()`(强调色上取黑/白字)。应用到分段控件、表盘、主按钮、下载卡、节点卡、历史标签、胶囊。实测采样:滑块 #A8D0AF、轨道 #848784,对比恢复;亮色/Miuix 原样保留
- 🔧 分段未选中文字:硬编码 #333 改为按主题明暗取中性色

**表单重构(CdnSourceSheet)**
- 🔧 下划线输入行 → 圆角 14° 填充式输入框(trackColor 底、顶部标签、空值显示说明性提示);删除「区域」字段(自定义源不该让用户理解 region 概念);保存/取消等宽并排
- 🔧 `NodeCatalog.pool`:**自定义源不再参与区域筛选**(用户有意添加,始终进池;内置源仍按大陆/海外偏好)

**入口收敛**
- 🔧 节点选择页移除「添加/管理」顶栏图标与卡上删除——自定义源的增删改**只在设置页**;节点页仅展示/选择(分组保留)

**历史卡信息重组**
- ✅ 分层:头行(模式+网络标签+时间)→ 速率行(↓/↑ 大数)→ **指标四列(延迟/抖动/下载流量/上传流量)** → 尾行(节点 · 运营商 城市 · IP)
- ✅ `SpeedtestResult` 新增 `ip` 字段,`finish()` 自动从定位状态补填(旧记录默认空)

**按钮等高**
- 🔧 完成后「再次测速」(主)与「复制结果」(次)并排等高(同为 16dp 内边距/28° 圆角;次按钮中性底),不再一大一小

### 阶段十四:性能专项 + IP 即显/掩码(2026-09-27)

**掉帧根因(两处结构性)**
1. MainActivity 与 MainScreen 都在顶层 `collectAsState` 整个 TestUiState → **每 200ms tick 全页/全 NavHost 重组**;外加磨砂(RuntimeShader)默认开启,模拟器上滚动全程重绘模糊 → 全局卡顿
2. 修复:
   - MainActivity 历史刷新改为 `LaunchedEffect` 内直接收集流,不再把状态读到组合
   - **MainScreen 重构为叶子订阅架构**:`selectState(flow.map{切片}.distinctUntilChanged)` —— 表盘/曲线(高频)各自成为独立小组件,模式分段/测速源分组/按钮/日志(低频或静态)不再随 tick 重组
   - `@Immutable` 注解 DirectionState/TestUiState/Sample/Node/IpLocation/SpeedtestResult/CustomCdnEntry,跳过等值子树重组
   - **磨砂默认关闭**(设置键 background_blur→bg_blur,默认 false;外观页可重新开启)—— 模拟器/中低端机全局掉帧主因

**IP/地理位置即显与掩码**
- `SpeedtestRunner.ensureIpPrefetch()`:进主页 LaunchedEffect 立即预取,无需开测;已有/进行中则跳过
- IP 芯片默认掩码 `1.2.*.*`,**点击切换完整/掩码**(rememberSaveable 持久);位置芯片"运营商 城市";无网/未取到显示 `—`(信息位常驻不隐藏)

**真机复验完成(MuMu,2026-09-27)**
- ✅ IP 即显/掩码:进主页 6s 内预取完成,`120.225.*.*` + "移动 临沂";点击展开 `120.225.106.73`,再点收回
- ✅ 性能(gfxinfo 量化):空闲滚动 Janky 1/16(6.25%),p50=5ms/p90=13ms;**测速进行中滚动 234 帧 Janky 仅 1.28%**,p50=5ms/p90=7ms/p99=36ms —— 叶子订阅 + 磨砂默认关后,200ms tick 不再拖累全页
- ✅ 双风格对比度:M3E 深色(阶段十三,滑块 #A8D0AF/轨道 #848784 vs 背景 #151B16,自动提亮生效);Miuix 浅色(滑块 #EAEAEA/轨道 #484848 vs 背景 #F7F7F7,原生对比足够、accentOf 不干预)

### 阶段十五:清空按钮重设计 + 分享按钮(2026-09-27)

- 🔧 **历史页清空重设计**:顶栏删除图标(用户反馈不好看)→ 移到**列表末尾的居中破坏性按钮**(中性底 + error 色文字,确认仍走 MeowAlertDialog);对话框逻辑从 MainActivity 移回历史页内部
- ✅ **完成态三按钮等高并排**:再次测速(主,1.15 权重)/ 复制结果 / 分享
- 🔧 **分享绕开系统劫持**:MuMu 实测 ACTION_SEND(含 createChooser、EXTRA_EXCLUDE_COMPONENTS)均被「跑满了吗」直达截走 → 改为**自建分享目标 BottomSheet**(`queryShareTargets` + `setClassName` 显式唤起,完全可控);Manifest 补 `<queries>`(Android 11+ 包可见性,否则查询恒空)。实测列表正确列出目标 App

### 阶段十六:真机性能专项(2026-09-27)

用户指出:**模拟器 gfxinfo 数据无效(桌面硬件 ≠ 手机),真机仍严重卡顿**。针对真机的四项优化:

1. **传输线程手机化**:原默认 8/64(路由器/服务器场景)在手机上把 UI 线程 CPU 挤死——千兆传输本身是 CPU 密集(拷贝/TLS),64 连接只会更卡不会更快。默认改 6/16(下)、4/8(上),换 `_v2` 键让老安装也生效;需要时可在设置→引擎(高级)调回
2. **表盘逐帧开销**:渐变画刷 hoist 到 `remember(accent)`(原先每帧重建 Brush);**大数字改用采样快照值(5Hz)而非补间值**——补间动画会导致 60fps 的文本测量/排版,弱机上是最贵的逐帧操作;弧/指针仍平滑补间;刻度 24→12 格
3. **debug → release**:用户此前一直装 debug 包(无 R8/无优化 + Compose debug 组合跟踪,开销巨大)。已产出 release(debug 签名直装)并冒烟验证;**真机请装 release**
4. 既有:磨砂默认关、叶子订阅隔离 200ms tick、@Immutable

遗留:若真机仍卡,需要真机 adb 的 `dumpsys gfxinfo` 数据定位(设备连接后可测)。

### 阶段十七:对比色全无修复(真机反馈,2026-09-27)

用户真机反馈:卡顿已解决(阶段十六),但延迟/位置胶囊与各切换条"对比色全无"。

**根因**:动态取色(壁纸种子)主题下,M3E 的 surfaceVariant/primary 可能恰好与背景同色——此前的兜底(阈值 0.32、outline@45%)在这种组合下强度不够;且 outline 本身也可能不可见。

**修复(Accent.kt 重写为确定性对比)**
- `trackColorOf()`:不再依赖 surfaceVariant/outline,改为 **lerp(background, onSurface, 0.22)** ——轨道/胶囊/次要按钮的中性底在任何主题/壁纸下都有固定比例的对比
- `accentOf()`:亮度差阈值 0.32→**0.45**,提亮目标循环同步收紧;浅色主题压暗、深色提亮
- 分段未选中文字按**轨道**亮度取中性色(原按背景);表盘未达刻度同样用确定性混合(`neutralOnBg(0.38)`)

**四组合像素采样验证(全部通过)**

| 组合 | 滑块 | 轨道/胶囊 | 页面 |
|---|---|---|---|
| Miuix 浅 | #3482FF | #C4C4C4 | #F7F7F7 |
| Miuix 深 | #94BEFF | #444444 | #000000 |
| M3E 深 | #A8D0AF | #323732 | #151B16 |
| M3E 浅 | #386947 | #C6CAC4 | #E9F0E7 |

注:debug/release 双包已重新构建;模拟器已恢复用户的 Miuix 设置。

### 阶段十八:自定义源按「跑满了吗」模型重写(2026-09-28)

用户提供「跑满了吗」5.3.1 APK 与其导出的 speed_profiles 配置文件,按其模型重写自定义源:

**数据模型**
- `CustomCdnEntry(单 URL)` → `CustomSource(id, name, downloads[], uploads[], ua)` —— 一个源含**多条下载/上传 URL**(多连接下载轮转使用,天然多文件聚合);旧 `custom_cdn_json` 首次启动自动迁移
- 配置文件格式 `SourcesConfigFile`(format=BETTERSPEEDTEST_SOURCES),导出/导入整包 JSON

**导入兼容三种格式**(剪贴板):本应用导出 JSON / **「跑满了吗」.conf**(profiles→sources,downloadUrls/uploadUrls 直接映射)/ 纯 URL 文本(每行一条);同名源替换合并。导出 = 全部源 JSON 到剪贴板

**引擎**:NodeCatalog 把 CustomSource 按下载 URL 逐条展开为节点(名称 ·1/·2…),上传多于下载的地址也展开为仅上传节点;自定义节点 region 为空,节点页以此划分自定义/内置组

**UI**
- 管理页:profile 列表(N 条下载 · M 条上传)+ 顶栏「剪贴板导入」「新建」+ 导出按钮
- **编辑页独立成路由**(Route.CdnEdit(index),替代旧 BottomSheet):名称 / 下载地址动态列表(+ 再加一条 / ✕ 删行)/ 上传地址可选区 / UA;顶栏「从剪贴板批量追加 URL」「保存」
- 🔧 修一个崩溃:编辑页在 MeowPreferencePage 内容槽里又套 verticalScroll → 无限高度约束崩溃,去掉即可(内容槽自带滚动)

**MuMu 实测**:旧数据迁移 ✓、URL 列表导入 ✓、编辑页渲染/改名保存 ✓、管理页列表计数 ✓

### 阶段十九:移除内置 CDN + 节点选择页优化(2026-09-28)

**移除内置 CDN 源**
- ✅ 删除 `core/CdnSources.kt`(16 个内置源)——自定义源是唯一的 CDN 类来源,「CDN 下载」语义改为「**自定义源**」(枚举 label、主页源选项、节点页标题同步)
- ✅ `NodeCatalog.pool` 签名简化(去掉 mainland/hasPinned——区域筛选随内置源一并移除);自动模式海外路径 = 自定义源 + Ookla;自定义源为空时 CDN 池为空(引擎按"无可用测速源"报错,不崩溃)
- ✅ 设置页文案:「与内置源合并」→「多条下载/上传地址,支持从跑满了吗配置导入」

**节点选择页优化**
- 自定义源为空 → **空态引导卡**(说明 + 「去添加 / 导入」直达设置页),不再白屏
- 自动模式 → 信息卡说明策略(大陆→全球网测就近;其他→自定义源/Ookla 择优),顶栏图标隐藏
- 列表尾部统计脚注(共 N 个节点 · 已测 M 个延迟)
- 「恢复自动选择」只在已有选中节点时显示(之前始终占一行)
- 无匹配搜索词时显示「没有匹配的节点」(区别于加载失败)
- 顶栏:测延迟 / 刷新(自动模式无)

**MuMu 实测**:源选项「自定义源」✓、空态引导 ✓、Speedtest.net 源节点列表 ✓、完整测速(自动模式 ↓943/↑58.9)✓

### 阶段二十:预热缩短 + 曲线同口径剔除(2026-09-28)

用户反馈:峰值剔预热方向正确但 5s 太长;曲线也要同步砍掉开头"虚假"爬坡段。

- ✅ **预热默认 5s→2s**(换 key `warmup_sec_v2` 让老安装生效;设置选项改为 0/1/2/3/5)——移动端 TCP 爬坡 2s 已够,15s 测试的有效窗口从 10s 恢复到 13s
- ✅ **曲线与均值/峰值完全同口径**:采样 tick 里 `t ≤ warmupSec` 的点不进 samples,曲线时间轴从预热结束起算(t−warmup)——开头缓冲灌入的假爬坡不再出现在图上
- 修一个除零:全样本退化均值改用独立的 allCount(原 samples.size 在预热期恒为 0)
- MuMu 实测(仅上行):均值 59.7 / 峰值 63.2,峰值不再虚高

### 阶段二十一:回归 UI 库本色(2026-09-28)

用户提醒:**我们用的是 MeowUI 库,不要和它对着干**。此前为修 M3E 深色动态取色下"对比色全无"而搭的整层自动对比度系统(accentOf/onAccentOf/neutralOnBg + 阈值/饱和度/风格判断)在纯 Miuix 浅色(关 Monet)下反而制造了新问题——灰色 primary 被判为"对比不足"换成了各种兜底色,观感错乱。

**修复:整个自定义调色层删除,回归库的本色**
- `accentOf()`/`onAccentOf()`/`neutralOnBg()` 全部删除 → 直接用 `MeowTheme.colors.primary`/`onPrimary`/`onSurfaceVariant`
- `trackColorOf()` 简化为:优先 `surfaceVariant`(两种风格各自调好的轨道色),仅当它与背景亮度差 <0.02(动态取色极端同色)时做 10% 最小混合兜底
- 分段滑块 = `primary`,选中文字 = `onPrimary`,未选中文字 = `onSurfaceVariant`——全部是库的语义令牌
- Miuix 原生关 Monet 的灰 primary 就让它是灰(那是 Miuix 的设计语言);M3E 动态取色的 primary 由 MeowUI 的 Monet/materialKolor 引擎保证与 onPrimary 的对比

**纯 Miuix 浅色(关 Monet)采样**:滑块 #6C6C6C / 轨道 #DEDEDE / 页面 #F7F7F7 —— 滑块在轨道上清晰,且与页面区分明确,完全是 Miuix 原生观感

### 阶段二十二:分享回归系统 + 操作入口归位(2026-09-28)

- ✅ **分享改回系统 chooser**(`Intent.createChooser`),删除自建 ShareTargetSheet/目标查询/显式 setClass——用户明确要求默认调用系统接口
- ✅ **完成态只留「再次测速」一个大按钮**;分享/复制改为**顶栏图标**(仅测速分页 + 完成态时出现):📋 复制结果 / ↗ 分享结果
- ✅ **清空历史移回顶栏**(历史分页 + 有记录时出现 🗑 图标),不再放列表最底下(用户:每次都要使劲往下滑才能点到)

### 阶段二十三:彻底移除自定义源(2026-09-28)

用户指令:**彻底移除自定义源,后期重新规划制作**。

**删除的文件**
- `data/CdnStore.kt`(存储/迁移/导入导出)
- `ui/CdnManageScreen.kt`(管理列表页)
- `ui/CdnSourceEditorScreen.kt`(多 URL 编辑页)

**模型清理**
- `CustomSource`/`SourcesConfigFile`/`SOURCES_CONFIG_FORMAT` 从 core/Model.kt 删除
- `TestSource` 枚举只剩 `AUTO / CNSPEED / OOKLA`(CDN 分支移除)
- `TestOptions.customCdn` 参数删除

**逻辑简化**
- `NodeCatalog`:只剩 CNSPEED(全球网测)和 OOKLA(Speedtest.net)两个取数分支;`pool()` 只服务 Ookla 路径
- Runner 自动模式:大陆→全球网测,海外→Speedtest.net(不再有 CDN 池)
- 主页源选择器:只留 自动/全球网测/Speedtest.net 三项
- 设置页:删除「自定义 CDN 源」入口
- 节点页:删除自定义源空态引导/分组逻辑

**MuMu 实测**:源选择器 3 项 ✓、自动模式完整测速正常 ✓、设置页无 CDN 入口 ✓、release 构建通过 ✓

### 阶段二十四:历史卡布局美化(2026-09-28)

重设计历史卡布局(分层/留白/字号层次/彩色数字/行内指标):

- **头部**:模式徽章(primary 色小胶囊)+ 源徽章(次级胶囊)· 右侧日期时间(两行小字)
- **速率区**:↓/↑ 箭头 + **32sp 大数字**(下载主题色/上传青色)+ 峰值小字跟随缩进,左右分块
- **指标行**:延迟 · 抖动 · 流量(总)三格,**浅灰圆角条内嵌**,与速率区视觉分层
- **尾行**:节点 · 运营商 城市 · IP(单行小字,次级色)
- 卡间距 8→10dp,区块间距统一 14dp

### 阶段二十五:设置页二级化(2026-09-28)

设置主页精简为 3 组 6 行入口,细节拆到三个二级 `MeowPreferencePage`(带返回键):

| 分组 | 入口行 | 二级页内容 |
|---|---|---|
| 测速 | 测速参数 / 引擎(高级) | 时长+聚合 / 连接数(下载·上传)+预热 |
| 界面 | 显示设置 / 主题与外观 | 单位+量程+数显+刻度 / MeowAppearancePage |
| 关于 | 源码/MeowUI/版本 | — |

路由:`Route.TestParams / Route.Engine / Route.Display`(push 转场+返回键由 MeowNavHost 统一处理)。MuMu 实测:主页 6 行入口渲染 ✓、测速参数页(标题+返回+两项)✓

### 阶段二十六:显示设置条件化 + 冗余提示清理(2026-09-28)

用户反馈:"固定满量程在自动模式下不应该显示,写提示干什么第一次都没注意到"

- ✅ **「固定满量程」条件显示**:仅在量程模式=固定时出现(`rememberMeowPreferenceValue` 响应式切换),自动模式下整行隐藏
- ✅ 显示设置/引擎页全部去掉冗余 summary(测速单位/量程模式/固定满量程/数显/量程刻度/连接上限/预热时长)——设置项标题+当前值已经自说明,不需要解释文字

### 阶段二十七:弧端进度点(2026-09-28)

用户建议:中心圆点(指针轴心遗留物)改为**联动进度弧顶端的标记点**。

- ✅ 移除中心装饰圆点;新增**弧端进度点**:在进度弧最亮端(当前位置)画一个比弧稍大的双圈点(外圈轨道色描边 + 内圈强调色),随弧端实时滑动——当前速率在弧上的位置一目了然
- ✅ **峰值标记点删除**(用户反馈"怎么有两个点"):当前速率接近峰值时两几乎重叠、视觉混乱;峰值已由下方文字"峰值 XXX"展示,弧上只留一个进度点
- ✅ 设置 → 显示设置 → 表盘:**「弧端进度点」开关**(默认开)
- SpeedGauge 签名加 `showDot: Boolean`,主页经设置键透传

### 阶段二十八:上行表盘"先拉满再回落"修复(2026-09-28)

**根因**:预热段内 `peakMbps` 尚为 0(统计剔预热的设计),但表盘 `mbps` 已是实际速率(~50 Mbps)。量程用 peak 计算 → 得到最小量程 5 Mbps → 当前值 50/5 打满弧;预热结束后 peak 落地 → 量程跳到 100 → 进度点从满弧"跌"回半弧,视觉上就是"拉满再回落"。

**修复**:表盘量程改用 `max(peakMbps, mbps)` 计算——量程永远容纳当前显示值,量程只在值涨时台阶式放大,不再出现"值超出量程打满再跌回"。

### 阶段二十九：保留原设计的缺陷修复与性能优化（2026-10-06）

本轮先核对 MeowUI 官方组件与设计文档，再检查 UI、测速引擎和持久化。用户明确要求修复而非重新设计，因此保留原有页面顺序、按钮位置、胶囊切换、指标与历史卡片排版，以及导航转场。

- UI：统一表盘、曲线、历史和分享的速率单位；修复紧凑数显设置、窄屏 / 大字体溢出、Scaffold 内边距、失败状态及上传未确认提示。设置和模式写入失败会反馈，不伪装成功。
- 生命周期：测速、手选节点和历史由 ViewModel 持有，Activity 配置重建继续使用同一任务；分页保存滚动状态。高频采样只影响对应显示组件。
- 参数：测速使用本轮参数快照；模式的两个方向原子写入。缩短时长时同时收敛预热，旧超长预热配置显示收敛后的有效值。
- 传输：修复 worker 取消与 socket / Call 关闭；DNS 等待可取消且线程、队列有界；响应头与 chunked 正文按协议解析；均值使用有效字节 / 时间，自适应连接使用稳定采样窗口。
- 选点：去重节点、限制并发预检、缓存控制请求；仅上传不再进行下载吞吐探测。零正式流量失败可换点，已传输后不自动重跑；探测流量单独记录并计入总量。
- 上传：区分本机发送吞吐与服务端确认，未确认仍保留已测数据并明确状态。
- 存储：历史在 IO 线程串行读写，通过同目录临时文件、fsync 和原子替换保存；损坏文件保留并提供重试 / 确认清空，不显示原始 JSON 等内部内容。
- 测试：增加协议、计量、取消、缓存、回退、DNS、上传确认、历史、偏好和单位 / 分享回归测试。2026-10-06 首轮最终构建通过，11 个测试类共 59 项测试通过（0 失败 / 错误 / 跳过）；Debug、Release 与 release lint 检查通过。

2026-10-11 继续最终模拟器验证（Android 15，独立 `.qa` 包）：

- 对照修改前截图确认首页原有模式胶囊、72% 表盘、指标、曲线、按钮、源选择顺序保留；M3 / Miuix 深浅色检查，360dp 宽度与 1.5 倍字体滚动检查。
- 全球网测仅上传，5 秒、预热 1 秒、1 连接：完成约 45.9 MiB 本机发送量，平均约 57.1 Mbps；服务端未给出确认，首页与历史均明确标注未确认，不将该数值视为服务端接收吞吐。
- 开始 / 停止、节点列表加载（本次 605 个节点）、历史保存与重启后读取通过；历史复制动作可用。
- 旧时长 5 秒 / 预热 5 秒组合显示有效预热 3 秒；MB/s 下固定 1000 Mbps 满量程显示 125.00 MB/s。
- 发现浅色轨道同色与大字体历史指标挤压，按原设计进行局部修正：轨道仅更正实际页面底色判断；历史指标保留原条形底色与常规三项排列，宽度不足时整项换行。最终截图确认问题已修复。
- 历史损坏文件显示简短错误、不暴露原 JSON；确认清空后文件变为 `[]`，恢复空态。测试包已卸载；模拟器尺寸、密度、字体和旋转设置恢复后关闭，原应用数据未改动。

2026-10-11 最终构建：`:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` 全部成功，59 项测试仍为 0 失败 / 错误 / 跳过，release lint 通过。发布 APK 为 `app/build/outputs/apk/release/app-release.apk`，包名仍为 `dev.gaimoy.betterspeedtest`，仍使用项目原 debug 签名。

最终截图位于 `../_review/20261006/final/`：`home-original-layout.png`（布局对照）、`material-light-fixed.png`（底轨修复）、`history-large-fixed.png`（大字体指标修复）、`engine-timing.png`、`display-units.png`、`stopped.png`。该目录沿用本轮开始日期，最终复验实际日期为 10 月 11 日。

APK SHA-256：`456827935C65AB0082ACBCE2DA6D100DD0F22CD1DA6FFE975A075C671FB533E8`。

公网只进行了上述有限上传检查；Ookla 全流程、多运营商与真机广度未在本轮验证。模拟器系统分享目标接管了分享流程，未验证第三方接收结果，也未发送到任何联系人。

修改前源码快照位于 `../.snapshots/20261006-012926`。模拟器使用独立 `.qa` 包，与原应用数据隔离；中间重设计截图不作为最终交付依据。

### 阶段 30：独立打流功能（2026-10-11）

用户要求同时支持自定义 URL 持续打流和现有测速节点循环。保持原测速首页布局，在设置分组增加「打流」入口。

- 新增 `TrafficModels`、`TrafficBudget`、`TrafficTransport`、`TrafficRunner`：下载 / 上传 / 双向同时，每方向 1–16 个连接；共享应用流量预算和总速率限制；总时长、每轮时长、失败退避重试及取消收尾。
- 自定义 HTTP/HTTPS GET 和有限二进制 POST；复用全球网测的校验 / multipart 协议与 Ookla 节点 URL。自动或手动选择节点，轮间重新连接、刷新校验；不改动普通测速历史。
- 新增 `TrafficService`：dataSync 前台服务、实时通知、通知返回与停止、运行时限处理和有时限唤醒锁。服务与普通测速互斥，进程终止后不自动恢复打流。
- 页面使用 MeowUI 设置组件与原有主按钮，修复新页面 Miuix 深色按钮文本对比问题；高频统计只在统计卡片订阅。通知最多每秒更新一次。
- 修复通知停止命令改变 service startId 导致服务实例残留的问题；实测停止后服务退出、唤醒锁释放。通知导航请求改由 ViewModel 状态传递，已验证返回到当前打流页面。
- 72 项 JVM 测试全部通过，新增 13 项覆盖并发预算、取消退款、共享限速、输入校验、双向预算停止、上传确认、HTTP 错误、重试恢复、阻塞取消、循环时限、预启动停止以及两种节点协议。Debug / Release 构建及 release lintVital 通过。
- Android 15 隔离 `.qa` 包、本地 HTTP 服务验证：16 MiB 到限停止；全球网测协议 12 秒 / 每轮 5 秒运行 3 轮；通知返回及停止；4 Mbps 双向平均下载 2.00 / 上传 1.99 Mbps；Miuix 深色、360dp 窄屏和 1.5 倍字体；小数限速输入。
- 截图与 QA 脚本：`../_review/20261011-traffic/`；当前 APK SHA-256：`01342A94C0F89D08D4D157903533A2D3ACE925FCD74A960A17CA6551369426CD`。

范围边界：当前为 HTTP 打流，不包含 iperf3 / UDP；上传需要服务端接收接口。统计是应用正文读写量，不等同运营商计费或服务端实收量；页面单列上传成功响应次数。公网节点长期运行、多运营商与真机仍需验证。普通测速尚未迁入前台服务。

### 阶段 31：打流提升为主导航独立页面（2026-10-11）

- 按用户要求忽略误附图片，底部导航调整为「测速 / 打流 / 历史 / 设置」，移除设置中的打流入口，保留原测速首页。
- 打流页独立展示运行状态、下载 / 上传大字号速率卡片、各方向流量、累计时间与总量、开始 / 停止按钮，参数位于下方；复用主 Scaffold，避免嵌套顶栏。
- 同步历史清空操作的标签索引；使用页面状态保存器保留表单；通知导航直接选择打流标签，兼容旧的打流子路由恢复。
- 72 项回归测试、Debug / Release 构建通过。隔离模拟器检查四个标签、M3 浅色 / Miuix 深色、底部滚动间距、360dp 与 1.5 倍字体，以及通知同款 Intent 的页面跳转。截图：`../_review/20261011-traffic-tab/`。
- APK SHA-256：`A3B8E20DAE738520D47DFD6D9C0BDFCD3D7D500DBBEFFAD6B7CA2344DDC4C83F`。

### 阶段 32：持续下载 HTTP 403 处理（2026-10-11）

- 用户反馈全球网测持续下载返回 403。检查发现原测速引擎已有零流量失败换点，而打流只通过 TCP 与 key 校验便选定节点，下载拒绝后直接退出。
- 对 3 个公网候选节点进行有界诊断（下载响应最多读取 4 KiB）：多数校验未取得 key，一个节点在原测速请求头下仍返回下载 403。该结果说明节点拒绝并非仅由新传输请求差异造成；未验证用户同一节点，因为未提供节点名。
- 自动节点模式遇下载 403 后排除节点身份，换到其他候选；最多 8 个拒绝节点，保留同一预算和总时限，取消上一组连接后再切换。手动选点、自定义 URL 不擅自替换目标，上传 403 不走下载换点逻辑。
- 全球网测下载补齐原测速的 Accept / Connection 请求头，每次请求刷新时间参数。错误提示区分下载 / 上传，并对固定节点拒绝提示换点或恢复自动选点。
- 新增真实本地 HTTP 服务回归：403 后切换成功且总量不超预算、手选节点不换、连续拒绝有界；共 75 项测试通过，Debug / Release 与 lintVital 构建通过。
- APK SHA-256：`FC6BFB6619402DDCA8D528935709CFF358F742F8FC6FB44A9A808B99015549DD`。节点服务端持续拒绝本身仍可能发生，不能承诺所有节点恢复可用。

### 遗留
- ⬜ release 正式签名与混淆(minify 当前关闭)
- ✅ 核心与存储 JVM 回归测试已补充；更多第三方服务协议向量仍可扩展
- ⬜ 真机(非模拟器)广度验证、弱网场景
- ⬜ Ookla 上传在国内网络连通性验证
- ⬜ MeowUI 新版发布后回归（当前继续使用 0.1.5）
- ⬜ 通知栏前台服务(测速中防杀 + 实时速率)
- ⬜ 多语言(当前中文硬编码)

---

## 12. 路线图 / 待办

优先级从高到低:
1. **正式签名 + 混淆规则**(OkHttp/kotlinx-serialization keep 规则)出可分发 release
2. **扩展协议向量与真机验证**：现有 JVM 回归之外，补充运营商 / IPv6 / 弱网实测
3. ~~节点连通性预检~~ ✅ 已实现(顶栏「测延迟」+ 卡片延迟徽章)
4. ~~结果分享/导出~~ ✅ 首页复制与系统分享；历史卡片点按复制
5. **通知栏前台服务**:测速中防杀、显示实时速率
6. **多语言**(当前中文硬编码,可抽 string resources)
7. MeowUI 升级跟踪(双风格新特性)
8. 桌面小部件(今日速率)——远期

---

*本文档由开发过程实时沉淀;引用代码路径均相对 `E:\APP\speedtest\BetterSpeedtest\`。*
