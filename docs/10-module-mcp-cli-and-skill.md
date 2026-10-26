# 10. 模块七细化设计：Mac AI Agent 接口层（13 个 MCP Tools、CLI `oms` 与全局 Skill）

> **模块定位**：连接 `omsd` 守护进程与你本地所有 AI Agent（**Codex、Antigravity、OpenCode、Claude Code** 等）的标准化桥梁。同时提供 **MCP Server (`oms mcp`)**、**命令行工具 (`oms`)** 与 **全局 Skill ([`skills/one-more-step/SKILL.md`](../skills/one-more-step/SKILL.md))** 三种无缝调用形态。

---

## 1. 13 个核心 MCP Tools 完整契约定义

所有工具通过 `@modelcontextprotocol/sdk` 注册，调用时通过本地 Unix Socket (`~/.one_more_step/run/omsd.sock`) 转发至 `omsd`。

### 1.1 收件箱、知识库捕获与文件流转类（5 个工具）

#### ① `phone_inbox_latest`
- **描述**：读取用户刚刚从小米手机主动投递到 Mac 的最新图片、截图、文档、网页链接或语音闪念。当用户说“看我刚从手机发过来的图/文件”、“处理我手机发来的报错/知识库素材/想法”时首先调用此工具。
- **输入参数 (`inputSchema`)**：
  ```json
  {
    "tag": { "type": "string", "enum": ["all", "default_inbox", "pkb_vault", "voice_idea", "ui_bug"], "default": "all", "description": "按投递意图标签过滤" },
    "limit": { "type": "number", "default": 5, "description": "返回最近 N 条投递记录" },
    "onlyUnprocessed": { "type": "boolean", "default": false, "description": "是否仅返回尚未被 AI 标记处理的记录" },
    "markAsProcessed": { "type": "boolean", "default": false, "description": "读取后是否自动标记为已处理" }
  }
  ```
- **返回结构**：包含每条投递的 `itemId`、`receivedAt`、`intentTag`、`textContent`、`voiceTranscript`（语音附言文字）及本地落盘文件的绝对路径 `files[].absPath`（AI 拿到 `absPath` 后可直接调用 `read_file` 查看图片或文档）。

#### ② `phone_media_recent`
- **描述**：主动查询小米手机相册或截图目录中最近生成的照片、截图或白板照片元数据列表（无需用户事先在手机上手动发送）。
- **输入参数**：
  ```json
  {
    "mediaType": { "type": "string", "enum": ["screenshot", "camera", "all_images", "video"], "default": "screenshot" },
    "limit": { "type": "number", "default": 10 },
    "sinceMinutesAgo": { "type": "number", "description": "可选：仅查询最近 N 分钟内拍摄或截屏的媒体" }
  }
  ```
- **返回结构**：`{ items: [{ mediaId, fileName, remotePath, mimeType, sizeBytes, width, height, createdAt }] }`。

#### ③ `phone_file_pull`
- **描述**：将手机受权目录（`screenshots`、`camera`、`download` 或自定义白名单目录）中的指定照片、截图、PDF 或文档拉取到 Mac 本地，并返回 Mac 本地绝对路径。
- **输入参数**：
  ```json
  {
    "sourceUriOrPath": { "type": "string", "description": "phone_media_recent 返回的 remotePath 或受权目录相对路径 (如 'download/paper.pdf')" },
    "saveToDir": { "type": "string", "description": "可选：指定保存到 Mac 的目标目录，默认保存在 ~/.one_more_step/inbox/pulled_cache/" },
    "compressImage": { "type": "boolean", "default": false, "description": "是否将大图转为高效 WebP 以加快跨网传输" }
  }
  ```
- **返回结构**：`{ ok: true, localAbsPath: "/Users/<you>/...", fileName, sizeBytes, sha256 }`。

#### ④ `phone_file_push`
- **描述**：将 Mac 上的报告、Markdown 总结、代码文件、图片或文本直接推送到小米手机的 `OneMoreStep` 时间流中，支持开启「通勤随身听 TTS 朗读包」模式。
- **输入参数**：
  ```json
  {
    "localFilePath": { "type": "string", "description": "可选：要推送的 Mac 本地文件绝对路径" },
    "markdownOrText": { "type": "string", "description": "可选：直接推送的 Markdown 或文本内容" },
    "title": { "type": "string", "description": "在手机时间流中显示的卡片标题" },
    "enableCommuteTts": { "type": "boolean", "default": false, "description": "是否在手机端高亮展示『🎧 一键 TTS 通勤朗读』按钮" }
  }
  ```

#### ⑤ `phone_clipboard_sync`
- **描述**：读取手机当前剪贴板内容（或最近收到的白名单验证码），或将电脑上的文本/链接/口令直接写入小米手机系统剪贴板。
- **输入参数**：
  ```json
  {
    "action": { "type": "string", "enum": ["get_phone_clipboard", "set_phone_clipboard"] },
    "textToSet": { "type": "string", "description": "当 action 为 set_phone_clipboard 时要写入手机剪贴板的文本" },
    "includeRecentOtp": { "type": "boolean", "default": false, "description": "读取时是否同时尝试提取最近 5 分钟内的短信/通知验证码" }
  }
  ```

---

### 1.2 主动式助理、日程与远程审批类（4 个工具）

#### ⑥ `phone_alarm_set`
- **描述**：在小米手机原生系统时钟 App 中直接创建闹钟。
- **输入参数**：
  ```json
  {
    "hour": { "type": "number", "minimum": 0, "maximum": 23, "description": "24小时制小时 (0-23)" },
    "minute": { "type": "number", "minimum": 0, "maximum": 59, "description": "分钟 (0-59)" },
    "label": { "type": "string", "description": "闹钟备注标签，如『检查构建结果』或『早起开会』" },
    "daysOfWeek": { "type": "array", "items": { "type": "number", "minimum": 1, "maximum": 7 }, "description": "可选：重复星期列表 (1=周一 ... 7=周日)，不传则为一次性闹钟" },
    "vibrate": { "type": "boolean", "default": true }
  }
  ```

#### ⑦ `phone_calendar_manage`
- **描述**：查询小米手机系统日历中的日程（用于排计划前防冲突），或直接向手机系统日历写入新日程与提前提醒。
- **输入参数**：
  ```json
  {
    "action": { "type": "string", "enum": ["list_events", "add_event", "delete_event"] },
    "startIso": { "type": "string", "description": "ISO 8601 起始时间，如 '2026-10-22T09:00:00+08:00'" },
    "endIso": { "type": "string", "description": "ISO 8601 结束时间" },
    "title": { "type": "string", "description": "add_event 时的日程标题" },
    "description": { "type": "string", "description": "add_event 时的日程详情备注" },
    "reminderMinutesBefore": { "type": "number", "default": 15, "description": "提前多少分钟触发手机原生日历响铃提醒" },
    "eventId": { "type": "number", "description": "delete_event 时要删除的日程 ID" }
  }
  ```

#### ⑧ `phone_notify`
- **描述**：向小米手机发送分级通知提醒，支持突破勿扰模式与手机端直接开口 TTS 语音播报。
- **输入参数**：
  ```json
  {
    "title": { "type": "string" },
    "body": { "type": "string" },
    "level": { "type": "string", "enum": ["silent", "normal", "urgent"], "default": "normal", "description": "silent=仅记入时间流; normal=常规通知; urgent=突破勿扰+锁屏高亮+强震动" },
    "ttsSpeak": { "type": "boolean", "default": false, "description": "是否让手机直接用语音播报通知内容（urgent 级别下走闹钟声道，静音也能发声）" },
    "syncDingtalk": { "type": "boolean", "default": false, "description": "是否同时抄送一份到钉钉机器人" }
  }
  ```

#### ⑨ `phone_request_approval`
- **描述**：当长耗时任务遇到关键决策点、高风险代码变更、或用户不在电脑前需要远程拍板时，向小米手机推送交互式审批卡片，并阻塞等待用户在手机上点击按钮或语音回复。
- **输入参数**：
  ```json
  {
    "title": { "type": "string", "description": "审批标题，如『是否执行生产配置更新？』" },
    "summary": { "type": "string", "description": "简明扼要的背景说明" },
    "diffOrContext": { "type": "string", "description": "可选：供用户在手机上展开查看的详细代码 Diff 或日志摘要" },
    "options": { "type": "array", "items": { "type": "string" }, "default": ["批准继续", "拒绝并中止"], "description": "供用户一键点击的选项按钮列表" },
    "timeoutSec": { "type": "number", "default": 1800, "description": "最长等待秒数" }
  }
  ```
- **返回结构**：`{ approved: boolean, selectedOption: string, userVoiceOrTextComment?: string, decidedAt: string }`。

---

### 1.3 空间感知、远程操控与 GUI 屏幕自动化类（4 个工具）

#### ⑩ `phone_presence_status`
- **描述**：查询小米手机实时全维状态：空间在场状态（`AT_DESK` vs `AWAY`）、当前激活的物理通道（`LAN_WS` / `BT_RFCOMM` / `WAN_WEBRTC`）、电池电量/温度/充电状态、屏幕亮灭与前台应用、Wi-Fi 信号与局域网 IP、各声道音量、存储与内存余量及小米权限健康度。
- **输入参数**：`{ "force": { "type": "boolean", "default": false } }`

#### ⑪ `phone_device_control`
- **描述**：远程操控小米手机系统能力：响铃找手机 (`find_phone`)、TTS 语音播报 (`tts_speak` / `stop_tts`)、调节音量或静音 (`set_volume`)、震动 (`vibrate`)、立即抓取屏幕截图并存入收件箱 (`take_screenshot`)、打开指定网址 (`open_url`)、启动常用 App (`launch_app`)、或执行系统级按键 (`system_action`: `home`/`back`/`recents`/`lock_screen`/`notifications`)。
- **输入参数**：
  ```json
  {
    "action": { "type": "string", "enum": ["status", "find_phone", "tts_speak", "stop_tts", "set_volume", "vibrate", "take_screenshot", "open_url", "launch_app", "system_action"] },
    "text": { "type": "string", "description": "find_phone 或 tts_speak 时播报的语音文本" },
    "useAlarmStream": { "type": "boolean", "default": true },
    "stream": { "type": "string", "enum": ["music", "ring", "alarm", "notification"], "default": "music" },
    "direction": { "type": "string", "enum": ["up", "down", "mute", "unmute"] },
    "volumePct": { "type": "number", "minimum": 0, "maximum": 100 },
    "url": { "type": "string" },
    "packageOrAlias": { "type": "string" },
    "target": { "type": "string", "enum": ["home", "back", "recents", "lock_screen", "notifications", "quick_settings"] }
  }
  ```

#### ⑫ `phone_gui_observe`
- **描述**：获取小米手机当前前台 App 的紧凑无障碍语义控件树（每个可操作元素带 `#1`, `#2` 短编号），可选附带静默截屏或带 `#ID` 视觉标签（Set-of-Mark）的标注截图。
- **输入参数**：
  ```json
  {
    "includeScreenshot": { "type": "boolean", "default": false, "description": "是否同时拉取当前屏幕截图到本地（90% 常规导航仅靠语义树即可，遇复杂视觉页面时设为 true）" },
    "somAnnotation": { "type": "boolean", "default": true, "description": "截图中是否自动绘制 #1, #2 控件边框与角标" }
  }
  ```
- **返回结构**：`{ packageName, activityName, isSecureWindow, nodes: [...], screenshotLocalAbsPath?: string }`。

#### ⑬ `phone_gui_action`
- **描述**：在小米手机上执行无障碍 GUI 自动化操作（按 `#ID` 或坐标点击、输入文本、滑动、打开 App/URL、按返回/桌面键）。
- **输入参数**：
  ```json
  {
    "action": { "type": "string", "enum": ["click", "long_click", "input_text", "scroll_down", "scroll_up", "swipe", "back", "home", "launch_app", "open_url"] },
    "targetNodeId": { "type": "string", "description": "phone_gui_observe 返回的节点短编号，如 '#3'" },
    "x": { "type": "number", "description": "可选：无节点编号时的备用点击横坐标" },
    "y": { "type": "number", "description": "可选：无节点编号时的备用点击纵坐标" },
    "text": { "type": "string", "description": "input_text 时要填入的文本，或 launch_app 时的包名/应用名，或 open_url 时的链接" },
    "requireUserConfirm": { "type": "boolean", "default": false, "description": "执行前是否在手机屏幕上高亮框出目标并等待用户点一下确认（用于提交表单/发送消息前的安全停顿）" }
  }
  ```

---

## 2. `oms` CLI 命令行工具规范

除了通过 MCP 调用外，所有功能均可通过终端 `oms` 命令直接调用（方便你在脚本中组合，或供偏好执行 Shell 命令的 AI 直接调用）：

```bash
# 1. 配对与守护进程管理
oms pair                                    # 终端显示配对二维码与 6 位短码
oms status                                  # 查看手机连接通道 (LAN/BT/WAN)、在场状态与电量
oms daemon start | stop | restart | logs    # 管理后台守护进程

# 2. 收件箱与文件流转
oms inbox latest [--tag pkb_vault|ui_bug|voice_idea] [--json]
oms media recent [--type screenshot|camera] [--limit 5]
oms pull <remote-path> [-o ./local_dir/]
oms push <local-file-or-md> [--title "调研报告"] [--tts]
oms clip get | set "要写入手机的文本"

# 3. 闹钟、日历与触达
oms alarm set 08:15 --label "看构建结果"
oms calendar list [--days 3]
oms calendar add --title "项目评审" --start "2026-10-22 14:00" --duration 60
oms notify "构建已完成" --level urgent --tts
oms approve --title "是否发布版本？" --summary "全部测试已通过" --options "发布,取消"

# 4. GUI 自动化
oms gui observe [--screenshot] [--som]
oms gui act click "#3"
oms gui act input "#1" --text "日报内容..."
```

---

## 3. 全局 Skill 设计（[`skills/one-more-step/SKILL.md`](../skills/one-more-step/SKILL.md)）

通过 `bun run setup` 可将仓库内的 [`skills/one-more-step/SKILL.md`](../skills/one-more-step/SKILL.md) 自动安装至 `~/.codex/skills/one-more-step/SKILL.md`，让所有支持 Skill 发现的 Agent 自动掌握你的手机协同能力：

### 3.1 Skill 核心触发场景与协同准则
1. **当用户提到“我手机刚截的图 / 刚发过来的文件 / 手机相册里的照片 / 手机下载的文档”**：
   - 优先调用 `phone_inbox_latest` 检查手机主动投递箱；若用户尚未手动发送，直接调用 `phone_media_recent` + `phone_file_pull` 主动从手机拉取，无需让用户再去拿手机发送。
2. **当用户提到“处理我手机发来的想法 / 知识库素材”**：
   - 调用 `phone_inbox_latest(tag="pkb_vault" | "voice_idea")` 获取内容，随后联动 `~/.codex/skills/personal-knowledge-base/SKILL.md` 规范进行结构化归档。
3. **当用户要求“提醒我 / 定个闹钟 / 记到日程里 / 跑完叫我”**：
   - 优先根据 `phone_presence_status` 判断用户是否在电脑旁；定闹钟调 `phone_alarm_set`，排日程先用 `phone_calendar_manage(list_events)` 查重防冲突后再 `add_event`；长任务遇到阻塞或完成时，若用户处于 `AWAY` 状态，自动调用 `phone_request_approval` 或 `phone_notify(level="urgent", ttsSpeak=true)` 触达手机。
4. **当需要操作手机专属 App（如钉钉填表、移动端页面走查、提取 App 数据）**：
   - 循环使用 `phone_gui_observe`（优先读紧凑语义树，必要时开启 `somAnnotation` 截图）与 `phone_gui_action`；凡涉及最终“提交/发送/删除”按钮，必须设置 `requireUserConfirm=true` 交由用户在手机上点最后一下确认。
