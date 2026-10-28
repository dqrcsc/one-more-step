---
name: one-more-step
description: 青简·咫尺 (OneMoreStep / oms) —— macOS 本地 AI Agent 与小米 Android 手机（HyperOS / MIUI）的全场景双向互操作与跨端协同工具。当用户提到“oms”、“OneMoreStep”、“青简”、“看看手机发来的图/文件/想法”、“拉取手机刚截的图/拍的照片”、“把这个发到我手机上/推送到手机时间流/通勤朗读”、“同步/读取/写入手机剪贴板或验证码”、“在手机上定闹钟”、“查手机日历/加日程”、“给手机发通知/强提醒/语音播报”、“寻找手机/让手机说话”、“解锁手机/图案密码解锁”、“用手机打电话/拨号/挂断电话”、“远程截屏/调节手机音量/打开手机应用或链接”、“发远程审批卡片到手机”、或“观察/点击/自动化操作手机屏幕 App”时使用。
---

# 青简·咫尺 · OneMoreStep (`oms`) 跨端双向互操作技能

- **项目根目录**：`{{PROJECT_ROOT}}`
- **全局 CLI 命令**：`oms <subcommand>`（默认安装于 `~/.local/bin/oms`，可在任意目录直接执行）
- **备用 CLI 入口**：`bun run {{PROJECT_ROOT}}/mac-host/src/cli/index.ts <subcommand>`
- **MCP Server 入口**：`oms mcp`（或 `bun run {{PROJECT_ROOT}}/mac-host/src/mcp/server.ts`）
- **本地数据与收件箱目录**：`~/.one_more_step/`（可通过 `OMS_HOME` 覆盖）

---


> 开发分支升级注意：v1.4.0 主机和手机同时升级，旧配对需使用 Mac 本地完整链接重新配对；禁止通过旧 OMS 明文通道或 HTTP 页面传递新凭据。GUI 节点动作必须携带最新 observationId；文件推送等待对端落盘 ACK。v2 使用认证加密业务帧且拒绝明文回退；HTTP 安装页、BLE/WAN 与完整断点恢复的边界见 [implementation-status](../../docs/implementation-status.md)。

## 1. 调用方式与架构约定

v1.4.1 起，手机首页“连接诊断 → 复制诊断”离线可用，Mac 的 `oms status` 包含 `connectionDiagnostics`，最近 200 条事件保存在 `~/.one_more_step/logs/connections.jsonl`。离线时 `phoneStatus` 可能是旧快照，务必核对更新时间；先保留诊断，再决定是否重启。连接超时不等于 AP 隔离，安全校验失败不等于密钥丢失，不应直接让用户重新配对。v1.4.0 → v1.4.1 不改变 v2 配对。

1. **优先方式**：
   - 若当前会话已挂载 `one-more-step` MCP Server，可直接调用 13 个 `phone_*` MCP 工具；
   - 若未挂载 MCP Server 或在终端工作流中，直接在 Shell 中运行全局命令 `oms <subcommand>`。
2. **后台守护进程自动拉起**：
   - `oms` CLI 与 MCP Server 均通过本地 Unix Domain Socket（`~/.one_more_step/run/omsd.sock`）与单例守护进程 `omsd` 通信；若 `omsd` 未运行，首次调用会自动在后台静默拉起。
3. **离线与断连报错处理**：
   - 本地收件箱查询（`oms inbox`）与偏好设置（`oms prefs`）属于**纯本地操作**，手机未连接时依然可随时读取；
   - 主动拉取手机相册（`oms media`/`oms pull`）、推送（`oms push`）、剪贴板同步（`oms clip`）、闹钟/日历/通知/审批/GUI 自动化需要手机处于在线连接状态（`LAN_WS` / `BT_RFCOMM` / `WAN_WEBRTC`）。若返回未连接错误，先用 `oms status` 检查 `activeTransportTier`，并提示用户确认手机端 `OneMoreStep` 在后台运行或处于同一局域网/蓝牙范围内。

---

## 2. 📱 ➔ 💻 从手机向 Mac 投递与主动拉取（Inbound）

### 2.1 读取手机已主动投递的收件箱 (`oms inbox`)

当用户已在手机上通过 **三指截屏悬浮胶囊（`🚀 发给 Mac AI`）**、**系统分享置顶入口（`📚 存入个人知识库` / `🚀 发给 Mac Inbox` / `🐛 发给 AI 修 Bug`）**、**控制中心快捷磁贴** 或 **App 内发送框** 投递了内容时：

```bash
# 查看最近 N 条手机投递记录（默认 5 条）
oms inbox --limit 5

# 按意图标签过滤：all | sms_otp | default_inbox | pkb_vault | voice_idea | ui_bug
oms inbox --tag ui_bug --unprocessed

# 查看手机实时同步过来的最新验证码（加 --copy 自动将纯数字验证码写入 Mac 剪贴板）
oms otp
oms otp --copy

# 按关键词搜索投递标题、正文、语音附言或文件名
oms inbox --q "白板"

# 查看单条投递记录详情
oms inbox show <itemId>

# 对包含语音附件 (.m4a/.mp3/.wav/.ogg) 的投递记录手动触发 SenseVoice 离线语音转写
oms inbox transcribe <itemId>

# 处理完毕后，将指定记录标记为已处理（避免下次重复消费）
oms inbox ack <itemId>
```

**执行准则**：
- 返回 JSON 中的 `items[].files[].absPath` 是文件在 Mac 本地的绝对路径，拿到后直接用 `read_file` 查看图片、截图或文档；
- 手机端底部输入栏支持点击 **`🎙️语音`** 直接录制 `.m4a` 语音消息，Mac `omsd` 收到音频附件后会自动调用本地 `local-asr`（SenseVoiceSmall 离线模型）完成转写并回填到 `items[].voiceTranscript`；
- 务必同时关注 `items[].textContent` 与 `items[].voiceTranscript`；
- Mac 宿主端默认开启「收到文字/语音转写自动进 Mac 剪贴板（可直接 `⌘V`）」与「收到图片自动弹窗预览」，可通过 `oms prefs` 查看或切换。

### 2.2 主动检索并拉取手机相册最新截图或照片 (`oms media` & `oms pull`)

当用户**尚未在手机上手工点发送**，直接对你说“看看我手机刚截的那张图”、“把我手机刚拍的白板照片拉过来”或“拉取手机下载目录里的 PDF”时，无需让用户去拿手机操作：

```bash
# 【一键捷径】直接拉取手机上最新的一张截屏到 Mac 本地并返回 localAbsPath
oms pull latest-screenshot

# 【一键捷径】直接拉取手机相机最新拍摄的一张照片到 Mac 本地
oms pull latest-camera

# 先列出手机近期媒体元数据列表（--type: screenshot | camera | all_images | video，--since: 最近 N 分钟）
oms media --type screenshot --limit 10
oms media --type camera --since 30

# 根据 remotePath 或受权目录相对路径（screenshots / camera / download）拉取指定文件
oms pull "content://media/external/images/media/100001234"
oms pull "download/spec.pdf" -o ~/Downloads
```

拿到返回的 `localAbsPath`（默认保存在 `~/.one_more_step/inbox/pulled_cache/`）后，直接调用 `read_file` 进行多模态分析。

---

## 3. 💻 ➔ 📱 从 Mac 向手机推送内容与通勤随身听（Outbound）

当用户要求“把这个报告/总结/图片发到我手机上”、“推送到手机让我路上听”时，使用 `oms push`（别名 `oms send`）：

```bash
# 1. 推送 Mac 本地文件（支持 Markdown、图片、PDF、代码文件等）
oms push "/abs/path/to/report.md" --title "架构调研报告" --tts

# 2. 直接推送单行文本或简短结论
oms push --text "核心结论：方案 A 延迟降低 40%" --title "测试结论"

# 3. 通过 stdin 推送多行 Markdown 笔记或长总结（推荐，免转义）
cat <<'EOF' | oms push --stdin --title "今日复盘总结" --tts
# 今日研发复盘
1. 完成了跨端帧协议测试；
2. 修复了小米后台弹窗权限自检。
EOF
```

- 加上 `--tts`（对应 MCP `enableCommuteTts: true`）后，手机端时间流卡片会高亮展示 **`[🎧 TTS 通勤朗读 (1.2x)]`** 按钮，自动清洗 Markdown 符号并用自然中文语音朗读。

---

## 4. 📋 双向剪贴板与手机验证码实时同步 (`oms otp` & `oms clip`)

手机收到短信或微信/飞书/邮箱验证码时（即使手机锁屏息屏），会自动提取 4~8 位纯验证码推送至 Mac 桌面通知并自动写入 Mac 剪贴板（可直接 `⌘V` 粘贴）。

```bash
# 查询最近同步到 Mac 的手机验证码记录（含 otpCode、otpSender 与完整短信原文）
oms otp
oms otp --copy

# 读取小米手机当前剪贴板内容（加 --otp 可同时附带最近捕获的验证码）
oms clip get
oms clip get --otp

# 将手机剪贴板内容拉取并直接写入 Mac 系统剪贴板 (pbcopy)
oms clip pull

# 将指定文本或链接直接写入小米手机系统剪贴板
oms clip set "https://github.com/dqrcsc/one-more-step"
cat <<'EOF' | oms clip set --stdin
多行口令或配置片段
EOF

# 将当前 Mac 系统剪贴板 (pbpaste) 内容直接同步到小米手机剪贴板
oms clip push
```

---

## 5. ⏰ 原生闹钟、防冲突日历、分级强提醒与远程审批

### 5.1 手机全维实时状态查看与远程快捷控制 (`oms status` & `oms phone`)

```bash
# 1. 查询手机实时状态（加 --live 强制向手机拉取最新遥测快照）
oms status --live
oms phone status

# 2. 远程寻找手机（闹钟声道突破静音强制响铃 + 语音播报 + 强震动）
oms phone find --text "正在寻找手机，我在这里！"

# 3. 让手机直接用 TTS 开口说话（加 --alarm 可在静音模式下走闹钟声道发声）或停止朗读
oms phone say "任务已经执行完毕，请查看结果" --alarm
oms phone stop-tts

# 4. 调节手机音量或静音（--stream: music | ring | alarm | notification）
oms phone volume up
oms phone volume down
oms phone volume mute
oms phone volume 60 --stream music

# 5. 一键远程抓取手机当前屏幕截图并直接存入 Mac Inbox 与本地文件
oms phone screenshot

# 6. 在手机浏览器直接打开指定网址，或启动指定 App（支持别名 wechat/lark/dingtalk/camera/settings）
oms phone open "https://github.com"
oms phone launch wechat
oms phone launch lark

# 7. 远程点亮屏幕、上滑调出密码盘并绘制 3x3 图案密码解锁手机（1~9 对应九宫格左上到右下）
#    - 支持 oms phone unlock 或顶层捷径 oms unlock
#    - 加 --save（或 -save）可将图案密码与九宫格边界持久化存入 ~/.one_more_step/config.json，后续直接运行 oms phone unlock 即可免输一键解锁
#    - 支持 --bounds l,t,r,b 自定义或校准九宫格像素/比例区域（小米 HyperOS 1220x2712 默认精准适配 175,1516,1045,2386）
oms phone unlock 123698745 --save
oms phone unlock
oms unlock
oms phone unlock --bounds "175,1516,1045,2386" --save

# 8. 原生电话拨打与挂断（若手机锁屏会自动先唤醒并绘制图案解锁，支持双卡 SIM 1 / SIM 2 / 电信 / 联通 / 移动选择、免提扬声器与仅打开拨号盘）
oms phone call 10000
oms phone call 10000 --sim 1
oms phone call 10010 --sim 联通 --speaker
oms phone call 13800138000 --dial-only
oms phone hangup
oms call 10000
oms hangup

# 9. 执行系统级导航与锁屏动作（home | back | recents | lock | notifications）
oms phone home
oms phone back
oms phone lock

# 10. AirPods 跨 Mac ⇄ 安卓手机蓝牙无缝抢占切换与对端自动暂停
oms airpods status
oms airpods mac
oms airpods phone
oms airpods auto on
oms airpods pause on

# 11. 实时低延迟手机屏幕镜像与远程触控投屏（自动打开 http://127.0.0.1:19527/mirror）
oms mirror
oms mirror start --fps 10 --quality 68 --width 720
oms mirror stop
```

`oms status` 返回的 `phoneStatus` 包含丰富遥测字段：
- **设备与系统**：`deviceBrand`、`deviceModel`、`androidVersion`、`romVersion`（HyperOS 版本）、`appVersionName`、`uptimeMinutes`；
- **电池与温度**：`batteryPct`（电量百分比）、`isCharging`、`chargePlug`（`AC`/`USB`/`WIRELESS`）、`temperatureC`（电池温度 °C）、`voltageMv`、`powerSaveMode`；
- **屏幕与前台应用**：`screenInteractive`（亮屏/息屏）、`screenLocked`（锁屏/已解锁）、`screenBrightness`、`foregroundPackage`、`foregroundAppLabel`（中文应用名）；
- **网络与音频/存储**：`lanIps`（手机局域网 IP）、`wifiSsid`、`wifiRssiDbm`、`wifiLinkSpeedMbps`、`bluetoothEnabled`、`ringerMode`（`NORMAL`/`VIBRATE`/`SILENT`）、`volumeMusicPct`/`volumeRingPct`/`volumeAlarmPct`、`storageAvailableGb`/`storageTotalGb`、`ramAvailableMb`/`ramTotalMb`；
- **权限健康度**：`permissions`（含小米后台弹窗、锁屏显示、省电无限制、悬浮窗、无障碍、日历、相册、以及 `recordAudioGranted` 麦克风录音权限）。

### 5.2 静默设定小米原生系统闹钟 (`oms alarm`)

```bash
# 设定一次性原生时钟闹钟（24 小时制）
oms alarm 08:30 --label "起床检查夜间构建结果"

# 设定周期重复闹钟（--days: 1=周一 ... 7=周日）
oms alarm 09:00 --label "晨会提醒" --days 1,2,3,4,5
```

### 5.3 手机系统日历防冲突读写 (`oms calendar`)

**强制防冲突原则**：在向手机日历写入新日程（`add`）之前，**必须先执行 `oms calendar list`** 检查目标时间段内是否已有冲突日程（系统会自动展开周期性重复例会）。

```bash
# 1. 查询未来 N 天的手机日历日程（默认 3 天）
oms calendar list --days 3
oms calendar list --start "2026-10-27T00:00:00+08:00" --end "2026-10-28T23:59:59+08:00"

# 2. 确认无冲突后，写入带提前响铃提醒的新日程（--duration 单位为分钟，--remind 默认提前 15 分钟）
oms calendar add \
  --title "OneMoreStep 架构评审" \
  --start "2026-10-27T14:00:00+08:00" \
  --duration 60 \
  --location "线上会议" \
  --desc "讨论跨端协同工作流" \
  --remind 15

# 3. 删除指定日程
oms calendar delete <eventId>
```

### 5.4 L1~L3 分级通知与闹钟声道强制 TTS 播报 (`oms notify`)

```bash
# L1 静默记录（仅记入手机时间流，不响铃不震动）
oms notify "后台索引已完成" --title "进度记录" --level silent

# L2 常规通知（若用户在电脑旁 AT_DESK，自动转为 Mac 桌面弹窗 + 手机静默入流，避免桌上手机震动吵人）
oms notify "单元测试已全部通过" --title "测试完成" --level normal

# L3 紧急强触达（突破勿扰模式 + 锁屏全屏亮屏 + 强震动；加 --tts 走闹钟声道直接开口语音播报）
oms notify "注意：长耗时任务遇到异常，请及时查看" --title "紧急告警" --level urgent --tts
```

### 5.5 阻塞式远程交互审批 (`oms approve`)

当执行长耗时任务遇到高风险操作（如数据库迁移、生产发布、大范围重构删除）且用户可能不在电脑前时，推送交互式审批卡片到手机并阻塞等待用户点按决策：

```bash
oms approve \
  --title "是否执行生产数据库迁移？" \
  --summary "将新增 2 张索引表，预计耗时 3 秒" \
  --diff "ALTER TABLE users ADD INDEX idx_created_at (created_at);" \
  --options "批准继续,拒绝并中止" \
  --timeout 1800
```
手机端点击按钮后立即返回 `{ approved: true/false, selectedOption: "...", comment: "..." }`，严格按用户选择的分支继续或中止。

---

## 6. 📚 个人知识库 (`#pkb_vault` / `#voice_idea`) 与 `#ui_bug` 联动

1. **个人知识库素材与语音闪念归档**：
   - 当用户说“处理我从手机发来的知识库素材/想法”时：
     1. 运行 `oms inbox --tag pkb_vault --unprocessed` 与 `oms inbox --tag voice_idea --unprocessed`；
     2. 若用户配置了 `personal-knowledge-base` Skill，严格按照其治理规则将内容沉淀入库；
     3. 入库完成后，对每条已处理记录执行 `oms inbox ack <itemId>`。
2. **移动端 UI 走查与报错修复 (`#ui_bug`)**：
   - 当用户说“修一下我刚从手机发过来的 UI 报错/截图”时，运行 `oms inbox --tag ui_bug --unprocessed`，用 `read_file` 查看截图并结合 `voiceTranscript` 定位修复，完成后执行 `oms inbox ack <itemId>`。

---

## 7. 🤖 手机 App 无障碍 GUI 观察与自动化控制 (`oms gui`)

针对仅存在于手机 App 内、无开放 API 的场景（如移动端页面走查、表单填写、跨 App 提取信息）：

```bash
# 1. 提取当前屏幕紧凑无障碍语义控件树（每个可操作节点带 #1, #2 短编号，仅 5~10 KB）
oms gui observe

# 2. 需要核对视觉布局、无文本图标或自绘画面时，附加静默截屏与 Set-of-Mark (#ID 红框) 标注图
oms gui observe --screenshot --som

# 3. 按节点 #ID 或坐标执行动作
oms gui act click "#3" --observation "刚才返回的 observationId"
oms gui act long_click "#3" --observation "刚才返回的 observationId"
oms gui act input_text "#5" --text "要填入的文本内容" --observation "刚才返回的 observationId"
oms gui act scroll_down
oms gui act scroll_up
oms gui act back
oms gui act home
oms gui act launch_app "com.ss.android.lark"
oms gui act open_url "https://example.com"

# 4. 【安全铁律】点击“提交 / 发送 / 发布 / 支付 / 删除”等最终动作前，必须加 --confirm
oms gui act click "#8" --confirm --observation "刚才返回的 observationId"
```

**GUI 自动化四大安全守则**：
1. **语义树优先（Semantic Tree First）**：常规导航优先用 `oms gui observe`（不带 `--screenshot`）以保持极速低延迟；仅在需要多模态视觉确认时开启 `--screenshot --som` 并用 `read_file` 查看返回的 `screenshotLocalAbsPath`。
2. **最终动作人工确认（`--confirm`）**：凡涉及外发消息、提交审批、修改账户或删除数据的最后一步点击，**必须**附带 `--confirm`（`requireUserConfirm: true`），由用户在手机弹窗上点按确认放行。
3. **隐私窗口自动避让**：若 `oms gui observe` 返回 `isSecureWindow: true`（如密码框、银行支付页），严禁继续注入手势或截屏，应请用户在手机上手动完成该步。
4. **尊重物理急停与触屏让路**：操控期间手机顶部常驻橙色急停胶囊，一旦用户点击急停或手指触碰屏幕，请求取消当前及后续 GUI 操作（已注入手势不保证撤回），需要手机端显式恢复后重新观察，不得强行重试循环。

---

## 8. 🔗 配对、实时 Web 看板与日常维护

```bash
# 在 Mac 默认浏览器打开实时收件箱与双向协同 Web 看板 (http://127.0.0.1:19527/inbox)
oms web

# 在 Mac 本地生成安全 v2 完整配对 URI；无线安装页仅提供 APK，不提供配对密钥
oms pair

# 查看或修改 Mac 收到手机投递时的自动联动偏好（自动复制剪贴板 / 自动打开预览 / 默认解锁图案与 PIN）
oms prefs
oms prefs --auto-copy true --auto-preview true
oms prefs --unlock-pattern 123698745

# 当重新编译 Android APK 后，通过局域网一键向手机推送 OTA 更新
oms ota

# 管理后台 omsd 守护进程与查看日志
oms daemon status
oms daemon restart
oms daemon logs --lines 50
oms daemon install-launchagent
```

---

## 9. 13 个标准 MCP Tools 与 CLI 命令速查对照表

| 能力域 | MCP Tool 名称 | 等效全局 CLI 命令 (`oms`) |
| :--- | :--- | :--- |
| **收件箱查询** | `phone_inbox_latest` | `oms inbox [--tag <tag>] [--unprocessed] [--ack] \| transcribe <id>` |
| **近期相册检索** | `phone_media_recent` | `oms media [--type screenshot\|camera] [--limit N]` |
| **手机文件拉取** | `phone_file_pull` | `oms pull <remotePath \| latest-screenshot \| latest-camera>` |
| **推送与通勤朗读** | `phone_file_push` | `oms push <file \| --text \| --stdin> [--title <t>] [--tts]` |
| **双向剪贴板** | `phone_clipboard_sync` | `oms clip get [--otp] \| set <text> \| push \| pull` |
| **原生系统闹钟** | `phone_alarm_set` | `oms alarm <HH:MM> [--label <t>] [--days 1,2,3,4,5]` |
| **日历防冲突读写** | `phone_calendar_manage` | `oms calendar list [--days N] \| add ... \| delete <id>` |
| **分级通知与TTS** | `phone_notify` | `oms notify <text> [--level silent\|normal\|urgent] [--tts]` |
| **远程阻塞审批** | `phone_request_approval` | `oms approve --title <t> --summary <s> [--diff <d>]` |
| **全维状态遥测** | `phone_presence_status` | `oms status [--live] \| oms phone status` |
| **远程系统操控** | `phone_device_control` | `oms phone <find\|say\|volume\|screenshot\|open\|launch\|unlock\|call\|hangup\|home\|back\|lock>` |
| **GUI 语义树观察** | `phone_gui_observe` | `oms gui observe [--screenshot] [--som]` |
| **GUI 自动化执行** | `phone_gui_action` | `oms gui act <click\|input_text\|...> [#id] --observation <id> [--confirm]` |
