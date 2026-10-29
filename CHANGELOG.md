# Changelog · 更新日志

## 1.5.8 - 2026-10-29

本版本合并此前未单独发布的 v1.5.7 拨号盘修复与 v1.5.8 AirPods 超时恢复修复。Mac 与 Android 需配套更新；已经安装 v1.5.8 build33 的手机无需再次安装。

### 修复与诊断

- **拨号盘就绪**：固定 520 ms 等待改为最多 3 秒的就绪轮询，核验拨号包及准确号码；等待期间持续检查锁屏、急停、会话和总期限。仅打开拨号盘模式不会进入呼叫步骤。
- **Mac AirPods 恢复**：每次连接使用独立原生短进程，结束、取消或超时后回收，避免失败客户端状态被后续请求复用。主桥接继续响应，整体期限 22 秒，成功前确认目标设备与默认音频输出。
- **进程退出保护**：连接子进程具备独立单调期限和父进程存活检查，即使主桥接退出前未处理取消，也会自行结束。
- **Android AirPods 恢复**：已接受或正在进行的 A2DP 连接不再重复提交；移除不必要的连接策略、设备级和非通话时 HFP 请求。手机等待最多 12 秒，Mac 为传输和响应留出余量。
- **切换护栏**：统一两端等待期限，确认真实释放和活动音频路由；拒绝重叠手动请求，绑定手机会话，明确指定目标时不回退其他耳机。Mac 按设备 UID 匹配目标音频输出，兼容蓝牙名与音频名不同。
- **底层诊断**：记录返回码、权限异常、profile 状态、耗时及连接进程启动/退出/取消。`oms airpods status` 可查看最近诊断，`~/.one_more_step/logs/airpods-operations.jsonl` 最多保留 200 条；不记录音频、配对密钥或密码。

### 安装与兼容性

- APK：`OneMoreStep-v1.5.8.apk`，Android 11+，包名 `com.oms.client`，versionName **1.5.8** / versionCode **33**；沿用原 Android Debug 签名，可覆盖安装。
- 已安装 v1.5.8 build33 的用户使用的就是本次 APK；早期诊断 build32 需要覆盖升级。v1.4.x/v1.5.x 配对与数据结构不变，无需卸载、清除数据或重新配对。
- Mac 更新代码后运行 `bun install --frozen-lockfile`、`bun run --cwd mac-host build:bt`，再运行 `oms daemon restart`。需要 Bun、Swift/Xcode Command Line Tools；无新增第三方依赖。
- APK SHA-256：`72ccacbf3edc827f3a1fffe3b68576e3b36d169764bc903420beb6e668fa452b`，另附 `SHA256SUMS.txt`。

### 已完成验证

- Mac/协议 **94/94** 测试（614 assertions），Android **74/74** 测试；TypeScript、Swift、正式 `build:bt`、Android APK 构建和签名检查通过。
- 原生进程回归覆盖挂起且忽略 SIGTERM 时的回收、只完成一次、新请求继续成功、独立期限和父进程突然退出。
- AirPods 4 本机真机：同一主服务与桥接进程经历合盖超时后，切到 Mac **2487 ms**、切回手机 **2581 ms**，最终确认手机活动音频设备；连接子进程已退出。合盖超时后约 28 秒内 6 次检查未发现迟到连接。
- 拨号盘首次打开（含解锁）**5119 ms**、重复打开 **869 ms**；两次均 `dialedOnly=true`、`callPlaced=false`。测试号码已清除，返回 App 并恢复原锁屏状态。
- 发布前双轴审查完成：进程退出边界已修复并通过测试，更新说明已补齐拨号盘范围。

### 仍需验收及边界

- 长时间反复切换、长时锁屏与网络变化后的稳定性，其他手机系统与耳机型号兼容性。
- 严格强制结束拨号器进程后的冷启动；实际呼叫/挂断、双卡、免提及通话音频。本轮仅测试拨号盘，没有拨出电话或播放声音。
- TTS/紧急语音和音质测试未在本轮执行。
- 本次是一轮针对性复现回归，不代表所有兼容性问题均已解决。系统已接受的蓝牙请求无法由应用保证撤销；应用在期限失效后停止后续重试和输出切换。原生返回码不能单独证明系统内部唯一根因。

## 1.5.7 - 候选，2026-10-29

**尚未发布 GitHub；Mac/手机已部署。基础真机验收通过，但扩展测试发现 AirPods 超时后的自动恢复连接不稳定，仍待修复。** 详见 [v1.5.7 候选说明](./docs/releases/v1.5.7.md)。

- 拨号盘冷启动由固定 520ms 等待改为最多 3 秒的就绪轮询，保留号码、锁屏、急停、会话及总期限校验。
- 统一 AirPods 原生连接、桥接、手机 RPC 和 CLI 的等待预算；超时、原生请求替代或宿主断开后停止后续步骤，不接受迟到成功。
- Android 移除阻塞 RFCOMM 兜底，直接等待可取消连接流程；两端只有确认目标连接及相应活动/默认音频输出才成功。
- 明确指定 MAC 时不回退其他耳机；实际确认释放，修复自动释放失败仍报成功；拒绝重叠手动切换，手机接管绑定原会话。
- 真机发现蓝牙名称与 CoreAudio 名称不一致，改为按目标地址匹配音频设备 UID，避免名称差异导致连接误判。
- 候选 APK versionCode 31；需同步更新 Mac 与 Android。更新说明附未完成真机验收清单。

## 1.5.6 - 2026-10-29

**验收状态：自动化检查及 APK 构建通过；本轮尚未进行真机验收。** 以下锁屏、通话、通知和耳机行为仍需按清单验证，不能将离线测试视为真实设备兼容性保证。

### 本次修复

- **急停与动作期限**：镜像输入接入 GUI 急停、串行锁与最多 5 秒期限；解锁/拨号共用最多 25 秒期限，在副作用前后和等待结束后复核授权。过期、撤权、急停或排队失效时停止后续操作。
- **解锁与拨号**：解锁失败不继续拨号，不自动重试凭据；删除拨号、免提、挂断及旧版兼容路径中的固定坐标回退。拨号前核验拨号包、号码、SIM 和按钮，挂断后确认通话结束，避免误触和成功误报。
- **凭据保护**：移除返回值/错误信息中的真实图案序列；PIN/图案输入要求可确认的系统锁屏窗口。
- **连接变化保护**：控制请求绑定原物理连接与路由代次，连接替换或切换后拒绝旧操作继续；补充超时、迟到/重复回复回归。
- **旧版镜像输入**：不再通过剪贴板或其他入口绕过受保护的输入路径；不支持时明确提示升级，不将复制成功当作输入成功。
- **镜像生命周期**：串行化启动/停止，丢弃过期结果；启动中关闭最后一个观看页面也能安排停止，避免残留采集。
- **AirPods 结果**：手机离线时不释放 Mac 连接；释放超时、手机接管失败明确报错。
- **紧急语音通知**：区分全屏自动展示与用户点击停止，修复自动展示误触发停止播报。
- **Neural TTS 交付**：使用仓库自带 Python 适配器和独立 venv，不再依赖外部 Codex Skill；固定 edge-tts 7.3.1，提供明确降级原因。

### 升级与兼容性

- 请同步更新 Mac 代码与 Android APK，完整护栏需要两端配合。
- APK：`OneMoreStep-v1.5.6.apk`，Android 11+，包名 `com.oms.client`，versionCode **30**，沿用 Android Debug 构建方式；已与 v1.5.5 公开 APK 比对，签名证书 SHA-256 一致，可覆盖升级。
- v1.4.x/v1.5.x 的配对协议与数据结构未更改；同签名覆盖安装无需清除数据或重新配对。更早的 v1.3.x 仍需按 v2 配对流程升级。
- Mac 更新依赖后，需要真人音色时在仓库运行 `bun run setup:tts`，再重启守护进程 `oms daemon restart`。安装只准备依赖；在线合成会向 Microsoft Edge TTS 服务提交待朗读文本，依赖缺失或合成失败时可回退手机本地语音。
- 无法确认 SIM/呼叫按钮时返回 `call_target_or_sim_not_verified`；无法确认系统锁屏窗口时返回 `ERR_KEYGUARD_SURFACE_UNAVAILABLE`。旧版镜像输入或不支持的解锁/拨号端点返回升级提示，可能需要更新两端或针对 OEM 做后续适配。
- RPC 超时仅表示 Mac 不再等待，不能撤回底层已发送的数据；已被系统接受的拨号或手势不保证能够撤销。

### 已完成的验证

- Bun：**83 pass / 0 fail**，575 assertions（真实设备发现关闭，使用回环及模拟对端）。
- Android JVM：**56 tests / 0 failures / 0 errors / 0 skipped**。
- Python TTS 离线适配测试：**3 pass**。
- TypeScript 两个 workspace 类型检查、冻结锁文件安装检查及 `git diff --check` 通过。
- Android `testDebugUnitTest assembleDebug --offline --no-daemon` 成功；APK manifest 版本及签名验证通过。
- TTS 隔离 venv 安装/导入已验证；未进行在线文本合成或实际播放。

### 仍需真机验收

1. **镜像和急停**：急停/恢复、排队输入、断线重连与页面切换时动作是否正确停止；快速开关镜像后采集及保亮是否退出。
2. **图案/PIN 解锁**：实际 OEM 锁屏窗口、键盘及已校准图案区域识别；解锁失败、急停或撤权后不继续输入/拨号。
3. **拨号/挂断与双卡**：真实拨号包、号码、SIM 标签、呼叫/挂断按钮、免提和通话状态确认。必须事先约定测试号码和参与人，不自动拨打真实联系人。
4. **紧急语音通知**：锁屏全屏自动展示后应继续播报；点击通知、停止按钮、侧键与解锁停止行为需验证。
5. **AirPods**：离线不释放 Mac、在线接管与接管失败提示，以及系统实际音频路由。
6. **Neural TTS**：新部署机器的在线合成、手机播放，以及断网/依赖缺失时的本地降级。

### APK 校验

- 大小：**22,721,753 bytes**。
- SHA-256：`f4905fdfffc76c76e9b18a8cd2e5c058e124bb5c5a9f90bc8328faf17f1dbab5`。
- Release 同时提供 `SHA256SUMS.txt` 和独立更新说明。

发布代码与附件不代表已在用户设备部署或完成上述真机验收。详细测试边界见 [审查修复验收记录](docs/review-fixes-2026-10-29.md)。

## 1.5.5 - 2026-10-28

### 手机端 5 种随手即停交互与 Mac `Music.app` 防误唤醒修复

- **手机端 5 种随手停止找手机 / 语音播报方式**：
  1. **侧面物理按键即停**：在 `NotificationExecutor.kt` 中注册系统音量与息屏广播监听（`android.media.VOLUME_CHANGED_ACTION` / `ACTION_SCREEN_OFF`），拿到手机后随手按一下侧面音量 `+`、音量 `-` 或电源键立即停止语音与震动。
  2. **解锁屏幕即停**：监听 `ACTION_USER_PRESENT`，指纹 / 面容 / 图案解锁进入桌面瞬间自动停声。
  3. **通知点按或左滑划掉即停**：紧急找手机通知绑定 `setContentIntent` 与 `setDeleteIntent`，点击通知横幅或左滑清除通知立即停止播报。
  4. **通知栏一键停止按钮**：新增 `OmsTtsStopReceiver`，在锁屏/顶部通知栏提供 **`[⏹ 我找到了 · 停止声音]`** 动作按钮。
  5. **App 顶部醒目红条**：在 `MainActivity` 顶栏新增实时联动的红色横幅 **`🔊 正在语音播报 / 寻找手机 · [⏹ 停止声音]`**。
- **彻底修复手机说话时 Mac 自动弹出 Apple Music (`Music.app`)**：
  1. 在 `mac-host/bluetooth-bridge/main.swift` 的 `pauseMacMediaIfPlaying()` 中移除硬件媒体键 `NX_KEYTYPE_PLAY` 模拟，并增加 `isDeviceRunning(defId)` 与 `MRMediaRemoteGetNowPlayingApplicationIsPlaying == true` 双重守卫，杜绝向空闲的 macOS `rcd` 守护进程发送媒体键而拉起 `Music.app`。
  2. 在 `AirPodsAutoSwitchManager.kt` 与 `oms-daemon.ts` 中豁免手机 TTS / 寻找手机播报期间的 `airpods.claimByPhone` 抢占请求。

### APK

- `OneMoreStep-v1.5.5.apk`：Android 11+，versionCode 29，22,818,517 bytes（沿用 Android Debug 签名，从 v1.4.x / v1.5.x 覆盖安装无需重新配对）。
- SHA-256：`1b08b4243b686b95c176b251458b24089f6997e492c779f60b0a08d1df3bad28`。

## 1.5.4 - 2026-10-28

### Microsoft Edge Neural TTS 9 款真人自然音色、循环寻机播报与 SHA-256 本地缓存

- **9 款 Microsoft Edge Neural TTS 真人自然语音集成**：在 Mac 守护进程 `omsd` 中内置免 Key 的神经网络语音合成引擎，提供 9 款高自然度音色（`xiaoyi` 晓伊·甜美元气女声[默认]、`xiaoxiao` 晓晓·温柔知性女声、`yunxia` 云夏·动漫元气少年音、`yunxi` 云希·阳光青年男声、`yunjian` 云健·沉稳磁性男声、`xiaobei` 晓北·东北话幽默女声、`xiaoni` 晓妮·陕西话亲切女声、`hsiaochen` 晓臻·台湾腔甜美女声、`hiugaai` 晓佳·新增粤语女声），支持中英文简称映射与 SHA-256 磁盘缓存（`~/.one_more_step/cache/tts/`）。
- **闹钟声道 MP3 音频流循环播放与离线回退**：Android 端 `NotificationExecutor` 支持通过 `MediaPlayer` + `USAGE_ALARM` 闹钟声道突破静音播放合成的 Neural TTS 音频，支持 `repeatCount`（`1..20` 次循环播报，`oms phone find` 默认 3 次），离线时自动挑选手机本地高自然度女声 Voice 模型并优化语调（`pitch=1.06f, speechRate=1.03f`）。
- **通勤随身听同步接入 Neural TTS**：新增 `tts.synthesize` 反向 RPC，手机端点击信息流卡片上的 `🎧 TTS朗读` 时同样自动调用 Mac 端 Neural TTS 真人音色。

## 1.5.3 - 2026-10-28

### WebP 硬件加速投屏与自适应 10ms 极速流控

- **WebP 屏幕帧编码与全链路升级**：将实时屏幕镜像编码从 JPEG 全面升级为 `WEBP_LOSSY`（`ScreenFrameCodec.WEBP = 2`），同画质下帧体积缩减约 30%~40%，显著提升局域网与无线投屏清晰度与帧率稳定性。
- **自适应 10ms 极速重抓与在途窗口扩容**：Android 端 `ScreenMirrorEngine` 在画面活跃变化时自动缩短重抓间隔至 `10ms`，并将 `maxInFlight` 窗口提升至 3 帧。

## 1.5.2 - 2026-10-28

### AirPods 跨生态无感自动流转、对端媒体自动暂停与实时屏幕镜像工作台

- **AirPods 跨 Mac ⇄ 小米手机蓝牙无感自动切换 (`oms airpods`)**：在 `oms-bt-bridge`（macOS `IOBluetooth` + `CoreAudio`）与 Android 端 `AirPodsAutoSwitchManager`（`BluetoothA2dp` / `BluetoothHeadset` + `AudioPlaybackConfiguration`）之间实现音频焦点仲裁；任意一端持续发声（`>320ms`）或来电通话时，自动将已配对 AirPods 抢占切换至当前活跃设备。
- **对端媒体自动暂停与切换窗口防外放保护**：当 AirPods 被对端抢占时，自动暂停原设备正在播放的音视频（Android 端结合 `MediaSessionManager.getActiveSessions`、`KEYCODE_MEDIA_PAUSE` 与 `AudioFocus` 抢占；Mac 端结合 `CoreAudio` 运行态守卫与 `MediaRemote` `kMRPause`）；蓝牙切换约 1.5s 窗口期内临时静音内置扬声器防止外放漏音。
- **实时低延迟手机屏幕镜像与远程触控工作台 (`oms mirror` / `/mirror`)**：新增 `FrameType.SCREEN_FRAME (0x07)` 16 字节二进制帧头与 `P1_STREAM_META` 优先级传输队列（`maxInFlight = 2` 背压丢帧控制），支持在 Mac 浏览器以最高 `12fps` 实时预览手机画面并直接用鼠标点击、拖拽滑动、输入文字、一键解锁与触发系统按键。

### APK

- `OneMoreStep-v1.5.2.apk`：Android 11+，versionCode 26，22,643,605 bytes（沿用 Android Debug 签名，从 v1.4.x 覆盖安装无需重新配对）。
- SHA-256：`c760477e78d0aa7aadd8aa15ba665db9cf63f7d2d20a52160b27723ac31efdb2`。

## 1.4.9 - 2026-10-28

### 远程 3×3 九宫格图案解锁与原生双卡电话拨打/挂断

- **远程点亮屏幕与 3×3 九宫格图案解锁 (`oms unlock` / `oms phone unlock`)**：支持通过 `PowerManager` 唤醒息屏手机、自动上滑呼出小米 HyperOS 锁屏密码层，并通过 `OmsAccessibilityService` 注入连续单笔轨迹 (`GestureDescription`) 划开 3×3 九宫格图案密码（`1~9` 点位序列，默认适配小米 `1220×2712` 分辨率 `175,1516,1045,2386`，支持 `--bounds` 自定义校准与 `--save` 本地持久化免输调用）。
- **原生双卡电话拨打与挂断 (`oms call` / `oms hangup`)**：若手机处于锁屏状态会自动先完成图案解锁，支持通过 `TelecomManager` 按卡槽或运营商名称（`--sim 1|2|电信|联通|移动`）发起呼叫、开启免提扬声器（`--speaker`）、仅打开拨号盘（`--dial-only`）以及远程挂断电话（`oms hangup`）。
- **多 URI Scheme 打开支持**：`AppLauncherHelper` 新增对 `tel:`、`smsto:`、`mailto:`、`market:` 等系统 URI Scheme 的直接跳转支持。

## 1.4.4 ~ 1.4.6 - 2026-10-27

### 蓝牙 BLE 配对回退、多子网 IP 记忆与连接稳定性优化

- **纯蓝牙 BLE 兜底增强 (`v1.4.6`)**：支持在局域网不可达时通过 BLE 通道完成 OTA 版本检查、在场状态刷新、压缩媒体流传输与配对回退（`pairing fallback`）。
- **BLE 扫描窗口释放与多子网历史 IP 保留 (`v1.4.5`)**：Android 端新增周期性 BLE 扫描窗口释放与 GATT 超时清理；保留多子网历史成功 IP 列表；Mac 端 `oms-bt-bridge` 新增 CoreBluetooth 发送队列与 Central 读回退通告。
- **跳过单 IP 阻塞探测与同子网优先 (`v1.4.4`)**：修复单配对 IP 在 WebSocket 连接前因 HTTP 预探测阻塞导致误报 `MAC_UNREACHABLE` 的问题，优先匹配与手机当前 Wi-Fi 同子网的 Mac 地址。

## 1.4.3 - 2026-10-27

### 大子网 Wi-Fi 锁屏保活与蓝牙/局域网协同修复

- **前台服务 `WifiLock` 低延迟保活**：在 `OmsCoreForegroundService` 中持有 `WifiManager.WifiLock(WIFI_MODE_FULL_LOW_LATENCY)`，解决小米 HyperOS 手机在锁屏熄屏 20～90 秒后 Wi-Fi 芯片进入 DTIM 深度休眠、不再响应局域网二层 ARP 导致 `closeCode=1006` 掉线的问题。
- **消除蓝牙扫描对局域网握手的抢占中断**：移除 `handleBleMacInfoBytes` 中强制重置 `isConnectingLan.set(false)` 的逻辑，且在局域网已连接时不再每 4.5 秒重复发起冗余 BLE 扫描，避免蓝牙回调打断正在进行的局域网握手（`4003 Stale connection`）。
- **BLE 流式分片写入队列**：在 Android 端 `OmsTransportClient` 实现基于 `onCharacteristicWrite` 回调的 GATT 分片写入队列（按协商 MTU 自动切片），解决 `secure.finish` 加密帧（~1.1KB）因超过 BLE 单包 512 字节上限而导致蓝牙握手最后一步失败重连死循环的问题。
- **双向主动心跳与路由自动回退**：手机端在局域网在线期间每 9 秒主动发送加密心跳帧并开启 10 秒 WebSocket Ping，保持企业级 `/23` 大子网无线 AP 的 ARP/转发表项常热；当 `findNonVpnWifiNetwork()` 自定义 SocketFactory 不可达但系统默认路由可达时自动同步回退至 WebSocket 客户端；Mac 端 `Bun.serve` 显式配置 `idleTimeout: 120` 与 `sendPings: true`。

### APK

- `OneMoreStep-v1.4.3.apk`：Android 11+，versionCode 17，22,485,137 bytes（沿用 Android Debug 签名，从 v1.4.x 覆盖安装无需重新配对）。
- SHA-256：`58c74476b5a3c4dd3404812ec6f6602e08f41217bda04da8956d14b9be7a0b9b`。

## 1.4.2 - 2026-10-27

### 手机端 UI 重构与诊断修复

- **首页布局重构**：修复展开「小米权限自检向导」或「v2 配对卡片」时把中间时间线挤压为 0、底部输入栏截断在屏幕外的问题；两个面板均增加高度上限、内部滚动与一键 `收起 ▲` 且互斥展开。
- **紧凑双层顶栏**：第一层展示图标、完整标题、版本胶囊、连接状态与 `🩺 诊断` 按钮，第二层为三等分工具栏（`🔄 检查更新` / `🔐 绑定Mac` / `⚡ 小米权限`），解决窄屏标题截断与 `v1.4.1` 诊断按钮 48dp 最小触控高度撑高顶栏的问题。
- **权限自检向导与标签栏打磨**：修复「刷新状态」文字折行，已开启权限显示柔和的 `已开启 ›`（自启动项显示 `去检查 ›`），未开启项高亮显示 `去开启`；底部标签栏与筛选栏统一收紧为 `28.dp` 胶囊高度。
- **GUI 安全护栏细节优化**：手势/点击动作完成后立即在 `delay(400)` 界面稳定等待前清除 `activeAction`，避免用户在动作完成后的 400ms 窗口内触碰屏幕误触发持久急停；允许 `home` / `lock_screen` 等安全退出动作在敏感窗口或未开悬浮窗时正常执行，且无目标节点的系统动作不再因前序动作更新观察修订号而在队列中被误拒。
- **蓝牙 BLE 与验证码提取优化**：修复 macOS CoreBluetooth 在订阅通知前过早触发 `central_connected` 的时序问题，Android 端连接 BLE 后主动协商 `requestMtu(512)`；修复机票/行程防诈提示（「请勿提供验证码」）结合航班号（如 `ZH9949`）误提取出 4 位数字验证码的问题。

### 升级方式

- **从 v1.4.0 / v1.4.1 升级无需重新配对。** 手机端点击顶栏 **🔄更新**（或访问局域网 `/install` 页面）直接覆盖安装 `OneMoreStep-v1.4.2.apk`，原配对、收件内容和离线队列完整保留。

### APK

- `OneMoreStep-v1.4.2.apk`：Android 11+，versionCode 16，22,482,269 bytes（沿用 Android Debug 签名）。
- SHA-256：`cc847169f51d4040cae483ce5f609dec8a9813872dc377aedcd5253a770e2f23`。

## 1.4.1 - 2026-10-27

### 离线原因与连接诊断

- 手机区分 Wi-Fi 不可用、Mac 探测不可达、连接失败、握手超时与安全校验失败；自动重试保留上一次原因，恢复后清除，备用链路错误不会覆盖 LAN 原因。
- 首页新增离线可用的 **连接诊断 → 复制诊断**，包含采集时的网络信息和最近连接事件。下次异常时可先复制诊断，再重连 Wi-Fi，保留排查现场。
- 手机和 Mac 各保留最近 200 条事件，记录 UTC 时间、固定事件码与本地尝试/连接编号。Mac 文件为 `~/.one_more_step/logs/connections.jsonl`，`oms status` 同时返回 `connectionDiagnostics`。
- 不记录密钥、配对链接、消息正文、屏幕或原始异常文本；复制报告包含用于排查的局域网 IPv4，不会自动外发。详情见 [连接诊断说明](docs/connection-diagnostics.md)。

### 升级方式

- **从 v1.4.0 升级无需重新配对。** 更新 Mac 代码并执行 `oms daemon restart`，手机覆盖安装附件 APK；保留原配对、收件内容和离线队列，无需卸载或清除数据。
- 从 v1.3.x 升级仍须完成 v2 配对迁移，见 [安全传输 v2](docs/secure-transport-v2.md)。
- 本次沿用 v2 协议，不改变认证或网络重试策略；此版本用于定位原因，**不宣称修复访客 Wi-Fi 的设备互通故障**。蓝牙与外网链路仍未完成专项真机验收。

### 验证结果

- 两个 TypeScript workspace 类型检查通过；Bun 52 项通过（405 次断言）；Android JVM 37 项通过，APK 构建成功。
- 真机验证：原配对自动连接、诊断入口与复制可用、关闭 Wi-Fi 后提示“未检测到 Wi-Fi”、恢复后诊断回到 `CONNECTED`，待发送队列为 0；Mac 有对应的断开与认证恢复时间线。
- 网络不可达和握手拒绝的其他分类由自动化测试支撑，不把单次真机验证外推到所有网络环境。见 [v1.4.1 验收记录](docs/ota-1.4.1.md)。

### APK

- `OneMoreStep-v1.4.1.apk`：Android 11+，versionCode 15，22,465,837 bytes。与本次已安装验收的是同一构建，沿用此前的 **Android Debug 签名**，尚未迁移到正式发布签名。
- SHA-256：`6a8bb5bd86cb78d3d0b6709723e571d0830cd575d6f6ca62c22fee53f613efb7`。
- 签名证书 SHA-256：`806caa5647ae4119df3ba6b32cd4131d94147e66d4a4992b80f7e7ac94a1e4b7`。

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
