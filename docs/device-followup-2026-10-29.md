# v1.5.6 真机发现的后续问题 · 2026-10-29

## 拨号盘首次打开误判：已修复源码，待真机复测

在安装的 v1.5.6 上使用 dialOnly=true、测试号码 2025550123。首次返回 `ok=false / callPlaced=false / callRequested=false / reason=dialer_number_not_verified`。随后无截图语义观察显示前台 com.android.contacts，EditText 已包含完整测试号码（数字间有零宽格式字符）。同样请求在拨号盘已打开时返回 `ok=true / dialedOnly=true / callPlaced=false`。现有号码归一化已经移除非拨号字符，因此证据更支持首次启动等待不足，而不是零宽字符匹配问题。

原代码在 ACTION_DIAL 后固定等待 520ms，只检查一次。改为 DialerReadiness 最多等待 3 秒，每 100ms 重查；每次检查和等待仍经过生产 GuardedRemoteAction 的急停、会话、取消及绝对期限检查。锁屏立即失败，不满足号码/拨号包条件仍失败，未增加任何点击/拨号回退。

验证：`testDebugUnitTest --offline --no-daemon` 成功，62 tests / 0 failures / 0 errors / 0 skipped；新增 6 项覆盖已就绪、延迟 1.2 秒渲染、持续错误号码、重新锁屏、撤权/过期/急停和等待取消。`git diff --check` 通过。日志 `/tmp/oms156-dialer-readiness-tests.log`。只修改研究仓库，未修改运行目录、未安装或发布新 APK。测试数字已清除，手机返回青简·咫尺。

## AirPods 往返切换超时：已修复源码，待新版真机复测

用户暂停媒体并明确选择 AirPods 4。手机→Mac 返回“连接 AirPods 等待超时”，当时两端报告未连接；恢复手机连接的请求也返回超时，但其携带状态已显示 phone.connected=true。随后实时状态再次显示 PHONE、phoneAirpodsConnected=true，用户确认已连接手机且耳机已佩戴。未播放声音；没有循环重试切换。

已确认的时限不一致：

- Mac `BluetoothBridgeServer.connectAirpodsToMac` 默认等 6500ms；Swift 最多尝试 3 轮，每轮 openConnection 的 page timeout 原始值为 5000，随后 CoreAudio 等待 1.6s，轮间还有 0.45s。SDK 的 HCI 超时单位是 0.625ms slot，原始 5000 约为 3.125s，不能直接解读成 5000ms。即便按该单位，完整三轮等待仍明显超过 6.5s。因此上层可能在底层尝试结束前就返回失败。
- Android `airpods.connectOnPhone` RPC 等 4200ms，而 manager 的等待序列 120+550+750+900ms，加每轮 4×140ms 轮询，累计最多 4560ms，尚未计入连接调用耗时。
- 超时预算不一致能够解释过早失败及迟到状态，但不能单凭源码证明所有实际连接失败均由该问题造成。系统蓝牙连接、音频 profile 与默认输出仍需独立核验。

现已统一各阶段期限，并加入原生操作失效/一次完成门控、Android 可取消轮询、指定目标和输出确认、真实释放结果与并发手动切换保护。测试覆盖新连接耗时超过旧 4.2 秒限制、期限处恰好就绪、撤权/取消、迟到结果和活动路由未确认。新版真机验收仍待完成，不能以源码测试宣称真实耳机问题已根治。证据 `/tmp/oms156-airpods-to-mac.json`、`/tmp/oms156-airpods-return-phone.json`、`/tmp/oms156-airpods-recovery-status.json`。

## 范围

本次未拨出任何电话。用户选择仅测拨号盘，实际双卡呼叫/挂断/免提仍待验收；PIN 未配置；语音与紧急通知按用户选择跳过。图案解锁只验证已有校准配置。AirPods 已恢复手机使用。

## v1.5.7 候选准备

仅研究仓库修改与本地构建；运行目录仍为已部署的 v1.5.6。新版验收步骤、自动化结果和 APK 校验见 [候选更新说明](./releases/v1.5.7.md)。本轮未触发真实耳机切换、电话呼叫或语音播放。

原生 API 依据：[Apple openConnection(_:withPageTimeout:authenticationRequired:)](https://developer.apple.com/documentation/iobluetooth/iobluetoothdevice/openconnection(_:withpagetimeout:authenticationrequired:)) 说明不提供 target 时同步等待；本机 SDK Bluetooth.h 定义 HCI 超时为 uint16_t，并提供 0.000625 秒/slot 转换。新代码将最多 5 秒转换为 8000 slots，另用单调时钟控制整项操作。

## v1.5.7 本机部署和基础真机验收（追加）

Mac 与手机已更新，LAN_WS 加密重连正常，未重新配对。微信 APK 文件及说明共 2 条成功发送。拨号盘首轮和紧接的重复请求都返回 ok=true、dialedOnly=true、callPlaced=false；测试号码已清除，手机返回青简·咫尺。没有强制结束拨号器进程，严格进程冷启动尚未验证。

AirPods 实测发现配对名“AirPods 4”与音频名“AirPods”不同；CoreAudio UID 对应目标蓝牙地址。已把 Mac 目标输出选择及释放时输出识别改为按 UID 匹配地址，新增匹配/拒绝错误地址与相似前缀的原生断言，测试和编译通过，部署 Mac 后未改动 APK。三次操作（先恢复手机、手机→Mac、Mac→手机）均返回成功并核验目标音频路由；最终回到手机，用户确认“手机显示已连接，没有异常”。

本轮没有拨打电话或播放测试声音。真实失败/超时、会话撤销、并发、自动切换、多轮长时间稳定性，以及其他 OEM/耳机兼容性仍待专项真机验收。已通过范围见 [v1.5.7 结果](./releases/v1.5.7.md)。未发布 GitHub。

## 扩展测试纠正：恢复连接仍存在问题

继续真机测试后，并发 busy、未配对地址拒绝、入盒后的 Mac 22.049 秒/手机 8.194 秒失败以及之后 25.644 秒无迟到状态变化均通过。但出盒佩戴后的恢复两轮都未完整通过：第一轮 Mac 超时而手机恢复成功，第二轮 Mac 和手机均失败。用户手动在系统蓝牙连接成功，已核验恢复为 PHONE。停止自动切换。该证据不支持“AirPods 全部修复完成”；详见候选更新说明的扩展结果。需要进一步补充连接返回码和 profile/路由阶段诊断来定位，尚无确定根因。

## v1.5.8 定向恢复验收通过

补充两端底层诊断后，确认手机存在重复提交已接受请求及等待预算不足；Mac 的长生命周期原生客户端经历合盖超时后无法在本轮恢复，而独立客户端可连接。同进程异步回调方案未通过，最终采用每次连接独立原生短进程，结束或取消时回收，主服务验证目标与默认输出。

手机 build33、Mac 最新候选已部署。Android 74/74、Mac/协议 94/94、TypeScript 与 Swift/正式 build:bt 命令通过。最终真机在主服务和主桥接未重启的情况下完成合盖超时、约 28 秒无迟到连接观察及出盒恢复：Mac 2487 ms、手机 2581 ms，最终 PHONE。未播放声音或拨出电话。一次定向复现回归通过不代表长期、通话音频或其他型号全部验证；详见 [v1.5.8 说明](./releases/v1.5.8.md)。尚未发布 GitHub。
