# AirPods 自动切换诊断与修复实施计划

**Goal:** 定位抖音已暂停但 mediaPlaying=true 的来源，修复手机播放触发接管，并避免误抢与并发交接。
**Architecture:** 保留 v1.5.8 的连接与进程隔离流程。先用被动诊断拆分信号，依据实测再修改触发策略；自动请求与手动请求共用目标、期限和会话保护。
**Tech Stack:** Kotlin/Android、TypeScript/Bun，现有认证加密 RPC。
**Spec:** 用户 2026-10-30 报告抖音开始播放后未从 Mac 自动切换，并明确要求检查修复；暂停后两次 live 查询仍为 mediaPlaying=true，Mac lastSwitchEvent 为空。

## 约束与已知事实
- 开始排查时运行 v1.5.8 / Android code33，自动开关已开启；不以重启作为修复。
- 汇总状态为真不证明具体状态源异常，也不能证明所有播放回调均已收到。
- 不读取媒体标题、通知内容、音频或凭据，不播放测试声音、不拨出电话。
- 不修改公开 v1.5.8 标签或 APK。诊断候选 Android 1.5.9 / code34，仅供本轮现场测量。
- 最终验收需用户配合暂停/播放；已有耳机连接不能被误当成新请求成功。

## 步骤
- [x] 在 AirPodsAutoSwitchManager 添加有界被动诊断：MediaSession、playback config、isMusicActive、TTS/通话、回调计数、触发/拒绝原因、剩余抑制时间。
- [x] OmsTransportClient 将诊断附入 live status；不改变播放检测及切换行为，先构建、验证签名并采集抖音暂停/播放对照。
- [x] 依据对照结果修正最小触发策略，并补相应纯逻辑回归；保留通知/TTS过滤、去抖、单次播放去重和手动切换优先。
- [x] 修复自动交接的明确竞态：先取得 Mac 精确目标释放确认，再在原会话/代次有效且播放仍有效时连接；失败或超时不抢连。
- [x] 验证抖音暂停→播放自动接管、暂停不触发、重复回调不重入、失败可诊断；最后恢复手机使用并记录未验证范围。

## 2026-10-30 现场证据与候选修复

- build34 引入的结构化诊断与旧 Mac 握手验证不兼容，造成 PROTOCOL_REJECTED。Mac 已部署只剔除可选诊断字段的补丁，实时 BT_RFCOMM 查询已恢复；build35 握手主动排除此字段，诊断仅经认证后的状态 RPC 获取。身份校验和 32 KiB 上限保留。
- 用户确认抖音暂停：MediaSession=false、musicActive=false、playbackConfigActive=true，回调计数33、lastEvaluatedPlaying=true、decision=no_rising_edge。
- 用户确认抖音播放：MediaSession=false、musicActive=true、playbackConfigActive=true，回调计数50、lastEvaluatedPlaying=true、decision=no_rising_edge。
- 实测支持的根因：底层 STARTED 配置独立置真，使聚合状态无法回到暂停，吞掉后续播放上升沿。候选移除该独立触发来源，保留会话、musicActive、通话与自身语音过滤。暂不添加轮询或新的厂商特例；是否需要回调延迟补采由真机复测决定。
- 自动交接候选：等待 Mac 释放 ACK，拒绝超时/失败结果；连接使用原加密会话及操作代次，手动新请求或停止播放使旧自动请求失效。Mac 自动释放与其他交接互斥。
- build35 为修复候选，尚待安装和自动切换真机验收；未发布 GitHub Release。

## build35 验证结果

- Android 82 项测试通过，无失败或跳过；APK 构建成功，签名与既有安装相同。
- Mac/协议 96 项测试、624 条断言通过，TypeScript 检查通过。
- Mac 自动交接互斥修复已部署并重启，手机 build34 通过 BT_RFCOMM 自动重连。build35 手机安装与自动切换效果待验收。

## 真机验收完成

- build35 同一运行进程内连续两轮抖音播放自动接管成功，用户确认耳机声音正常。claim 请求至活动设备确认分别 8697 ms、3046 ms。
- 两轮暂停观察未误抢，持续播放回调未重复发起 claim；最终耳机留在手机。
- 失败/取消/会话失效路径通过回归测试，真机未人为注入网络断开，未重测通话/TTS/其他视频 App。验收详情保存于分析目录 acceptance/2026-10-30-v1.5.9-auto-switch.md。
