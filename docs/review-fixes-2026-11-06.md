# v1.6.1 审查修复与回归记录 · 2026-11-06

> 本文保留发布前修复阶段的验证记录。用户随后授权发布 v1.6.2 / build39，正式版本信息与验收边界见 [v1.6.2 更新说明](./releases/v1.6.2.md)。

基于 `c111c319e4081142729b6ec765a8b747fb225290` 修复本轮确认的 3 个 P1、6 个 P2。先完成三个 P1 的针对性回归，再处理 P2，最后完成全量测试。本文记录源码工作区结果，**不代表已发布新版本或完成真机验收**。

## 已修复的问题

| 优先级 / 问题 | 修复行为 | 主要实现与回归 |
| --- | --- | --- |
| P1 镜像多窗口/密码保护缺失 | 执行前重新检查全部应用窗口与焦点密码输入；保留本包和安全导航的既有特例 | `OmsAccessibilityService`、`GuardedMirrorActionTest` |
| P1 镜像急停与用户接管回退 | 登记当前 Job，急停胶囊展示成功后才执行；手势取消锁存急停；旧队列及迟到回调不能越过本地恢复，清理不移除急停提示 | `GuardedMirrorAction`、`GestureCancellation` 及测试 |
| P1 Mac 通话接听后跨会话/期限继续 | 合成后拨号、接听后等待/重复播放/挂断共享原绝对期限；原会话失效或手机急停取消播放并等待子进程回收；挂断复用原绑定会话 | `CallContinuation`、`PhoneSessionManager.bindPhoneSession`、`call-continuation.test.ts`、`secure-transport.test.ts` |
| P2 Keystore 失败造成混合配对及明文回退 | 加密成功后才提交整份新配对；失败保留旧配置；已有密文无法解密时不回退旧明文；保留旧版明文迁移兼容；发现服务只更新路由字段 | `PairingSecretPersistence`、`StreamRepository`、6 项新测试 |
| P2 AI 通话输入目标不受限 | 只选允许包/窗口的可见、启用、非密码输入；聚焦前后刷新复核；发送按钮绑定同一窗口 | `AiCallInputPolicy`、`OmsAccessibilityService`、7 项新测试 |
| P2 Mac 外放路由误报 | 原生 CoreAudio 检查 built-in transport 与 speaker terminal；未知/耳机/HDMI 明确报 `ERR_MAC_SPEAKER_ROUTE`；每轮播放前后复核，播放中监测并取消；不自动改用户输出 | Swift bridge、`BluetoothBridgeServer.queryAudioOutput`、`mac-speaker-route.test.ts` |
| P2 分享 URI authority 绕过 | 拒绝外部带 `@` 的 authority，阻止 `0@sms`、`10@calendar` 等用户限定 provider 绕过；普通 content 附件继续可用 | `ShareInputPolicy`、2 项新测试 |
| P2 Ubuntu CI JDK 路径错误 | 移除仓库级 Mac 私有 `org.gradle.java.home`，由环境 JAVA_HOME 决定；既有 CI 的 setup-java 可生效 | `android-app/gradle.properties`，本地显式 JAVA_HOME 构建通过 |
| P2 发布信息和附件不一致 | 文档与 GitHub v1.6.1 说明改为 build38 / 实测摘要，补独立校验文件；不移动标签、不替换 APK | `CHANGELOG.md`、`docs/releases/v1.6.1.md`、`v1.6.1-SHA256SUMS.txt` |

## 回归结果

- Android JVM：**117 tests，0 failures / errors / skipped**，22 个测试类。
- Mac/共享协议 Bun：**112 pass，0 fail，690 assertions**，14 个测试文件。
- 相比修复前 Android 93 项、Bun 98 项，新增 **38 项**回归。
- TypeScript：两个 workspace 类型检查通过。
- Swift：桥接完整编译通过，产物仅写入 `/tmp/oms161-bt-bridge-check`，未运行或部署。
- Android：`testDebugUnitTest assembleRelease --offline --no-daemon` 成功；本地 APK 签名校验通过。
- `git diff --check` 通过。

Bun 首次受限环境运行遇到 Swift 缓存权限与 loopback 监听失败；随后在允许本机测试监听、使用临时 Swift 缓存的环境完整重跑通过。测试 preload 禁止真实设备发现、限制 loopback，音频测试使用替身或无声 JS 子进程，没有拨号、播放音频或切换蓝牙。

完整日志已保存到审查工作区的 `one-more-step-analysis/reviews/2026-11-06/evidence/fix-*` 文件；原始日志为 `/tmp/oms161-final-bun.log`、`/tmp/oms161-final-typecheck.log`、`/tmp/oms161-final-android.log`、`/tmp/oms161-mac-p2-swift.log`。

## 发布信息核验

已回读 [v1.6.1 Release](https://github.com/dqrcsc/one-more-step/releases/tag/v1.6.1) 确认说明与校验附件存在。原 APK 的 SHA-256 仍为：

```text
4a8b209362196d6a1e2770ce77153ad72d14adc6efb2451f76f02a01abbd5011  OneMoreStep-v1.6.1.apk
```

本轮编译的修复 APK 只是本地回归产物，仍沿用源码中的 1.6.1 / code38，**没有作为公开 v1.6.1 的替代包上传，也不应直接作为下一版分发**。正式分发前应增加版本号/code，核对运行目录未提交改动，再准备独立更新包。

## 尚需真机或远端验收

1. 镜像在分屏、小窗、密码焦点场景拒绝危险手势；急停胶囊可见，手指打断后停止锁存，恢复不会执行旧请求；检查连续镜像手势流畅度。
2. AI 通话在目标机型实际页面完成输入/发送；其他分屏输入框不被写入；窗口切换或变成密码框后停止。
3. 预约测试号码后，验证接听后断连、急停、期限到达会停止 Mac 播放，重连后的新会话不被旧请求挂断。
4. 内置扬声器、AirPods、HDMI 等真实输出的识别及中途切换取消。路由监测每 250 ms 发起、查询最长 1 秒；系统路由与 afplay 不能原子锁定，检测有延迟，不承诺切换瞬间零音频。播放成功也不等同于对方实际听清。
5. Keystore 真实设备迁移及不可用后的恢复；密文无法解密时返回不可用，可能需要重新配对，不会自动回退明文。不要用真实唯一配对配置做破坏性故障注入。
6. 推送修复后确认 GitHub Ubuntu CI 通过。本轮仅移除已确认的启动错误并完成本地回归，没有把本地成功称为远端 CI 已通过。

运行目录 `/Users/dqrcsc/x_project/one_more_step` 的已有未提交修改保持不变；本轮未部署、未重启本地服务、未安装手机 APK。代码修复尚未推送 GitHub。
