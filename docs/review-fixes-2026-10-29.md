# v1.5.5 审查修复验收记录 · 2026-10-29

## 范围

基于 GitHub main `9469bdb`，对应上一轮审查的 S1–S3 / F1–F3，并按用户后续授权扩展异常路径测试及远程控制安全复查。修复在研究仓库分支 `fix/reliable-device-bridge`；未修改实际运行目录、未重启守护进程、未升级手机、未推送 GitHub 或创建 Release。没有提升应用版本号，候选 APK 仍标记 1.5.5 / versionCode 29，不能与公开 Release 混同。

## 修复结果

| 项目 | 修复行为 | 验证 |
|---|---|---|
| S1 镜像绕过急停 | 镜像共享 guiMutex 与 guard；排队动作、急停、撤权及期限统一检查；Mac 发送最多 5 秒绝对期限 | guard JVM 回归 + Mac RPC 期限测试；未进行新真机手势 |
| S2 挂断误触/假成功 | 删除手机固定坐标与 Mac 旧版兼容盲点；核验通话、拨号包及挂断节点；确认通话已结束才 hungUp=true | 5 项状态验证 + 旧手机失败关闭测试；OEM 按钮匹配待验收 |
| S3 紧急语音自行停止 | 全屏展示 Intent 不再带 STOP 标志，action 与点击通知的 Intent 不同 | Android 编译、调用链检查；系统全屏通知行为待真机 |
| F1 AirPods 离线假成功 | 离线先拒绝；释放确认超时与手机接管失败均返回失败阶段 | 隔离 bridge/session 回归；没有操作真实耳机 |
| F2 镜像启停竞态 | 生命周期队列与代次检查；关闭最后 viewer 的启动期也安排 25 秒停播；关闭 daemon 清理待完成启动 | 延迟 RPC、start/stop/restart、viewer 离开、失败恢复及 legacy 迟到帧回归 |
| F3 TTS 缺交付依赖 | 仓库 Python 适配器 + 独立 venv +固定 edge-tts 7.3.1；正文走 stdin；原子写音频；失败有固定降级码 | 6 项 Bun 测试、3 项 Python 离线适配测试、临时目录真实依赖安装/导入通过 |

未知通话状态或 OEM 通话界面不匹配时会安全失败，不再猜坐标。已由 Android 接受的手势仍不能保证中途撤回，停止会取消等待并阻止后续操作，不扩大为系统级撤销保证。

## 第二轮：无需真机的安全与异常路径补查

| 新确认的问题 | 本轮修复 | 离线证据 |
|---|---|---|
| 解锁与拨号未共享完整动作护栏，等待后可能继续执行 | Android 共享同一个 mutex、急停、会话及最多 25 秒期限；每段副作用前后、暂停结束后检查；拨号内解锁使用已持锁方法 | 8 项生产 gate 的 JVM 测试：失败解锁、撤权、急停、排队过期、取消释放锁、期限上限、内部解锁无死锁 |
| Mac 忽略解锁失败、自动重试凭据，旧版拨号仍猜坐标 | Mac 只发送一次有期限的绑定请求；Android 解锁失败不打开拨号盘；不支持的旧端返回升级提示 | IPC 路由测试确认错误不会触发第二次请求或 GUI 回退 |
| 拨号/免提固定坐标及 SIM 猜测 | 删除坐标回退；验证拨号包、号码、唯一呼叫按钮、SIM 标签/ID，检查通话状态后才确认成功 | 4 项实际 DialerTargetPolicy 测试；真实 OEM 控件匹配尚未验证 |
| 解锁凭据可能回显或输入未知窗口 | 删除真实图案序列响应与错误回显；凭据输入要求当前为 SystemUI 锁屏，PIN 需可识别按键 | 源码路径核验及编译通过；OEM 锁屏识别仍需真机 |
| 操作跨重连/路由切换可能落到新连接 | 新增 bindPhoneSession，捕获物理连接与路由代次；替换、撤权、切走再切回都拒绝旧绑定 | 4 项加密传输模拟回归，包括迟到/重复回复和会话替换 |
| 旧版镜像输入忽略期限、绕过入口且文字输入失败假成功 | 去除 legacy 输入翻译；要求受保护的 mirror.input，无法支持就明确升级；保留旧版画面读取 | IPC 测试确认无 clipboard.set、gui.act 或 system_action 回退；调用方短期限不被延长 |

旧客户端不带 unlock/call 期限时，Android 仍给予最多 25 秒；显式过期期限拒绝。图案解锁保留用户指定/已校准区域功能，不将它描述为无需真机校准的通用能力。无法识别 SIM 时返回 `call_target_or_sim_not_verified`，无法识别锁屏窗口时返回 `ERR_KEYGUARD_SURFACE_UNAVAILABLE`。

传输复查未发现应用层积压旧控制请求：outQueue 目前仅排心跳，RPC 直接送往原 pipe。RPC 超时只代表 Mac 不再等待，不能撤回底层已发送的数据；手机期限与分阶段检查负责阻止过期后的后续操作。已被系统接受的拨号/手势无法承诺撤销。

## 自动化结果

- `bun test`：83 pass / 0 fail，575 assertions。
- `bun run typecheck`：两个 TypeScript workspace 通过。
- `python3 mac-host/tests/test_neural_tts.py`：3 pass。
- `./gradlew testDebugUnitTest assembleDebug --offline --no-daemon`：构建成功；56 tests / 0 failure / 0 error / 0 skipped。
- `git diff --check`：通过。
- 网络测试使用仓库 preload，关闭真实设备发现，只在回环监听随机端口；第一次受限沙箱运行阻止了三个监听测试，正常权限重跑全绿。
- `OMS_HOME=<临时目录> bun run setup:tts`：成功建立隔离 venv，安装 edge-tts 7.3.1 并导入。不触碰现有服务的 runtime，没有提交文本到在线 TTS，也没有播放音频。

本轮整体验证日志：`/tmp/oms-review-fixes-second-bun.log`、`/tmp/oms-review-fixes-second-android.log`；JVM 数量从 Gradle XML 报告统计。新增代码没有调用实际手机、耳机或在线语音服务。

## 候选构建

- 路径：`android-app/app/build/outputs/apk/debug/app-debug.apk`
- SHA-256：`c33fdd2cf411624736dd0b638ad5cb11aab2d4113be2706344195f6c4d39a46f`
- 大小：23208061 bytes。
- 这是本地 Debug 验证构建，尚未发布/部署；版本号保持不变。

## 后续真机验收

1. 使用候选 APK 与匹配 Mac 修复代码，在受控测试页确认急停后镜像输入被拒绝，手机恢复后新输入正常。
2. 无通话时调用挂断应明确失败；真实测试通话的结束按钮识别及状态确认需双方事先同意，不自动拨号测试。
3. 锁屏允许全屏通知时，紧急语音自动展示应继续播放；用户点击/按键停止仍有效。
4. 快速开关镜像页面后确认手机采集/保亮退出；正常打开可恢复。
5. 手机离线的 AirPods 切换不得断开 Mac；在线真实接管失败要显示失败。
6. 新部署机器运行 setup:tts，确认在线合成音色与手机播放；缺依赖/离线时观察明确本地降级原因。在线服务及硬件行为不由离线 mock 测试证明。

7. 在受控环境确认图案/PIN 锁屏识别、拨号号码与双卡选择；验证解锁失败或急停后不继续拨号。涉及真实通话需事先约定测试号码和参与人。

修复说明与测试代码已落盘；修复完成时尚未提交、推送或发布；后续发布记录见下。Neural TTS 的在线服务边界与部署步骤见 README。

## v1.5.6 发布验证

用户后续授权发布。本次将版本提升为 1.5.6 / versionCode 30，重新完成 Bun 83 项、Python 3 项、TypeScript 与冻结锁文件检查；Android JVM 56 项通过且 Debug APK 重新构建成功。上方候选 APK 的 1.5.5 标识与哈希属于发布前快照，不是本次 Release 附件。

- 发布 APK：`OneMoreStep-v1.5.6.apk`，22721753 bytes。
- SHA-256：`f4905fdfffc76c76e9b18a8cd2e5c058e124bb5c5a9f90bc8328faf17f1dbab5`。
- 构建日志：`/tmp/oms-release-v1.5.6-android.log`、`/tmp/oms-release-v1.5.6-bun.log`。
- 本轮没有安装到手机、重启实际 Mac 服务或做真机验收；详细更新说明和待验收内容见 [v1.5.6](releases/v1.5.6.md)。
