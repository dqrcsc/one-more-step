# Changelog · 更新日志


## Unreleased — 可靠交付与设备会话边界

- 移除旧固定签名/公开 room ID 认证；重新配对撤销旧会话，RPC 和 ACK 绑定来源。
- 两端流校验、限额与送达确认；收件箱完整附件预检、原子索引、派生视图恢复；面板同源与文件边界限制。
- GUI observationId、绝对期限、串行执行、失败拒绝的确认流程和持久急停；审批选项与等待期限一致。
- 升级须同步主机/手机，历史无密钥设备须重新配对；不含端到端加密、BLE 背压和持久化续传。
- 测试与真机验收边界见 [实现状态](docs/implementation-status.md)。以下历史版本记录不代表开发分支所有承诺已验证。

All notable changes to **青简·咫尺 (OneMoreStep)** are documented in this file.

## [1.3.5] - 2026-10-26

### Security (一机一码与局域网严格 1 对 1 安全加固)
- **Mac 端 `/inbox` 与控制 API 严格锁死本机回环 (`127.0.0.1` / `::1`)**：
  - `/inbox`、`/dashboard`、`/api/inbox`、`/api/inbox/file` 与 `/api/inbox/action` 增加客户端来源 IP 校验（`PhoneSessionManager.isLoopbackAddress`），彻底阻断同一局域网其他电脑通过 HTTP 访问收件箱或借道 Mac 操控手机（返回 `403 Forbidden`）。
- **双向 HMAC-SHA256 / Ed25519 挑战-应答认证与指令拦截**：
  - 彻底移除开发期 `"trusted_xiaomi_sig"` 调试签名放行逻辑；
  - 首次配对 (`pair.request`) 成功后协商生成 256-bit 专属配对密钥 (`pairSecretHex`) 并立即销毁一次性配对凭证；
  - 每次重连执行双向挑战签名校验（手机验 Mac `macAuthHmacHex`，Mac 验手机 `phoneSignatureBase64Url`），手机端在完成对 Mac 的双向验签前拒绝执行任何传入的 RPC 控制指令或接收文件流。
- **手机端唯一 `macId` 严格锁定（杜绝局域网自动改绑）**：
  - 手机端 UDP 广播监听、局域网子网扫描 (`discoverMacOnLan`)、BLE 近场发现与 OTA 更新检查均严格核对 `macId == paired.macId`，彻底移除 `discoverMacOnLan(port, null)` 任意匹配回退逻辑，即使已绑定的 Mac 休眠或离线，手机也绝不会自动连上同一局域网的其他电脑。
- **PIN 码保护的配对解析与防暴力破解 (`/pair/resolve`)**：
  - `/install` 网页不再向局域网明文暴露 `ephemeralToken` 与 `oms://pair` 链接，需输入 Mac 终端显示的 6 位配对码方可解锁（或直接在手机 App 输入 6 位配对码完成绑定），连续输错 5 次自动销毁配对会话。

---

## [1.3.4] - 2026-10-26

### Added
- **第 13 个 MCP Tool `phone_device_control` 与 `oms phone` 远程操控套件**：
  - 支持从 Mac AI 或 Web 看板远程响铃寻找手机（`find_phone`，突破静音走闹钟声道播报 + 强震动）；
  - 支持远程 TTS 语音播报（`tts_speak` / `stop_tts`）、四声道音量调节与静音（`set_volume`）、震动反馈（`vibrate`）；
  - 支持一键远程抓取手机屏幕截图并自动归档至 Mac 收件箱（`take_screenshot` / `oms phone screenshot`）；
  - 支持在手机端直接打开网址（`open_url`）、按别名启动常用 App（`launch_app`：`wechat`、`lark`、`dingtalk`、`camera`、`settings` 等）及执行系统级导航与锁屏（`home`、`back`、`recents`、`lock_screen`、`notifications`）。
- **手机全维实时遥测 (`PhoneDeviceStatus`)**：
  - 新增电池温度（`temperatureC`）、电压（`voltageMv`）、充电接口类型、省电模式状态；
  - 新增屏幕亮灭（`screenInteractive`）、亮度、当前前台应用中文名称（`foregroundAppLabel`）与包名；
  - 新增 Wi-Fi SSID、信号强度（`wifiRssiDbm`）、协商速率、响铃模式、各声道音量百分比、可用存储与内存（RAM）余量。
- **手机端原生语音录制与 Mac 离线 SenseVoice 自动转写**：
  - 手机端底部输入栏新增 `🎙️语音` 实时录制（`VoiceRecorderHelper`，支持 `.m4a` 音频流与振幅动效）；
  - Mac 守护进程 `omsd` 收到音频附件后自动调用本地 `local-asr`（SenseVoiceSmall 离线模型）转写为文字并回填 `voiceTranscript`，同时支持 `oms inbox transcribe <itemId>` 手动重跑。
- **局域网智能 OTA 升级 (`/apk/version` & `oms ota`)**：
  - 支持版本号（`versionCode` / `versionName`）与安装包 SHA-256 校验对比，手机端与 Mac 端均可一键触发无线升级。
- **工程化与自动化脚手架**：
  - 新增 `bun run setup`（`scripts/setup.ts`），一键编译 macOS 原生 `oms-bt-bridge`、挂载全局 `oms` 命令并安装全局 Agent Skill。

### Fixed & Changed
- 移除 Android 客户端局域网自动发现中的本机测试网段硬编码，统一基于已配对 IP、UDP 广播探测、ARP 邻居单播信标与手机真实 Wi-Fi 子网（`/24` & `/23`）分批扫描。
- 修复工作区 TypeScript `tsc --noEmit` 类型检查配置，补充 GitHub Actions CI 自动验证流水线。

---

## [1.2.0] - 2026-10-23

### Added
- **Mac 实时收件箱与双向协同 Web 看板 (`/inbox`)**：
  - 内置深色主题 Web 控制台（`oms web`），支持 2 秒无感增量同步、大图 Lightbox 预览、一键唤起 macOS `Preview.app`、一键在 Finder 中定位附件，以及从网页反向推送 Markdown/剪贴板至小米手机。
- **Mac 即时消费自动联动 (`oms prefs`)**：
  - 支持收到手机文字或语音转写时自动写入 Mac 系统剪贴板（直接 `⌘V` 粘贴），收到手机截图/照片时自动弹窗预览。
- **收件箱检索与生命周期管理**：
  - 支持按关键词搜索（`oms inbox --q`）、单条详情查看（`show`）、已处理标记（`ack`）与批量清理（`clear-processed`）。

---

## [1.0.0] - 2026-10-21

### Added
- **跨端双向协议与安全配对 (`@oms/shared-protocol`)**：
  - 8 字节优先级多路复用二进制帧协议（`P0` 控制信令抢占 `P2` 大文件分块流，消除队头阻塞）、Ed25519 双向挑战签名鉴权、UUIDv7 幂等去重与 `/install` 零数据线无线安装配对台。
- **三级自适应传输与蓝牙空间在场引擎 (`@oms/mac-host`)**：
  - `Tier 1 (LAN_WS)` 物理网卡直绑高速通道、`Tier 2 (BT_RFCOMM)` macOS Swift `CoreBluetooth` 近场桥接、`Tier 3 (WAN)` 跨网通道无缝热切换；
  - 基于蓝牙 RSSI EMA 与滞回滤波的 `AT_DESK` / `AWAY` 在场感知智能通知路由。
- **小米 Android 原生客户端 (`com.oms.client`)**：
  - 免 IM 极速投递套件：三指截屏 3.5 秒悬浮胶囊（`ScreenshotPillController`）、系统分享 3 大置顶 Direct Share 入口（`#pkb_vault` / `#default_inbox` / `#ui_bug`）、控制中心快捷磁贴（1×1 透明焦点窗口突破 Android 10+ 剪贴板限制 + 语音闪念）；
  - 确定性系统能力执行器：小米原生时钟静默闹钟（`AlarmExecutor`）、系统日历防冲突读写（`CalendarExecutor`）、受权目录白名单按需拉取（`ScopedStorageExecutor`）、L1~L3 分级通知与闹钟声道强制 TTS（`NotificationExecutor`）、阻塞式远程交互审批卡片与通勤 TTS 随身听；
  - 无障碍 GUI 屏幕自动化引擎（`OmsAccessibilityService`）：紧凑语义树提取（`SemanticTreeExtractor`）、Android 11+ 静默截屏与 Set-of-Mark (`#ID`) 视觉合成、以及四大硬件级安全护栏（顶部物理急停胶囊、触屏即让路、`FLAG_SECURE` 金融黑名单、敏感动作停顿确认）；
  - 小米 HyperOS / MIUI 专属权限（AppOps `10021` 后台弹出界面、`10008` 锁屏显示）反射自检与一键跳转向导。
