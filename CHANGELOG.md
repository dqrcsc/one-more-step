# Changelog · 更新日志

## 1.4.0 - 2026-10-27

### 升级须知

- **Mac 主机与 Android 必须一起升级，并重新配对一次。** 旧配对不兼容 v2；不支持回退明文。
- 手机覆盖安装本次 APK，无需卸载或清除数据；Mac 更新到此版本后重启 `oms daemon restart`，在 Mac 本地运行 `oms pair`，把完整 `oms://pair?v=2…` 链接安全地复制到手机配对输入框，点击“验证并绑定”。链接含一次性秘密，请勿公开分享。
- 已取消“6 位码通过 HTTP 获取配对密钥”。升级完成并配对后，原收件数据和离线队列保留。

### 新功能

- v2 配对使用 256 位一次性秘密；临时 P-256 ECDH、HKDF-SHA256 和双向 AES-256-GCM 保护 RPC、文件、ACK、心跳及设备遥测。
- 长期配对密钥由双方派生，不通过网络响应下发；每连接独立密钥和序号，拒绝篡改、重放、乱序和跨连接帧，并限制握手期限、大小和单密钥使用量。

### 修复（包含此前本地 OTA 的 v1.3.8 / v1.3.9）

- 双向文件传输校验长度、分块范围和 SHA-256，收到接收端保存确认后才报告成功；修复收件箱索引恢复、存储路径与本地看板访问边界。
- GUI 操作使用最新观察、绝对期限、人工确认及持久急停；修复正常点击“放行”被浮层窗口事件误判为页面变化的问题，真正切换页面仍拒绝执行。
- 重新配对撤销旧连接和等待中的操作，RPC / ACK 绑定来源；保留 v1.3.7 的验证码同步功能。

### 验证

- Bun 全量测试 49 项通过（391 次断言），两个 TypeScript workspace 类型检查通过；Android JVM 测试 32 项通过。
- TypeScript、JCA 和独立 Python 实现的公开密码测试向量一致；模拟测试覆盖加密 RPC、双向文件及失败拒绝场景。
- LAN 真机通过：新配对、v2 遥测、空文件与跨块文件下发、手机文字回传、Mac 重启后自动重连、GUI 正常放行与换页拒绝。详情见 [OTA 验收](docs/ota-1.4.0.md)。

### 安装包与边界

- 附件 `OneMoreStep-v1.4.0.apk`：Android 11+，versionCode 14。沿用此前版本的 **Android Debug 签名**，是本次已安装验收的同一构建；尚未迁移到正式发布签名。
- APK SHA-256：`402a4ce633e8d35b98000af4203b96574ef7d9c7e26a9fc8ef1b7da579de3e58`。
- 签名证书 SHA-256：`806caa5647ae4119df3ba6b32cd4131d94147e66d4a4992b80f7e7ac94a1e4b7`。
- 组合加密协议未经第三方审计；BLE / WAN 尚未真机验收，BLE MTU / 背压及持久化断点续传仍未完成。HTTP 局域网 APK 分发、端点元数据和本地密钥存储不在业务帧加密保护范围内，详见 [安全传输 v2](docs/secure-transport-v2.md)。

## 1.3.9 — GUI 确认窗口事件过滤（本地 OTA）

- 针对真机正常放行被过期观察校验拦截的问题，仅用实际应用窗口 ID/包名识别窗口，过滤无关窗口的内容/状态事件，保留目标窗口变化的拒绝行为。
- 避免把非应用浮层 root 追加到观察窗口；观察失效错误带事件类型、窗口 ID 等诊断字段，不包含页面正文。
- 补充窗口事件归属回归测试。Android versionCode 13，沿用相同签名；实际修复效果以真机复测为准。



## 1.3.8 — 可靠交付与设备会话边界（本地 OTA）

- 合并 v1.3.7 的验证码同步功能；Android versionCode 升至 12，使用原签名覆盖安装，不修改数据库版本。
- 移除旧固定签名/公开 room ID 认证；重新配对撤销旧会话，RPC 和 ACK 绑定来源。
- 两端流校验、限额与送达确认；收件箱完整附件预检、原子索引、派生视图恢复；面板同源与文件边界限制。
- GUI observationId、绝对期限、串行执行、失败拒绝的确认流程和持久急停；审批选项与等待期限一致。
- 升级须同步主机/手机，历史无密钥设备须重新配对；不含端到端加密、BLE 背压和持久化续传。
- 测试与真机验收边界见 [实现状态](docs/implementation-status.md)。以下历史版本记录不代表开发分支所有承诺已验证。

All notable changes to **青简·咫尺 (OneMoreStep)** are documented in this file.

## [1.3.7] - 2026-10-26

### Added
- **手机验证码（短信 / 微信 / 飞书 / 邮箱）锁屏实时同步 Mac 桌面通知与剪贴板 (`#sms_otp`)**：
  - **Android 四通道穿透捕获引擎 (`com.oms.client.otp`)**：融合 `OmsNotificationListenerService`（全渠道通知栏监听，支持 `MessagingStyle`）、`OmsSmsBroadcastReceiver`（直接解析底层 `SMS_RECEIVED` PDU 穿透锁屏脱敏）、`SmsContentObserver`（监听 `content://sms` 写入）与 `OmsAccessibilityService`（无障碍通知事件兜底），内置 25 秒幂等去重；
  - **高精度抗干扰验证码提取 (`OtpExtractor` & `extractOtpFromText`)**：内置语义关键词门控与干扰数字掩码（自动过滤尾号、账号、金额、时间期限、手机号与品牌数字名），精准提取 4~8 位验证码与短信签名（如 `【阿里云】`、`【招商银行】`）；
  - **Mac 剪贴板与通知分离体验**：收到验证码时，Mac 剪贴板**仅写入提取出的纯验证码数字**（网页输入框直接 `⌘V` 粘贴），Mac 桌面通知高亮展示 `🔑 【签名】验证码: 839201` 与完整短信原文；
  - **小米专属权限自检与一键测试**：在 `XiaomiAdaptationHelper` 与手机端「小米权限」向导中新增「通知使用权」与「短信及小米通知类短信 (`OpCode 10018`)」自检跳转，并提供 `[⚡ 测试验证码]` 一键联调按钮；
  - **CLI 与 Web 看板升级**：新增 `oms otp [--copy]` 命令，`/inbox` 看板新增 `🔑 验证码` 筛选页与一键复制纯验证码按钮。

---

## [1.3.6] - 2026-10-26

### Fixed
- **无缝版本迁移与重连优化**：修复 `v1.3.4 -> v1.3.6` 升级后的鉴权密钥持久化（`savePairedMac` 保留已有 `pairSecretHex` 与公钥），移除后台高频 508-IP ARP 子网扫描风暴，仅在用户手动触发重连或首次绑定时执行子网扫描。

---

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
