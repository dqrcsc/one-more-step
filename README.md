# <img src="./docs/assets/icon-qingjian-zhichi.png" width="36" height="36" align="top" alt="QingJian ZhiChi Icon" /> 青简 · 咫尺 (OneMoreStep) — macOS 本地 AI ⇄ 小米 Android 全场景双向协同底座

[![Version: v1.6.1](https://img.shields.io/badge/Version-v1.6.1-10b981?style=flat-square)](./CHANGELOG.md)
[![Runtime: Bun >= 1.1](https://img.shields.io/badge/Runtime-Bun_%E2%89%A51.1-f472b6?style=flat-square&logo=bun&logoColor=white)](https://bun.sh)
[![macOS Host](https://img.shields.io/badge/macOS_Host-TypeScript_%2B_Swift_(CoreBluetooth_%2F_IOBluetooth)-0284c7?style=flat-square&logo=apple&logoColor=white)](./mac-host)
[![Xiaomi Android](https://img.shields.io/badge/Xiaomi_Android-Kotlin_%2B_Compose_(HyperOS_%2F_MIUI)-ff6900?style=flat-square&logo=xiaomi&logoColor=white)](./android-app)
[![Security: Transport v2](https://img.shields.io/badge/Security-ECDH_P--256_%2B_AES--256--GCM-4f46e5?style=flat-square)](./docs/secure-transport-v2.md)
[![MCP: 13 Tools & 3 Resources](https://img.shields.io/badge/MCP_Server-13_Tools_%26_3_Resources-0d9488?style=flat-square)](./mac-host/src/mcp/server.ts)
[![License: MIT](https://img.shields.io/badge/License-MIT-f59e0b?style=flat-square)](./LICENSE)

> **青简载思，咫尺通达 (`One More Step`) —— 把小米 Android 手机变成桌面 AI 智能体（Codex / Antigravity / Claude Code / OpenCode）的随身五官、移动控制台与无缝跨生态外设。**  
> **坚持「零第三方 IM 借道、零云端中转服务器、兼容手机常驻 VPN、一机一码双向认证加密」的纯本机直连设计**——无论是手机三指截屏 1 秒喂给电脑 AI、锁屏验证码毫秒级提取上屏 `⌘V`、AirPods 在 Mac 与小米手机间像苹果全家桶一样随音频焦点无感抢占切换、9 款 Microsoft Edge Neural TTS 真人自然音色远程寻机播报、还是在电脑浏览器以 12fps 实时镜像触控手机、远程九宫格解锁与双卡选卡拨号，皆在咫尺之间。

---

**v1.6.1 更新与实现边界声明**：
- **v1.6.1 全栈安全加固与性能优化**：修复 Web Dashboard 存储型 XSS 并启用 CSP；收紧 `~/.one_more_step` 目录 (`0700`) 与配置/Socket (`0600`) 权限及原子写入；`InboxManager` 改为 $O(1)$ 增量更新并启用日志 2 MB 轮转；Android 端坐标点击强制要求 `observationId` 并命中检测敏感词，拉黑 `com.oms.client` 本包与系统敏感权限页，全路径展示急停胶囊并支持手势打断急停与本地恢复；使用 Android Keystore AES-GCM 加密配对密钥；修复 `isConnectingLan` 重连死锁与 WebSocket 背压；切换为非调试版 Release APK 构建。见 [v1.6.1 更新说明](./docs/releases/v1.6.1.md)。
- **v1.6.0 新增能力与修复**：新增联系人姓名速查表（`oms contacts`）与直接按姓名拨号（`oms call <姓名>`）；支持拨号后自动检测接通（兼容小米 HyperOS 中文秒数计时器 `"7秒"` / `"SIM卡2 · 7秒"`）并执行免提 + Mac 扬声器 Neural TTS 代聊（`--mac-say`）、小米系统 `AI通话` 注入代聊（`--ai-call`）或手机本地 TTS 播报（`--say`）；修复小米 HyperOS 通话界面 `Switch` 嵌套子 `ImageButton` 的免提/挂断点击、免 `READ_PHONE_STATE` 权限通话状态判定、小米画报锁屏自动退出以及 `oms daemon restart` 后 BLE centralId 自动恢复。见 [v1.6.0 更新说明](./docs/releases/v1.6.0.md)。
- **v1.5.9 修复**：修正抖音暂停信号误判导致的自动切换漏触发，自动接管等待 Mac 释放确认，并补齐会话失效保护和媒体诊断。同一运行进程内两轮播放自动切换及用户听感确认通过；见 [v1.5.9 更新说明](./docs/releases/v1.5.9.md)。
- **v1.5.8 修复**：原生连接进程隔离、手机 A2DP 去重与有界等待、两端底层诊断。同一服务进程的合盖超时→出盒恢复已在本机通过，最终连接手机；长期稳定性仍待验证，见 [v1.5.8 更新说明](./docs/releases/v1.5.8.md)。
- **v1.5.7 候选修复**：拨号盘冷启动就绪轮询；统一 AirPods 两端期限、取消和目标音频路由确认。两端已部署，基础真机复测通过；扩展测试发现 AirPods 超时后的自动恢复仍不稳定，尚未发布 GitHub，见 [v1.5.7 更新说明](./docs/releases/v1.5.7.md)。
- **v1.5.6 修复**：统一解锁、拨号与镜像的急停/期限检查，移除危险坐标回退，修复镜像启停竞态、AirPods 失败误报和紧急语音自动停止，补齐 Neural TTS 独立安装。**本轮尚未完成真机验收**，详见 [v1.5.6 更新说明](./docs/releases/v1.5.6.md)。
- **v1.5.5 新增能力**：新增 **9 款 Microsoft Edge Neural TTS 真人自然语音播报与寻机引擎**（`xiaoyi` 甜美元气女声、`xiaoxiao` 温柔知性女声、`yunxia` 动漫元气少年音、`yunxi` 阳光男声、`yunjian` 沉稳男声、`xiaobei` 东北话、`xiaoni` 陕西话、`hsiaochen` 台湾腔甜美女声、`hiugaai` 粤语女声，支持 `--voice`、`--repeat <1..20>` 循环播报与 SHA-256 本地缓存 `~/.one_more_step/cache/tts/`）、**手机端 5 种随手即停交互**（按侧面音量/电源键、解锁屏幕、点按/左滑通知、通知停止按钮、App 顶部红条一键停声），并彻底消除手机播报时触发 Mac `Music.app` 自动弹窗的问题；包含 v1.5.3 **WebP 硬件加速投屏与自适应 10ms 极速流控**、v1.5.2 **AirPods 跨 Mac ⇄ 小米手机蓝牙无感自动切换**（`oms airpods`）与 **实时手机屏幕镜像工作台**（`oms mirror`）。完整变更历史见 [CHANGELOG.md](./CHANGELOG.md)。
- **v2 安全传输与配对要求**：自 v1.4.0 起业务帧统一运行于 **P-256 ECDH + HKDF-SHA256 + 双向 AES-256-GCM** 认证加密会话内（见 [安全传输 v2](./docs/secure-transport-v2.md)）。从 v1.4.x / v1.5.x / v1.6.0 升级至 v1.6.1 **无需重新配对**，直接运行 `oms ota` 或覆盖安装 [`OneMoreStep-v1.6.1.apk`](https://github.com/dqrcsc/one-more-step/releases/download/v1.6.1/OneMoreStep-v1.6.1.apk) 即可保留全部配对密钥、收件箱与配置；从 v1.3.x 及更早版本升级需同步升级双端并执行一次 `oms pair` 重新绑定。
- **工程实现边界**：当前已落地能力、升级兼容性及待专项真机验证项（如 WAN WebRTC DataChannel 尚未实现、当前 WAN 为可选 WebSocket 中继接入；Mac 蓝牙桥实际使用 `CoreBluetooth` BLE GATT + `IOBluetooth` 经典蓝牙音频切换；GUI 观察必须携带最新 `observationId` 等）详见 **[当前实现边界与升级验收 (`docs/implementation-status.md`)](./docs/implementation-status.md)**。

---

## 📑 目录导航

- [0. 项目简介与「青简 · 咫尺」命名释义](#0-项目简介与青简--咫尺命名释义)
- [1. 为什么需要 OneMoreStep？（核心设计原则与痛点对比）](#1-为什么需要-onemorestep核心设计原则与痛点对比)
- [2. 八大核心场景与实操案例详解](#2-八大核心场景与实操案例详解)
  - [场景一：📱 ➔ 💻 免 IM 极速投递、SenseVoice 离线语音转写与知识库联动](#场景一--免-im-极速投递sensevoice-离线语音转写与知识库联动)
  - [场景二：🔑 手机锁屏验证码（短信 / 微信 / 飞书 / 邮箱）毫秒级提取上屏](#场景二-手机锁屏验证码短信--微信--飞书--邮箱毫秒级提取上屏)
  - [场景三：🎧 AirPods 跨 Mac ⇄ 小米手机蓝牙无感自动流转与对端自动暂停](#场景三-airpods-跨-mac--小米手机蓝牙无感自动流转与对端自动暂停)
  - [场景四：🪞 实时低延迟手机屏幕镜像与电脑鼠标远程触控工作台 (`/mirror`)](#场景四-实时低延迟手机屏幕镜像与电脑鼠标远程触控工作台-mirror)
  - [场景五：🔓 远程唤醒、3×3 九宫格图案密码解锁与双卡原生电话拨打/挂断](#场景五-远程唤醒33-九宫格图案密码解锁与双卡原生电话拨打挂断)
  - [场景六：⏰ 主动式助理：原生闹钟、防冲突日历、分级强提醒/TTS 与远程审批](#场景六-主动式助理原生闹钟防冲突日历分级强提醒tts-与远程审批)
  - [场景七：🎛️ 手机全维实时遥测、远程寻机与局域网一键 OTA](#场景七️-手机全维实时遥测远程寻机与局域网一键-ota)
  - [场景八：🤖 无障碍 GUI 屏幕自动化引擎与四项应用层安全护栏](#场景八-无障碍-gui-屏幕自动化引擎与四项应用层安全护栏)
- [3. 系统架构与底层工程机制详解](#3-系统架构与底层工程机制详解)
- [4. 3 分钟快速上手指南（安装、v2 安全绑定与小米满血权限）](#4-3-分钟快速上手指南安装v2-安全绑定与小米满血权限)
- [5. AI Agent 接入指南（13 个 MCP Tools、3 个 Resources 与全局 Skill）](#5-ai-agent-接入指南13-个-mcp-tools3-个-resources-与全局-skill)
- [6. CLI 命令行工具 (`oms`) 全集速查表与实战示例](#6-cli-命令行工具-oms-全集速查表与实战示例)
- [7. 本地数据规范 (`~/.one_more_step`) 与 Web 控制台接口](#7-本地数据规范-one_more_step-与-web-控制台接口)
- [8. 源码构建、测试隔离与开发验证](#8-源码构建测试隔离与开发验证)
- [9. 设计文档库与验收记录导航](#9-设计文档库与验收记录导航)
- [10. 开源协议 (License)](#10-开源协议-license)

---

## 0. 项目简介与「青简 · 咫尺」命名释义

### 0.1 什么是「青简 · 咫尺 (OneMoreStep)」？

**青简 · 咫尺 (`OneMoreStep` / `oms`)** 是一套专为 **macOS + 小米 Android（HyperOS / MIUI）** 双持开发者与知识工作者打造的**跨端双向 AI 协同与硬件互操作底座**。

通过一套部署在 Mac 本地的轻量级守护进程（`omsd` + 原生 Swift 蓝牙桥接器 `oms-bt-bridge`）与小米手机端的原生 Kotlin 客户端（`OneMoreStep.apk`），它一次性打通了**六大跨端断层**：

1. **📥 手机 ➔ Mac 极速输入流**：三指截屏 3.5 秒悬浮胶囊秒传、系统分享三大置顶入口直达本地知识库、控制中心磁贴秒同步剪贴板、手机原生语音录制 + Mac 本机 **SenseVoiceSmall 离线自动转写**；
2. **🔑 锁屏验证码零触碰接力**：四通道穿透监听手机短信与 App 验证码，抗干扰提取 4~8 位纯数字验证码直接写入 Mac 剪贴板（电脑直接 `⌘V` 粘贴）并弹出桌面通知；
3. **🎧 AirPods 跨生态无感流转**：打破苹果与安卓蓝牙壁垒，根据 Mac 与小米手机谁正在播放音频/通话，自动抢占切换 AirPods 连接并自动暂停对端媒体；
4. **🪞 实时屏幕镜像与远程触控**：在 Mac 浏览器（`/mirror`）提供最高 `12fps` 的实时手机投屏与鼠标点击/滑动操作，无需拿起手机即可在电脑上查看和操作手机；
5. **🔓 确定性系统能力与远程操控**：电脑端一行命令或一句 AI 指令，即可远程唤醒手机并绘制 **3×3 九宫格图案解锁**、**双卡选卡拨打电话/挂断**、静默设定**小米原生闹钟**、防冲突读写**系统日历**、发送突破勿扰的 **L3 闹钟声道 TTS 语音播报**、发起**阻塞式远程代码审批**、或主动拉取手机相册最新照片；
6. **🤖 受安全护栏约束的 GUI 自动化**：通过 `OmsAccessibilityService` 提取 `5~10 KB` 紧凑语义控件树与 Set-of-Mark (`#ID`) 静默截屏，配合顶部物理急停胶囊、触屏即让路、金融密码页黑名单与 `--confirm` 停顿确认，替你操作无开放 API 的移动 App。

### 0.2 关于「青简 · 咫尺 (One More Step)」命名的解释说明

1. **「青简」：竹简杀青，治学案头**  
   古人炙竹渗水谓之「杀青」，编联成册谓之「韦编」。「青简」象征我们在 Mac 本地与个人知识库（Personal Knowledge Base）中长年累月沉淀的思考、笔记与代码资产。
2. **「咫尺」 (`One More Step`)：跨越桌面与掌心的最后一步**  
   桌面端的 AI Agent（Codex、Antigravity、Claude Code、OpenCode）拥有强大的推理与编程能力，却始终被困在电脑终端里；而手机作为我们全天候贴身的信息传感器与通讯终端，又因操作系统的生态藩篱与网络隔离而沦为数据孤岛。从手机截屏到电脑 AI 读图、从电脑长任务卡住到手机远程拍板，往往只差**最后一步 (`One More Step`)**。「青简·咫尺」正是为了消弭这最后一步的摩擦而生——**青简载思，咫尺通达**。

---

## 1. 为什么需要 OneMoreStep？（核心设计原则与痛点对比）

### 1.1 四大核心工程设计原则

1. **零第三方 IM 借道、零云端中转（100% Local & Private）**  
   所有图文、语音、剪贴板、验证码、屏幕帧与控制指令均在你的 Mac 与小米手机之间通过局域网或蓝牙点对点直传，**不借道微信/钉钉文件传输助手，不经过任何外部云服务器**，数据 100% 落盘于本机 `~/.one_more_step/`。
2. **兼容手机常驻 VPN 与复杂企业大子网（VPN-Friendly & Always-On）**  
   针对重度用户手机 24 小时挂着 Clash / Sing-box（占用 Android 唯一 `VpnService` 槽位）以及公司 `/23` 大子网锁屏休眠痛点，采用普通应用层 Socket + 物理 Wi-Fi 网卡绑定 + 前台 `WIFI_MODE_FULL_LOW_LATENCY` 低延迟锁 + 9 秒双向加密心跳 + **BLE 近场带外 IP 握手与离线回退**，彻底告别频繁断连。
3. **跨生态硬件体验平权（Apple ⇄ Xiaomi Ecosystem Bridge）**  
   用 MacBook 搭配小米旗舰手机的开发者，不必再羡慕 iPhone + Mac 的「通用剪贴板、验证码自动填充、iPhone 镜像、AirPods 自动切换」——OneMoreStep 在原生系统权限之上为你补齐全部跨生态协同体验。
4. **确定性原生 API 优先，安全护栏 GUI 兜底（Deterministic First, Guarded GUI Second）**  
   凡是 Android 系统提供公开或反射接口的能力（闹钟、日历、剪贴板、相册拉取、电话拨打、音量、TTS），100% 走毫秒级确定性原生执行器；仅在面对封闭第三方 App 时才调用带 `observationId` 状态校验与物理急停护栏的 GUI 引擎。

### 1.2 日常高频痛点对比（Before vs. After）

| 高频协同场景 | 传统割裂体验（Before） | 接入「青简 · 咫尺」后（After） |
| :--- | :--- | :--- |
| **📱 手机截屏/白板照喂给电脑 AI** | 手机截屏 ➔ 打开微信 ➔ 发文件传输助手 ➔ 电脑微信另存为 ➔ 复制路径发给 AI（8~9 步，耗时 1 分钟） | 手机三指截屏 ➔ 点右上角胶囊 **`[🚀 发给 Mac AI]`**（或直接在电脑对 AI 说*“拉取我手机刚截的图”*），1 秒落盘分析 |
| **🔑 电脑登录收手机验证码** | 低头找手机 ➔ 解锁屏幕 ➔ 打开短信/通知栏看 6 位数字 ➔ 抬头手动敲进电脑网页 | 手机锁屏收到短信瞬间，Mac 弹出通知且**纯数字验证码已自动进 Mac 剪贴板**，直接按 `⌘V` 粘贴 |
| **🎧 戴着 AirPods 双端切换** | 从 Mac 换到小米手机看视频，要手动打开手机蓝牙设置点连接，且电脑还在外放声音 | 哪台设备开始播放声音/来电，**AirPods 自动毫秒级切过去，同时自动暂停另一台设备的音乐/视频** |
| **🔓 手机放远处需看屏幕/打电话** | 必须起身走过去拿起手机、手动划开九宫格密码、打开拨号盘选卡拨打 | 在电脑浏览器打开 `/mirror` 直接鼠标点按，或运行 `oms call 10000 --sim 电信`，**手机自动亮屏、画九宫格解锁并选卡外呼** |
| **⏳ 电脑跑长耗时 Agent 任务** | 不敢离开工位去吃饭或躺沙发，怕 AI 卡在确认步骤或报错空转 | 离开工位（`AWAY`）自动将通知路由至手机；遇到高风险操作弹出**紫色 Diff 审批卡片**，手机点一下 `[✅ 批准继续]` |
| **💡 路上闪念与好文入知识库** | 微信收藏吃灰，录音懒得回听整理 | 任意 App 点系统分享 ➔ **`📚 存入个人知识库`**，或发一段原生语音，Mac 自动用 **SenseVoice 离线转写**并归入待处理队列 |

---

## 2. 八大核心场景与实操案例详解

### 场景一：📱 ➔ 💻 免 IM 极速投递、SenseVoice 离线语音转写与知识库联动

1. **三指截屏秒传悬浮胶囊 (`ScreenshotPillController`)**
   - 在小米手机任意界面三指下拉截屏，屏幕右上角立即浮现半透明胶囊 **`[🚀 发给 Mac AI] [🎙️ 附言]`**（若不操作，3.5 秒后自动消隐，绝不遮挡视线）。
   - 点击 **`[🚀 发给 Mac AI]`** 即刻将原图投递至 Mac 收件箱（默认自动在 Mac 上弹出预览窗口）；点击 **`[🎙️ 附言]`** 可边发截图边说一句“帮我把这个界面的间距调紧凑一点”。
2. **系统分享 3 大置顶入口 (`Direct Share`)**
   - 在任意 App（浏览器、公众号、知乎、小红书、相册、文件管理器）点击「系统分享」，首行置顶提供 3 个意图分流入口：
     - **`📚 存入个人知识库`**（携带 `#pkb_vault` 标签，进入 `~/.one_more_step/inbox/pkb_pending.jsonl` 供 `personal-knowledge-base` Skill 深加工）；
     - **`🚀 发给 Mac Inbox`**（携带 `#default_inbox` 标签，文字自动进 Mac 剪贴板，文件自动落盘）；
     - **`🐛 发给 AI 修 Bug`**（携带 `#ui_bug` 标签，进入 `ui_bug_pending.jsonl` 队列）。
3. **控制中心快捷磁贴 (`OmsQuickTileService`)**
   - 下拉小米控制中心**单击 `发给 Mac AI` 磁贴**：通过 `1×1` 像素透明焦点窗口（`ClipboardCatcherActivity`）突破 Android 10+ 后台剪贴板读取限制，1 秒将手机剪贴板同步至 Mac；
   - **长按该磁贴**：直接呼出「💡 我有一个想法」语音闪念框，说完即刻归入 `#voice_idea`。
4. **手机原生语音录制 + Mac SenseVoiceSmall 离线自动转写**
   - 在手机端 `OneMoreStep` 底部输入栏点击 **`🎙️语音`** 直接录制 `.m4a` 音频并发送；Mac 端 `omsd` 收到音频附件后，后台自动调用本机 `local-asr`（SenseVoiceSmall 离线模型）完成高精度中文/中英混杂转写，回填 `voiceTranscript` 并自动写入 Mac 系统剪贴板。

---

### 场景二：🔑 手机锁屏验证码（短信 / 微信 / 飞书 / 邮箱）毫秒级提取上屏

- **四通道穿透捕获引擎 (`com.oms.client.otp`)**：
  1. `OmsNotificationListenerService`：实时监听全系统通知栏（支持短信、微信、飞书、钉钉、邮箱等 `MessagingStyle` 消息）；
  2. `OmsSmsBroadcastReceiver`：直接解析底层 `SMS_RECEIVED` PDU 广播，即使小米手机开启了「锁屏隐藏通知内容」也能穿透提取；
  3. `SmsContentObserver`：监听 `content://sms` 数据库写入；
  4. `OmsAccessibilityService`：无障碍通知事件兜底（内置 25 秒内容哈希幂等去重）。
- **高精度抗干扰提取 (`OtpExtractor`)**：
  - 自动识别中英文验证码触发词，并通过正则掩码精准剔除**手机尾号、银行卡尾号、消费金额、有效分钟数、年份日期以及机票航班号（如 `ZH9949` 防诈提示）**等干扰数字，提取出纯净的 4~8 位验证码与机构签名（如 `【阿里云】`、`【招商银行】`）。
- **Mac 剪贴板与通知分离体验**：
  - 手机收到验证码后 `0.2 秒`内，Mac 右上角弹出桌面通知：`🔑 【签名】验证码: 839201`（附完整原文），同时 **Mac 系统剪贴板仅写入纯数字 `839201`**，你在电脑浏览器输入框直接按 `⌘V` 即可完成登录！

---

### 场景三：🎧 AirPods 跨 Mac ⇄ 小米手机蓝牙无感自动流转与对端自动暂停

无需购买两副耳机，也不用每次手动去点蓝牙设置，让 AirPods 在 macOS 与小米 Android 之间获得媲美甚至超越苹果原生的无感流转体验：

- **双端音频焦点实时感知**：
  - **Mac 端**：`oms-bt-bridge` 通过 macOS `CoreAudio` 实时监听默认音频输出设备运行状态（`kAudioDevicePropertyDeviceIsRunningSomewhere`）与 `IOBluetooth` 配对列表；
  - **手机端**：`AirPodsAutoSwitchManager` 实时监听 Android `AudioPlaybackConfiguration`（音乐/视频播放）与 `AudioManager.OnModeChangedListener`（电话振铃/通话/VoIP）。
- **智能抢占与对端自动静音暂停**：
  - **手机 ➔ Mac**：当 AirPods 连着手机且手机处于静音闲置状态，你在 Mac 上点开视频或音乐，Mac 自动通过 `IOBluetoothDevice.openConnection()` 抢回 AirPods 并设为系统默认输出，同时通知手机暂停残留媒体；
  - **Mac ➔ 手机**：当你在小米手机上点开 B 站/抖音/音乐或突然来电话，手机自动通过 `BluetoothA2dp.connect()` / `BluetoothHeadset.connect()` 将 AirPods 切回手机，同时 **Mac 自动发送系统级 `MRMediaRemoteSendCommand(kMRPause)` 暂停 Mac 上正在播放的音乐或视频**！
- **灵活控制**：支持在 Mac Web 看板（`/inbox`）、手机首页卡片或命令行 `oms airpods [status | mac | phone | auto on/off | pause on/off]` 随时手动切换或开关自动流转。

---

### 场景四：🪞 实时低延迟手机屏幕镜像与电脑鼠标远程触控工作台 (`/mirror`)

当手机放在书包里、充电器上或另一个房间时，直接在 Mac 浏览器打开实时镜像工作台：

```bash
oms mirror
# 或在浏览器直接访问 http://127.0.0.1:19527/mirror
```

- **二进制高速视频帧流 (`FrameType.SCREEN_FRAME = 0x07`)**：
  - 手机端基于 `AccessibilityService.takeScreenshot` 硬件 Bitmap 管道，在端侧直接缩放并编码为紧凑 JPEG 帧，封装 16 字节二进制帧头（`[codec][flags][width][height][seq][timestampMs]`），通过 `P1_STREAM_META` 优先级通道直推 Mac；
  - 内置在途帧背压控制（`maxInFlight = 2`）与无观看者 25 秒自动休眠机制；启动尚未完成时关闭最后一个页面也会安排停播。
- **网页端所见即所得鼠标触控**：
  - **单击画面** ➔ 映射为手机真實物理像素坐标 `tap(x, y)`；
  - **按住鼠标拖拽** ➔ 映射为手机平滑滑动手势 `swipe(x1, y1, x2, y2)`；
  - **右侧控制面板** ➔ 一键唤醒并绘制九宫格解锁、快速向手机当前焦点输入框发送中英文文本、一键触发 `桌面(Home)` / `返回(Back)` / `多任务(Recents)` / `锁屏(Lock)` / `音量调节` / `高清抓屏入收件箱`。

---

### 场景五：🔓 远程唤醒、3×3 九宫格图案密码解锁与双卡原生电话拨打/挂断

1. **远程点亮屏幕 + 自动绘制 3×3 九宫格图案解锁 (`oms unlock`)**
   - 九宫格点位按从左到右、从上到下编号为 `1 ~ 9`（第 1 行 `1,2,3`，第 2 行 `4,5,6`，第 3 行 `7,8,9`）。
   - 执行命令后，手机自动通过 `PowerManager.ACQUIRE_CAUSES_WAKEUP` 点亮屏幕 $\rightarrow$ 自动上滑呼出锁屏密码层（Bouncer） $\rightarrow$ 自动探测或按校准坐标生成单笔画连续贝塞尔轨迹 (`GestureDescription`) 一次性划开九宫格密码！
   - 支持 `--save` 将图案安全保存在 Mac 本地 `~/.one_more_step/config.json`（权限 `0600`），此后直接运行 `oms unlock` 即可免输密码一键解锁：
     ```bash
     # 首次绑定并保存你的九宫格图案（例如 1-2-3-6-9-8-7-4-5），之后直接运行 oms unlock
     oms phone unlock 123698745 --save
     oms unlock
     ```
2. **自动解锁 + 原生双卡电话拨打与挂断 (`oms call` & `oms hangup`)**
   - 想要拨打客服电话、测试语音线路或紧急联系某人时，无需拿起手机：
     ```bash
     # 若手机处于锁屏状态，会自动先唤醒并绘制九宫格解锁，再使用指定 SIM 卡外呼并开启免提
     oms call 10000 --sim 电信 --speaker
     oms call 13800138000 --sim 2
     oms hangup
     ```
   - 底层通过 `TelecomManager.callCapablePhoneAccounts` 自动匹配双卡槽位（支持传入 `1`、`2` 或运营商名称 `电信`、`联通`、`移动`），通过 `ACTION_CALL` 直接发起呼叫，通过 `TelecomManager.endCall()` 一键挂断。

---

### 场景六：⏰ 主动式助理：原生闹钟、防冲突日历、分级强提醒/Neural TTS 与远程审批

- **静默创建小米原生系统闹钟 (`oms alarm`)**：调用 `AlarmClock.ACTION_SET_ALARM` + `EXTRA_SKIP_UI=true` 配合小米后台弹出界面权限，在小米系统时钟 App 内静默建好闹钟，并附带 `AlarmManager.setAlarmClock()` 双保险。
- **系统日历防冲突查询与写入 (`oms calendar`)**：查询时自动展开周期性重复会议（`CalendarContract.Instances`），让 AI 先检查你下午是否有会，确认无冲突后再写入新日程并配置提前 15 分钟系统日历响铃。
- **在场感知分级通知与 9 款 Microsoft Edge Neural TTS 真人语音播报 (`oms notify` / `oms phone say`)**：
  - 当你在电脑前（`AT_DESK`）时，`normal` 通知自动转为 Mac 桌面轻提示 + 手机静默入流，避免桌上手机频繁震动打扰写代码；
  - 当遇到紧急情况（`urgent` + `--tts`）或调用 `oms phone say` 时，突破手机勿扰模式亮屏弹窗，并通过 `USAGE_ALARM`（闹钟声道）直接播放 **Microsoft Edge Neural TTS 真人自然语音**（内置 `xiaoyi` 甜美元气女声、`xiaoxiao` 温柔知性女声、`yunxia` 动漫元气少年音、`yunxi` 阳光男声、`yunjian` 沉稳男声、`xiaobei` 东北话、`xiaoni` 陕西话、`hsiaochen` 台湾腔甜美女声、`hiugaai` 粤语女声，支持 `--voice` 与 `--repeat <1..20>` 循环播报，离线时自动回退手机端自然语调 TTS）。
- **阻塞式远程交互审批 (`oms approve`)**：长耗时任务遇到关键决策点时，向手机推送含代码 Diff 的紫色审批卡片，终端阻塞挂起，直到你在手机上点击 `[批准继续]` 或 `[拒绝并中止]`。

---

### 场景七：🎛️ 手机全维实时遥测、真人语音远程寻机与局域网一键 OTA

- **20+ 项实时硬件与系统遥测 (`oms status --live`)**：一键获取手机电量、充电接口（`AC`/`USB`/`WIRELESS`）、电池温度（`°C`）、电压、省电模式、屏幕亮灭与锁屏状态、**当前前台 App 中文名称与包名**、Wi-Fi SSID/信号强度（`dBm`）/协商速率、四声道音量百分比、可用存储（`GB`）与内存（`MB`）、AirPods 连接状态及 11 项权限健康度。
- **真人语音远程寻机与 5 种随手即停交互 (`oms phone find`)**：手机不知道丢在房间哪个角落？运行 `oms phone find "出来吧，皮卡丘" --voice hsiaochen --repeat 10`，手机立即拉满闹钟声道音量，用指定的真人音色循环呼唤并持续震动；找到手机后，可通过**按侧面任意音量键/电源键、解锁手机屏幕、点按或左滑划掉通知横幅、点击通知上的 `[⏹ 我找到了 · 停止声音]` 按钮、或点击 App 顶部醒目红条**随手一键停声。还支持远程抓屏回传（`oms phone screenshot`）、调节音量、打开指定网址或直接拉起微信/飞书/钉钉/相机。
- **局域网智能 OTA 升级 (`oms ota`)**：重新编译或更新 APK 后，无需插线，运行 `oms ota`（或点击手机端顶部 `🔄 检查更新`），自动校验 `versionCode` 与 `SHA-256` 并拉起无线覆盖安装。

---

### 场景八：🤖 无障碍 GUI 屏幕自动化引擎与四项应用层安全护栏

针对钉钉填表、小程序数据提取、移动端 UI 走查等无公开 API 的场景：

1. **三步语义剪枝树 (`SemanticTreeExtractor`)**：自动剔除无意义容器节点、聚合卡片内碎片文本，为每个可交互控件赋予 `#1, #2` 短编号，单屏仅 `5~10 KB`。
2. **零弹窗静默截屏与 Set-of-Mark 合成 (`ScreenCapturer`)**：可选自动在截图上绘制 `#1, #2` 红框视觉角标，供多模态大模型精准对齐视觉与控件树。
3. **四项应用层安全护栏**：
   - **`observationId` 窗口状态强绑定**：每次 `oms gui observe` 生成唯一 `observationId`，一旦页面发生跳转或窗口改变，旧节点编号立即失效拒绝执行，防止误点；
   - **顶部物理急停胶囊 (`SafetyStopOverlay`)**：AI 操作期间手机顶部常驻橙色急停条，点按立即持久化熔断后续所有 GUI 动作；
   - **用户触碰即让路 & 敏感窗口黑名单**：检测到手指触屏或进入支付/密码框窗口立即中止；
   - **最后一步人工点按放行 (`--confirm`)**：在点击「提交/发送/删除」前，在手机屏幕弹出半透明确认卡片，必须由你本人点按 `[允许执行]` 才真正注入点击。

---

## 3. 系统架构与底层工程机制详解

### 3.1 整体架构拓扑图

```mermaid
flowchart TB
    subgraph MacHost["💻 macOS 宿主端 (TypeScript / Bun + Swift 原生桥接)"]
        Agents["本地 AI Agents<br/>(Codex / Antigravity / Claude Code / OpenCode)"]
        MCP["标准 MCP Server (`oms mcp`)<br/>13 Tools + 3 Resources"]
        CLI["全局命令行工具 (`oms`)<br/>配对 / 投屏 / AirPods / 解锁拨号 / 收发 / GUI"]
        WebStudio["本地回环 Web 工作台 (`127.0.0.1:19527`)<br/>`/inbox` 实时看板 · `/mirror` 12fps 触控投屏 · `/install` 无线安装"]
        Daemon["单例守护进程 (`omsd`)<br/>Unix Socket IPC · 在场路由 · SenseVoice 离线转写 · 审批挂起器"]
        Inbox["本地存储与密钥库 (`~/.one_more_step/`)<br/>`latest.json` · `pkb_pending.jsonl` · `connections.jsonl` · `keystore/`"]
        BtBridge["原生蓝牙与音频守护助手 (`oms-bt-bridge`)<br/>Swift CoreBluetooth GATT + IOBluetooth & CoreAudio (AirPods 流转)"]

        Agents <-->|stdio JSON-RPC| MCP
        Agents <-->|Bash / Global Skill| CLI
        MCP & CLI <-->|Unix Socket (`omsd.sock`)| Daemon
        Daemon <--> WebStudio
        Daemon <--> Inbox
        Daemon <-->|Unix Socket (`bt.sock`)| BtBridge
    end

    subgraph Transport["🔐 安全传输 v2 (P-256 ECDH + AES-256-GCM + 8字节 P0/P1/P2 多路复用帧)"]
        T1["Tier 1: LAN WebSocket<br/>(物理网卡直绑 · UDP/ARP 发现 · WifiLock 9s 双向保活)"]
        T2["Tier 2: BLE GATT 分片通道 + Classic BT<br/>(零网络近场信令 · 带外 IP 握手 · AirPods A2DP/HFP 抢占)"]
        T3["Tier 3: WAN WSS 中继接入<br/>(可选跨网扩展通道)"]
    end

    subgraph XiaomiPhone["📱 小米 Android 客户端 (Kotlin + Jetpack Compose, MinSDK 30)"]
        FgService["核心前台服务 (`OmsCoreForegroundService`)<br/>唯一 macId 强锁定 · 离线重试队列 · 离线连接诊断"]
        ShareSuite["免 IM 投递与验证码引擎<br/>截屏 3.5s 胶囊 · 3大置顶分享 · 透明焦点剪贴板 · 4通道验证码提取"]
        AudioAndCall["跨生态音频与通讯执行器<br/>AirPods 自动流转 (`AirPodsAutoSwitchManager`) · 双卡选卡拨号/挂断"]
        SysCap["确定性系统能力与全维遥测<br/>原生闹钟 · 防冲突日历 · 受权文件 · 20+项遥测 · L1~L3通知/闹钟流TTS"]
        GuiAgent["无障碍 GUI 与实时投屏引擎 (`OmsAccessibilityService`)<br/>3×3 九宫格轨迹解锁 · 12fps 屏幕帧流 · 语义树(#ID) · 急停护栏"]
        ComposeUI["Jetpack Compose 双层顶栏主界面<br/>双向时间流 · 远程审批卡片 · 通勤TTS随身听 · 小米满血权限向导"]

        FgService <--> ShareSuite & AudioAndCall & SysCap & GuiAgent & ComposeUI
    end

    Daemon <--> T1 & T3
    BtBridge <--> T2
    T1 & T2 & T3 <--> FgService
```

### 3.2 四大核心底层工程机制

#### 1) 8 字节 `P0 / P1 / P2` 三级优先级多路复用二进制帧协议 (`shared-protocol/src/framing.ts`)
所有通道底层统一采用紧凑的 8 字节 Big-Endian 帧头（`[0x4F 'O'][Version][FrameType][Flags][PayloadLength(uint32)]`），并在发送队列中实施严格的三级优先级调度，彻底消除大文件传输或投屏时的「队头阻塞（Head-of-Line Blocking）」：
- **`P0_URGENT_CONTROL`**：JSON-RPC 控制信令（`0x01`）、心跳 `PING/PONG`（`0x05/0x06`）、流确认 `STREAM_ACK`（`0x04`）——永远插队在最前方毫秒级发出；
- **`P1_STREAM_META`**：实时屏幕镜像帧 `SCREEN_FRAME`（`0x07`）与文件流起始元数据 `STREAM_START`（`0x02`）——带在途帧上限（`maxInFlight = 2`）自适应丢帧防积压；
- **`P2_BULK_DATA`**：64KB 切片的二进制文件块 `STREAM_CHUNK`（`0x03`，`[16B UUIDv7][4B chunkIndex][raw_bytes]`）——在后台空闲间隙平滑穿插传输，并在对端完成 `SHA-256` 落盘校验返回 `COMPLETED` ACK 后才确认送达。

#### 2) 安全传输 v2（P-256 ECDH + HKDF-SHA256 + 双向 AES-256-GCM）与唯一 `macId` 强锁定
- **零明文密钥下发**：首次配对使用 `oms pair` 在 Mac 本地生成 10 分钟有效、单次消费的 256 位 `ephemeralToken`，双方通过临时 `P-256 ECDH` 密钥交换与 `HKDF-SHA256` 独立派生长期配对密钥，网络中绝不传输明文密钥。
- **每连接独立前向安全会话**：每次重连均重新执行双向挑战与临时 ECDH，派生独立的 `macToPhoneKey` 与 `phoneToMacKey`，所有业务帧封装为 `SECURE_RECORD (0x7E)`（含严格递增 64 位序号防重放、防篡改、防跨连接注入）。
- **唯一 `macId` 强锁定与本机回环隔离**：手机一旦绑定你的 Mac，其 UDP 探测、子网扫描与 BLE 发现仅接受 `macId == paired.macId`，绝不误连局域网内其他电脑；Mac 端 `/inbox`、`/mirror` 与 `/api/*` 严格限制仅 `127.0.0.1` / `::1` 可访问。

#### 3) 企业大子网 `WifiLock` 保活与 BLE GATT 自动分片流控
- **解决小米锁屏断流**：`OmsCoreForegroundService` 持有 `WIFI_MODE_FULL_LOW_LATENCY` 锁，配合 9 秒应用层加密心跳与 10 秒 WebSocket Ping，防止小米 HyperOS 锁屏后 Wi-Fi 芯片进入 DTIM 深度休眠导致企业 `/23` 大子网 AP 清除 ARP 表项。
- **BLE GATT 分片写入队列**：针对蓝牙 MTU 限制（如 `secure.finish` 握手帧约 `1.1 KB` 超过 BLE 512 字节上限），两端实现了基于 `onCharacteristicWrite` 回调的流式分片发送与重组队列，并避免蓝牙扫描抢占正在握手的局域网连接。

#### 4) 离线可用的双端结构化连接诊断引擎 (`connectionDiagnostics`)
- 手机与 Mac 各维护最近 200 条固定大小连接事件环（Mac 保存在 `~/.one_more_step/logs/connections.jsonl`），精确区分「Wi-Fi 不可用」、「Mac 探测不可达」、「握手超时」与「安全校验失败」，手机端断网时可直接在首页点击 **`🩺 诊断 ➔ 复制诊断`** 保留排查现场。

#### v1.5.6 审查修复：远程动作边界

解锁与拨号在 Android 端共享急停、串行锁、原会话校验及最多 25 秒动作期限；镜像输入最多 5 秒，调用方更短的期限不会被延长。解锁失败不继续拨号；拨号须核验号码、SIM 与呼叫按钮，不能确认时返回失败。旧版手机不再使用固定坐标或剪贴板输入兼容回退，而是提示升级。图案/PIN 输入仅在可确认的系统锁屏窗口执行，不回显真实图案序列。

这些修改已通过离线回归与构建，本轮仍待真机验收；不同 OEM 的锁屏、通话按钮和双卡识别仍需真机验收。详细范围见 [审查修复验收记录](docs/review-fixes-2026-10-29.md)。

### 3.3 Monorepo 代码目录结构

```text
one_more_step/
├── package.json                  # Bun Workspace 根配置与快捷脚本 (v1.5.7 候选)
├── bunfig.toml                   # 测试隔离配置（自动加载预置守卫，阻断测试访问真实设备或生产目录）
├── OneMoreStep-v1.5.6.apk        # [已编译产物] 最新版小米 Android 客户端安装包 (与 OneMoreStep.apk 同步)
├── scripts/
│   └── setup.ts                  # 一键编译 Swift 蓝牙桥、链接全局 `oms` 命令与安装全局 Agent Skill
├── skills/
│   └── one-more-step/SKILL.md    # 面向 Codex / Antigravity / OpenCode 的全局 Skill 规范模板
├── shared-protocol/              # [@oms/shared-protocol] 跨端协议、帧编解码与 v2 加密会话原语
│   ├── src/
│   │   ├── types.ts              # JSON-RPC 2.0 契约、遥测模型、AirPods/投屏状态、意图标签与错误码
│   │   ├── framing.ts            # 8 字节 P0/P1/P2 多路复用帧编解码与 16 字节屏幕镜像帧头
│   │   ├── secure-session.ts     # 安全传输 v2 (P-256 ECDH + HKDF-SHA256 + AES-256-GCM 会话状态机)
│   │   └── crypto-pairing.ts     # Ed25519 密钥对、挑战签名、UUIDv7 与 v2 配对 URI 构造
│   └── tests/                    # 协议帧抢占、v2 跨语言密码向量与安全会话单元测试
├── mac-host/                     # [@oms/mac-host] macOS 守护进程、MCP Server、CLI、Web 工作台与蓝牙桥
│   ├── bluetooth-bridge/         # [Swift] CoreBluetooth BLE GATT + IOBluetooth/CoreAudio AirPods 控制器
│   ├── src/
│   │   ├── daemon/               # omsd 单例守护进程、Unix Socket IPC、SenseVoice 离线转写与 AirPods 仲裁
│   │   ├── transport/            # 会话管理、UDP/ARP 自动发现、蓝牙桥客户端、RSSI 滞回滤波在场引擎
│   │   ├── inbox/                # 本地收件箱管理 (InboxManager) 与 Web 工作台 (/inbox & /mirror & /install)
│   │   ├── mcp/server.ts         # 标准 stdio MCP Server (13 Tools & 3 Resources)
│   │   ├── cli/index.ts          # oms 命令行工具全集
│   │   └── plugins/dingtalk.ts   # 可选钉钉自定义机器人 HMAC-SHA256 抄送插件
│   └── tests/                    # 传输层、安全握手、测试隔离与 Daemon+MCP 端到端集成测试
├── android-app/                  # [com.oms.client] 小米 Android 原生客户端 (Kotlin + Jetpack Compose)
│   └── app/src/main/java/com/oms/client/
│       ├── transport/            # 8 字节帧协议、SecureChannel v2 加密层与三级传输客户端
│       ├── service/          # 常驻前台服务 (OmsCoreForegroundService, WifiLock 低延迟保活)
│       ├── share/            # 截屏胶囊、Direct Share、快捷磁贴、语音闪念、透明剪贴板捕获
│       ├── otp/              # 四通道验证码穿透监听 (NotificationListener + SMS PDU + ContentObserver)
│       ├── capability/       # AirPods 自动流转、双卡拨号/挂断、原生闹钟、日历、受权存储、分级通知/TTS
│       ├── agent/            # 无障碍服务、九宫格轨迹解锁、12fps 屏幕捕获、语义树剪枝、物理急停胶囊
│       ├── xiaomi/           # 小米 HyperOS / MIUI 专属 AppOps 权限反射自检与一键跳转向导
│       └── ui/               # Jetpack Compose 双层顶栏主页、双向时间流、诊断面板、审批卡片与随身听
└── docs/                         # 完整立项评估、7 大模块详细设计、安全传输 v2 与真机验收记录
```

---

## 4. 3 分钟快速上手指南（安装、v2 安全绑定与小米满血权限）

### 4.1 环境要求
- **macOS**：支持 Apple Silicon / Intel，已安装 [`Bun`](https://bun.sh/)（`>= 1.1.0`）与 Xcode Command Line Tools（用于自动编译 `oms-bt-bridge`）。
- **手机端**：运行 Android 11+ (`MinSDK 30`) 的小米 / Redmi 手机（HyperOS 或 MIUI）。

### 4.2 第一步：初始化 Mac 宿主并生成配对凭证

在项目根目录运行以下命令，一键编译原生蓝牙桥、挂载全局 `oms` 命令、安装全局 Skill 并启动守护进程：

```bash
bun install
bun run setup
oms pair
```

Neural TTS 由仓库内 `mac-host/scripts/neural_tts.py` 提供，不依赖 Codex 的其他 Skill。
`bun run setup` 会尝试创建 `~/.one_more_step/runtime/tts` 的独立 Python 虚拟环境并安装固定版本的
[`edge-tts`](https://pypi.org/project/edge-tts/7.3.1/)；需要 Python 3 与可用的 pip/venv。
安装失败不影响手机本地语音，可用 `bun run setup:tts` 重试。
自定义服务数据目录时以 `OMS_HOME=/path/to/home bun run setup:tts` 安装，或通过 daemon 环境变量
`OMS_TTS_PYTHON=/absolute/path/to/python3` 指定已安装 edge-tts 的解释器。
`oms phone voices` 返回 `neuralTtsRuntimeInstalled`（仅检查解释器是否存在）；实际合成失败时，通知/播报返回
`ttsEngine: android_local` 与 `ttsFallbackReason`，区分依赖缺失、合成失败和超时。
Neural TTS 是在线能力，会把待播文本发送到 Microsoft Edge 语音服务；手机本地 TTS 回退不通过此服务。

终端将输出无线安装页地址与 **v2 一次性加密配对 URI**：

```text
==============================================================
🔗 OneMoreStep · 小米手机无线安装与绑定凭证 (10分钟内有效)
==============================================================
📲 【无数据线 APK 下载页】(仅提供安装，不提供配对凭据):
   👉 http://192.168.x.x:19527/install

📬 【Mac 实时收件箱看板】(电脑浏览器打开):
   👉 http://127.0.0.1:19527/inbox

🔐 安全 v2：旧版 PIN/链接不可用。请通过自己控制的可信途径将下方完整 URI 输入手机。
🌐 本机局域网地址:   192.168.x.x : 19527
🔑 完整配对 URI:
   oms://pair?v=2&id=mac_...
==============================================================
```

- **安装 APK 到小米手机（二选一）**：
  - **方式 A（手机浏览器免线下载）**：确保手机与 Mac 在同一 Wi-Fi，用手机浏览器打开 `http://192.168.x.x:19527/install` 点击下载安装 `OneMoreStep.apk`；
  - **方式 B（推荐 · 通过 ADB 安装自动豁免受限设置）**：若手机已连接有线或无线 ADB，直接运行以下命令安装，**系统将自动豁免 Android 13/14/15 无障碍与通知使用权的「受限制的设置」解锁步骤**：
    ```bash
    adb install -r OneMoreStep-v1.5.6.apk
    ```

### 4.3 第二步：完成 v2 一机一码绑定与常驻 VPN 分流

1. 打开手机上的 **「青简·咫尺 (`OneMoreStep`)」**，点击顶栏 **`[🔐 绑定Mac]`** 展开绑定面板。
2. 将 Mac 终端输出的完整 `oms://pair?v=2&...` 粘贴至输入框，点击 **`[🔐 验证并绑定]`**。
3. 顶部状态栏变为 **`🟢 局域网直连`** 即完成 `ECDH P-256 + AES-256-GCM` 双向加密绑定。
4. **💡 常驻 VPN 用户必配**：打开手机上的 Clash Meta / Sing-box / NekoBox 等软件，进入 **「分应用代理（Per-App Proxy）」**，将 **`青简·咫尺 (OneMoreStep)`** 勾选为 **「绕过代理 / 直连」**。

### 4.4 第三步：一键开启小米 HyperOS / MIUI 满血权限

点击手机 App 顶栏的 **`[⚡ 小米权限]`** 按钮，展开权限自检向导并依次点击 **`[去开启]`**：

| 序号 | 权限项 | 作用说明 |
| :---: | :--- | :--- |
| **1** | **小米「后台弹出界面」&「锁屏显示」** (`OpCode 10021 / 10008`) | 确保 AI 能在后台静默创建小米原生闹钟、控制中心透明窗口秒读剪贴板、远程唤醒屏幕及弹出锁屏强提醒。 |
| **2** | **通知使用权 & 小米「通知类短信」** (`OpCode 10018`) | 用于锁屏实时捕获短信与微信/飞书/邮箱验证码，毫秒级同步至 Mac 剪贴板。 |
| **3** | **电池省电策略设为「无限制」+ 自启动** | 配合 `WifiLock` 防止锁屏后后台协同通道被小米系统休眠清理。 |
| **4** | **悬浮窗权限** | 用于三指截屏后浮现 3.5 秒「🚀 发给 Mac AI」胶囊，以及 AI 操控屏幕时的顶部橙色物理急停胶囊。 |
| **5** | **无障碍服务 (`OneMoreStep 屏幕协同助手`)** | 赋予 AI 提取语义控件树、12fps 实时屏幕镜像、3×3 九宫格自动轨迹解锁与 GUI 自动化手势能力。 |
| **6** | **附近设备（蓝牙）、电话、日历、相册与麦克风** | 支撑 BLE 近场连接、AirPods 跨生态自动切换、双卡选卡拨号、日历防冲突读写与原生语音闪念录制。 |

---

## 5. AI Agent 接入指南（13 个 MCP Tools、3 个 Resources 与全局 Skill）

### 5.1 全局 Skill 自动就位
运行 `bun run setup` 后，全局 Skill 已自动安装至 `~/.codex/skills/one-more-step/SKILL.md`，全局命令 `oms` 已链接至 `~/.local/bin/oms`。支持全局 Skill 发现的本地 Agent（**Antigravity、Codex、OpenCode** 等）无需额外配置即可自动调用全套 `oms` 能力。

### 5.2 挂载标准 MCP Server
在 **Antigravity、Codex、Claude Code、OpenCode** 的 MCP 配置文件中加入：

```json
{
  "mcpServers": {
    "one-more-step": {
      "command": "bun",
      "args": [
        "run",
        "/Users/dqrcsc/x_project/one_more_step/mac-host/src/mcp/server.ts"
      ]
    }
  }
}
```

### 5.3 13 个标准 MCP Tools 完整契约表

| 能力域 | Tool 名称 | 核心功能与支持参数说明 |
| :--- | :--- | :--- |
| **收件箱、验证码与文件流转** | `phone_inbox_latest` | 获取手机投递或自动同步到 Mac 的最新记录。支持按标签 `tag`（`all` \| `sms_otp` \| `default_inbox` \| `pkb_vault` \| `voice_idea` \| `ui_bug`）与未处理状态过滤。 |
| | `phone_media_recent` | 主动列出手机相册或截图目录中最近生成的 N 个媒体文件元数据（`screenshot` \| `camera` \| `all_images` \| `video`，支持 `sinceMinutesAgo`）。 |
| | `phone_file_pull` | 将手机受权目录（`screenshots` / `camera` / `download`）中的指定图片或文件拉取到 Mac 本地并返回绝对路径。 |
| | `phone_file_push` | 将 Mac 上的报告、Markdown 总结或文件推送到手机时间流，支持 `enableCommuteTts: true` 开启通勤随身听语音朗读。 |
| | `phone_clipboard_sync` | 双向读写小米手机剪贴板（`get_phone_clipboard` / `set_phone_clipboard`），支持 `includeRecentOtp: true` 附带最近验证码。 |
| **主动式助理与远程审批** | `phone_alarm_set` | 在小米手机原生系统时钟 App 中静默创建闹钟（支持 `hour`、`minute`、`label`、`daysOfWeek` 重复周期）。 |
| | `phone_calendar_manage` | 查询手机系统日历日程（`list_events`，自动展开重复会议防冲突），或写入（`add_event`）/ 删除（`delete_event`）日程。 |
| | `phone_notify` | 发送分级通知：`silent`（静默入流）、`normal`（常规通知）、`urgent`（突破勿扰 + 锁屏亮屏 + 闹钟声道强制 Neural TTS 真人语音播报，支持 `voice` 与 `repeat`）。 |
| | `phone_request_approval` | 长耗时任务遇到高风险操作时，向手机推送含代码 `diffOrContext` 的交互式审批卡片，阻塞等待用户点击决策后返回。 |
| **全维遥测与远程系统操控** | `phone_presence_status` | 查询是否在电脑旁（`AT_DESK` / `AWAY`）、传输通道、电量/温度、屏幕锁屏状态、前台 App 中文名、Wi-Fi 信号、AirPods 状态、存储/内存余量及权限健康度。 |
| | `phone_device_control` | **全能远程系统控制**：<br>• `unlock_screen`：唤醒屏幕并绘制 3×3 九宫格图案（`pattern`）或 `pin` 解锁手机（支持 `savePattern`）；<br>• `call_phone` / `dial` / `hangup_call`：自动解锁并按双卡槽位（`simSlot: 1\|2\|电信\|联通`）拨打或挂断电话（支持 `speaker: true` 免提）；<br>• `find_phone` / `tts_speak` / `tts_voices` / `stop_tts`：突破静音真人语音寻机或语音播报（内置 `xiaoyi`/`xiaoxiao`/`yunxia`/`yunxi`/`yunjian`/`xiaobei`/`xiaoni`/`hsiaochen`/`hiugaai` 9 款音色与 `repeat` 循环次数）；<br>• `set_volume` / `vibrate` / `take_screenshot`：四声道音量调节、震动、远程抓屏入收件箱；<br>• `open_url` / `launch_app` / `system_action`：打开网址、启动常用 App（`wechat`/`lark`/`dingtalk`）或执行系统导航键（`home`/`back`/`lock_screen`）。 |
| **GUI 屏幕自动化** | `phone_gui_observe` | 获取手机当前前台 App 的紧凑无障碍语义控件树（带 `#1, #2` 短编号与 `observationId`），可选附带静默截屏或 Set-of-Mark (`#ID`) 标注图。 |
| | `phone_gui_action` | 执行 GUI 自动化操作（`click`、`long_click`、`input_text`、`scroll_down`、`scroll_up`、`swipe`、`back`、`home`、`launch_app`、`open_url`），节点动作需携带 `observationId`，支持 `requireUserConfirm` 停顿确认。 |

### 5.4 3 个 MCP Resources 一览

| Resource URI | 名称 | 说明 |
| :--- | :--- | :--- |
| `oms://inbox/latest` | Latest Mobile Submission | 最新一条从小米手机投递到 Mac 的图文/验证码/闪念元数据与本地文件路径 |
| `oms://inbox/pkb_pending` | Pending PKB Submissions | 待归入个人知识库 (`pkb_vault` / `voice_idea`) 的手机未处理投递队列 |
| `oms://phone/status` | Xiaomi Phone Presence & Status | 手机在场状态 (`AT_DESK` / `AWAY`)、传输通道、连接诊断与全维遥测快照 |

---

## 6. CLI 命令行工具 (`oms`) 全集速查表与实战示例

### 6.1 核心命令速查表

| 命令分类 | 命令语法 | 功能说明 |
| :--- | :--- | :--- |
| **配对与工作台** | `oms pair [--json]` | 在 Mac 本地生成 10 分钟有效的安全 v2 配对 URI 并启动无线安装台 (`/install`) |
| | `oms web` | 在 Mac 默认浏览器打开实时收件箱与双向协同 Web 看板 (`http://127.0.0.1:19527/inbox`) |
| | `oms mirror [open\|start\|stop\|status]` | 打开 `12fps` 实时手机屏幕镜像与电脑鼠标远程触控工作台 (`http://127.0.0.1:19527/mirror`) |
| **AirPods 流转** | `oms airpods [status\|mac\|phone\|auto\|pause]` | 查看 AirPods 双端状态、一键抢占切换至 Mac 或手机、开关音频焦点自动流转与对端自动暂停 |
| **解锁与电话** | `oms unlock [pattern] [--save] [--bounds ...]` | 远程唤醒手机屏幕、上滑调出密码盘并绘制 3×3 九宫格图案（`1~9`）或 PIN 解锁手机 |
| | `oms call <number> [--sim 1\|2\|电信] [--speaker]` | 自动解锁手机并使用指定 SIM 卡槽发起原生电话呼叫（`oms dial` 仅打开拨号盘） |
| | `oms hangup` | 远程挂断手机当前正在振铃或通话中的电话 |
| **遥测与遥控** | `oms status [--live]` | 查看在场状态、传输通道、电池温度、前台应用、AirPods、权限健康度与连接诊断日志 |
| | `oms phone <find\|say\|voices\|stop-tts\|volume\|screenshot\|open\|launch\|lock>` | 远程真人语音找手机（`--voice` / `--repeat`）、Neural TTS 开口说话、查看 9 款音色列表、调音量、远程抓屏、打开网址/App、执行系统导航与锁屏 |
| **验证码与剪贴板**| `oms otp [--limit N] [--copy] [--ack]` | 查询手机锁屏实时同步的最新验证码（加 `--copy` 自动将纯数字写入 Mac 剪贴板） |
| | `oms clip [get [--otp] \| set <text> \| pull \| push]` | 双向读写手机剪贴板，或与 Mac 系统剪贴板（`pbcopy` / `pbpaste`）一键互推互拉 |
| **收件箱与媒体** | `oms inbox [--tag <tag>] [--unprocessed] [--q <kw>]` | 查询手机投递记录；支持 `show <id>`、`ack <id>`、`transcribe <id>`（SenseVoice 离线转写） |
| | `oms pull <latest-screenshot\|latest-camera\|path>` | 一键拉取手机最新截屏、最新相机照片或受权目录（`screenshots`/`camera`/`download`）文件 |
| | `oms push <file\|--text\|--stdin> [--title <t>] [--tts]` | 将 Mac 文件或 Markdown 推送至手机时间流（`--tts` 开启通勤随身听语音朗读） |
| **助理与审批** | `oms alarm <HH:MM> [--label <t>] [--days 1,2,3]` | 在小米原生系统时钟 App 中静默创建闹钟 |
| | `oms calendar [list [--days N] \| add ... \| delete <id>]` | 防冲突查询手机未来日历日程，或写入带提前响铃提醒的新日程 |
| | `oms notify <text> [--level silent\|normal\|urgent] [--tts] [--voice <v>] [--repeat <N>]` | 发送 L1~L3 分级通知或突破勿扰的闹钟声道强制 Neural TTS 真人语音播报 |
| | `oms approve --title <t> --summary <s> [--diff <d>]` | 向手机推送阻塞式远程审批卡片，挂起等待手机端点击决策后返回 |
| **GUI 自动化** | `oms gui observe [--screenshot] [--som]` | 提取当前屏幕紧凑无障碍语义树（`#1, #2`）及可选 Set-of-Mark 红框标注截图 |
| | `oms gui act <click\|input_text\|...> [#id] --observation <id>` | 按节点 `#ID` 或坐标执行无障碍自动化操作（敏感操作加 `--confirm` 触发手机端人工放行） |
| **维护与升级** | `oms ota` / `oms prefs` / `oms daemon <restart\|logs>` | 局域网一键推送 APK 升级、配置自动复制/预览/默认 TTS 音色（`--tts-voice`）偏好、管理后台 `omsd` 守护进程 |

### 6.2 常用命令行实战示例

```bash
# ──────────────── 1. 屏幕镜像投屏、AirPods 无感切换与远程解锁拨号 ────────────────
# 打开 12fps 实时手机屏幕镜像与鼠标触控工作台 (http://127.0.0.1:19527/mirror)
oms mirror

# 查看 AirPods 状态 / 手动切到 Mac 或手机 / 开启音频焦点自动切换与对端自动暂停
oms airpods status
oms airpods mac
oms airpods phone
oms airpods auto on
oms airpods pause on

# 远程点亮手机并绘制 3x3 九宫格图案解锁（首次加 --save 保存图案，后续直接运行 oms unlock）
oms unlock 123698745 --save
oms unlock

# 自动解锁手机并用电信卡拨打电话（开启免提扬声器）/ 挂断电话
oms call 10000 --sim 电信 --speaker
oms hangup

# ──────────────── 2. 验证码提取、收件箱、离线语音转写与相册拉取 ────────────────
# 查看手机最新同步的验证码并写入 Mac 剪贴板
oms otp --copy

# 一键拉取手机刚截的图或刚拍的照片到 Mac 本地
oms pull latest-screenshot
oms pull latest-camera

# 查看待归入个人知识库的手机投递 / 对语音消息执行 SenseVoice 离线转写
oms inbox --tag pkb_vault --unprocessed
oms inbox transcribe <itemId>
oms inbox ack <itemId>

# 将电脑上的 Markdown 报告推送到手机『🎧 通勤 TTS 随身听』
oms push ./docs/02-scenarios-and-value.md --title "全景场景分析" --tts

# ──────────────── 3. 远程控制、真人语音寻机、原生闹钟、防冲突日历与远程审批 ────────────────
# 真人语音寻找手机（突破静音走闹钟声道发声，支持 9 款 Neural TTS 音色与 --repeat 循环次数）
oms phone find "皮卡丘，快回家吃饭" --voice yunxia --repeat 8
oms phone find "出来吧，皮卡丘" --voice hsiaochen --repeat 10
oms phone voices
oms phone stop-tts

# 远程抓屏 / 启动微信
oms phone screenshot
oms phone launch wechat

# 静默定小米原生闹钟 / 检查未来 3 天日历冲突并写入新日程
oms alarm 08:30 --label "起床检查夜间构建结果"
oms calendar list --days 3
oms calendar add --title "OneMoreStep 架构评审" --duration 60 --remind 15

# 发起阻塞式远程交互审批（终端等待手机端点击按钮拍板）
oms approve --title "是否执行生产数据库迁移？" --summary "新增 2 张索引表，预计耗时 3 秒"

# ──────────────── 4. 无障碍 GUI 屏幕观察与受护栏保护的自动化 ────────────────
# 提取紧凑语义树与带 #1, #2 红框角标的 SoM 截图
oms gui observe --screenshot --som

# 携带最新 observationId 点击控件或在最后一步提交前弹出手机确认框 (--confirm)
oms gui act click "#3" --observation "<observationId>"
oms gui act click "#8" --observation "<observationId>" --confirm
```

---

## 7. 本地数据规范 (`~/.one_more_step`) 与 Web 控制台接口

### 7.1 本地运行目录结构 (`~/.one_more_step`)
`omsd` 将所有配置、加密密钥、结构化诊断日志与收件箱附件统一保存在 `~/.one_more_step/`（可通过环境变量 `OMS_HOME` 覆盖）：

```text
~/.one_more_step/
├── config.json                   # 端口、蓝牙开关、自动复制/预览/验证码开关、AirPods 策略、默认 TTS 音色、保存的解锁图案 (0600)
├── cache/
│   └── tts/                      # Microsoft Edge Neural TTS 合成音频 SHA-256 本地缓存 (*.mp3)
├── keystore/
│   ├── host_identity.json        # Mac 端长期身份密钥对 (0600)
│   └── trusted_devices.json      # 已绑定的小米手机设备 ID、派生配对密钥与信任记录 (0600)
├── run/
│   ├── omsd.pid                  # 守护进程 PID
│   ├── omsd.sock                 # 供 MCP Server 与 CLI 并发调用的本地 Unix Domain Socket
│   └── bt.sock                   # omsd 与原生 Swift 蓝牙/音频助手 (oms-bt-bridge) 的通信 Socket
├── logs/
│   ├── omsd.log                  # 守护进程运行日志
│   └── connections.jsonl         # 最近 200 条结构化连接诊断事件环 (UTC 时间戳、事件码、尝试编号)
└── inbox/
    ├── latest.json               # 最新一条手机投递记录的即时快照 (O(1) 读取)
    ├── items_index.jsonl         # 全量投递历史索引 (同目录临时文件原子替换)
    ├── pkb_pending.jsonl         # 待个人知识库 (personal-knowledge-base) 消化的暂存队列
    ├── ui_bug_pending.jsonl      # 待修复的移动端 UI Bug 队列
    ├── asr_cache/                # SenseVoice (local-asr) 离线语音转写结果缓存
    ├── pulled_cache/             # AI 主动从手机拉取的文件与静默截屏缓存
    └── YYYY-MM-DD/<itemId>/      # 按日期与 UUIDv7 归档的原始附件与 meta.json
```

### 7.2 本地 HTTP 控制台与局域网安装接口
`omsd` 内置 HTTP 服务（默认端口 `19527`），严格区分**局域网公开只读下载端点**与**仅限本机回环（`127.0.0.1` / `::1`）访问的管理端点**（内置 Host Header 与同源 `Origin` 校验，阻断 DNS Rebinding 与跨站伪造）：

| 路径 | 访问边界 | 功能说明 |
| :--- | :--- | :--- |
| `GET /install` | 局域网可读 | 无数据线 APK 无线下载页（仅提供安装包下载，**绝不暴露任何配对密钥或 PIN**） |
| `GET /apk/latest.apk` | 局域网可读 | 下载最新编译的 `OneMoreStep.apk` 安装包（供首次安装与手机端无线 OTA 使用） |
| `GET /apk/version` | 局域网可读 | 返回当前 APK 的 `versionCode`、`versionName`、`sha256` 与字节大小 |
| `GET /inbox` | **仅限本机 `127.0.0.1`** | 打开 Mac 实时收件箱、全维遥测、AirPods 切换与反向推送 Web 看板 |
| `GET /mirror` | **仅限本机 `127.0.0.1`** | 打开 `12fps` 实时手机屏幕镜像与电脑鼠标远程触控工作台 |
| `GET/POST /api/inbox/*` | **仅限本机 `127.0.0.1`** | 收件箱查询、附件规范路径安全读取、手机远程操控、AirPods 切换与偏好配置接口 |
| `GET/POST /api/mirror/*`| **仅限本机 `127.0.0.1`** | 实时屏幕帧二进制拉取（`/api/mirror/frame.jpg`）、镜像启停与鼠标点击/滑动/按键注入接口 |

---

## 8. 源码构建、测试隔离与开发验证

本项目配备完整的单元测试、跨语言加密向量测试与 Daemon ⇄ MCP 端到端集成测试。通过仓库根目录的 [`bunfig.toml`](./bunfig.toml)，所有测试默认在**严格隔离模式**下运行（自动使用独立临时 `OMS_HOME`、仅监听回环地址、禁用真实局域网广播与系统 `osascript` 弹窗，绝不污染本机真实 `~/.one_more_step` 数据或干扰已运行的 `omsd`）：

```bash
# 1. 运行全仓 TypeScript 严格类型检查
bun run typecheck

# 2. 运行全仓单元测试与端到端集成测试（shared-protocol + mac-host）
bun test

# 3. 重新编译 macOS 原生 Swift 蓝牙与 AirPods 音频桥接助手 (mac-host/bin/oms-bt-bridge)
bun run build:bt

# 4. 运行 Android 客户端 JVM 单元测试并编译打包 APK
cd android-app && ./gradlew :app:testDebugUnitTest assembleDebug
# 编译产物：android-app/app/build/outputs/apk/debug/app-debug.apk
```

---

## 9. 设计文档库与验收记录导航

完整的立项论证、22 个高价值场景 ROI 分析、7 大核心模块详细设计以及各版本安全/真机验收报告均存放在 [`docs/`](./docs/README.md) 目录下：

### 9.1 实现状态、安全协议与诊断规范（优先参考）
- 🛡️ **[当前实现边界与升级验收 (`docs/implementation-status.md`)](./docs/implementation-status.md)**：当前版本真实行为、升级兼容性要求与待验证边界说明。
- 🔐 **[安全传输 v2 规范 (`docs/secure-transport-v2.md`)](./docs/secure-transport-v2.md)**：一次性配对令牌、临时 P-256 ECDH、HKDF-SHA256 与双向 AES-256-GCM 会话设计。
- 🩺 **[离线连接诊断规范 (`docs/connection-diagnostics.md`)](./docs/connection-diagnostics.md)**：双端最近 200 条连接事件环、错误分类码与离线排查流程。
- 📝 **[版本更新日志 (`CHANGELOG.md`)](./CHANGELOG.md)**：`v1.0.0` 至 `v1.5.6` 各版本详细演进记录与 APK 校验信息。

### 9.2 立项分析与七大模块架构设计文档
1. **[01. 可行性分析报告](./docs/01-feasibility-analysis.md)**：Android + macOS 技术可行性、三级无感连接、免 IM 原生交互及小米 ROM 避坑指南。
2. **[02. 全景功能与高价值场景分析](./docs/02-scenarios-and-value.md)**：6 大核心价值维度、22 个具体协同场景及优先级矩阵评估。
3. **[03. 系统顶层架构概览](./docs/03-architecture-overview.md)**：Monorepo 结构、技术选型、核心 MCP 工具集与演进路线。
4. **[04. 模块一：设备配对、安全认证与统一通信协议](./docs/04-module-protocol-and-pairing.md)**：配对绑定、双向挑战鉴权、8 字节优先级多路复用帧协议。
5. **[05. 模块二：三级自适应传输层与蓝牙空间感知](./docs/05-module-transport-and-presence.md)**：多通道热切换与 `AT_DESK / AWAY` 蓝牙 RSSI 滞回滤波引擎。
6. **[06. 模块三：Android 免 IM 极速投递、内置时间流与知识库联动](./docs/06-module-android-share-and-stream.md)**：截屏秒传胶囊、Direct Share 置顶目标、1×1 透明焦点窗口剪贴板同步与通勤随身听。
7. **[07. 模块四：Android 确定性系统能力与小米 HyperOS 深度适配](./docs/07-module-android-system-capabilities.md)**：原生闹钟双保险、日历防冲突读写、受权目录白名单、L1~L3 分级通知/TTS 及小米专属权限向导。
8. **[08. 模块五：Android 无障碍 GUI 屏幕自动化引擎与安全护栏](./docs/08-module-android-gui-agent.md)**：语义树剪枝算法、Android 11+ 静默截屏与 Set-of-Mark 合成、拟人手势注入与四项应用层安全护栏。
9. **[09. 模块六：Mac 宿主守护进程 (`omsd`)、本地收件箱与知识库触发器](./docs/09-module-mac-daemon-and-inbox.md)**：单例 `omsd` + Unix Socket 多 Agent 并发架构、`~/.one_more_step/` 规范与在场智能路由。
10. **[10. 模块七：Mac AI Agent 接口层（13 个 MCP Tools、CLI `oms` 与全局 Skill）](./docs/10-module-mcp-cli-and-skill.md)**：13 个 MCP 工具的 JSON Schema 契约、`oms` CLI 设计与全局 Skill 协同准则。
11. **[11. 无数据线安装、配对与全功能体验指南](./docs/11-wireless-install-and-experience-guide.md)**：手把手完成无线安装、首次绑定、小米权限开启与核心场景实操演练。

---

## 10. 开源协议 (License)

本项目基于 [MIT License](./LICENSE) 开源 © 2026 [dqrcsc](https://github.com/dqrcsc)。
