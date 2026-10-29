#!/usr/bin/env bun
import { OmsIpcClient } from "../daemon/ipc-client.js";

/**
 * Module 07: Standard MCP Server (`one-more-step-mcp`) over stdio
 * Exposes 13 Tools & 3 Resources to Codex, Antigravity, OpenCode, and Claude Code.
 */

export const OMS_MCP_TOOLS = [
  {
    name: "phone_inbox_latest",
    description:
      "获取用户刚刚从小米手机主动投递或自动同步到 Mac 的最新验证码 (sms_otp)、图片、截图、文档、链接或语音闪念。支持按意图标签过滤 (default_inbox | pkb_vault | voice_idea | ui_bug | sms_otp)。",
    inputSchema: {
      type: "object",
      properties: {
        tag: {
          type: "string",
          enum: ["all", "default_inbox", "pkb_vault", "voice_idea", "ui_bug", "sms_otp"],
          default: "all",
          description: "按投递意图标签过滤 (sms_otp 为手机实时同步的验证码)",
        },
        limit: {
          type: "number",
          default: 5,
          description: "返回最近 N 条投递记录",
        },
        onlyUnprocessed: {
          type: "boolean",
          default: false,
          description: "是否仅返回尚未处理的记录",
        },
        markAsProcessed: {
          type: "boolean",
          default: false,
          description: "读取后是否自动标记为已处理",
        },
      },
    },
  },
  {
    name: "phone_media_recent",
    description:
      "主动列出小米手机相册或截图目录中最近生成的截图、相机照片或视频元数据（无需用户在手机上手动发送）。",
    inputSchema: {
      type: "object",
      properties: {
        mediaType: {
          type: "string",
          enum: ["screenshot", "camera", "all_images", "video"],
          default: "screenshot",
        },
        limit: { type: "number", default: 10 },
        sinceMinutesAgo: {
          type: "number",
          description: "可选：仅查询最近 N 分钟内拍摄或截屏的媒体",
        },
      },
    },
  },
  {
    name: "phone_file_pull",
    description:
      "将小米手机受权目录（screenshots / camera / download）中的指定图片或文件拉取到 Mac 本地，并返回 Mac 本地绝对路径供 read_file 读取。",
    inputSchema: {
      type: "object",
      properties: {
        sourceUriOrPath: {
          type: "string",
          description:
            "phone_media_recent 返回的 remotePath (content://...) 或受权目录相对路径 (如 'download/paper.pdf')",
        },
        saveToDir: {
          type: "string",
          description: "可选：保存到 Mac 的目标目录，默认保存在 ~/.one_more_step/inbox/pulled_cache/",
        },
        compressImage: {
          type: "boolean",
          default: false,
          description: "是否将大图压缩为 WebP 以加速跨网传输",
        },
      },
      required: ["sourceUriOrPath"],
    },
  },
  {
    name: "phone_file_push",
    description:
      "将 Mac 上的报告、Markdown 总结、代码片段或文件直接推送到小米手机的 OneMoreStep 时间流中，支持开启『通勤随身听 TTS 朗读』。",
    inputSchema: {
      type: "object",
      properties: {
        localFilePath: {
          type: "string",
          description: "可选：要推送的 Mac 本地文件绝对路径",
        },
        markdownOrText: {
          type: "string",
          description: "可选：直接推送的 Markdown 或文本内容",
        },
        title: {
          type: "string",
          description: "在手机时间流中显示的卡片标题",
        },
        enableCommuteTts: {
          type: "boolean",
          default: false,
          description: "是否在手机端高亮展示『🎧 TTS 通勤朗读』模式",
        },
      },
    },
  },
  {
    name: "phone_clipboard_sync",
    description: "读取小米手机当前剪贴板内容，或将 Mac 生成的文本/口令/链接直接写入手机剪贴板。",
    inputSchema: {
      type: "object",
      properties: {
        action: {
          type: "string",
          enum: ["get_phone_clipboard", "set_phone_clipboard"],
        },
        textToSet: {
          type: "string",
          description: "当 action 为 set_phone_clipboard 时写入手机剪贴板的文本",
        },
        includeRecentOtp: {
          type: "boolean",
          default: false,
        },
      },
      required: ["action"],
    },
  },
  {
    name: "phone_alarm_set",
    description: "在小米手机原生系统时钟 App 中直接静默创建闹钟。",
    inputSchema: {
      type: "object",
      properties: {
        hour: {
          type: "number",
          minimum: 0,
          maximum: 23,
          description: "24小时制小时 (0-23)",
        },
        minute: {
          type: "number",
          minimum: 0,
          maximum: 59,
          description: "分钟 (0-59)",
        },
        label: {
          type: "string",
          description: "闹钟备注标签，如『看构建结果』或『早起开会』",
        },
        daysOfWeek: {
          type: "array",
          items: { type: "number", minimum: 1, maximum: 7 },
          description: "可选：重复星期 (1=周一 ... 7=周日)",
        },
        vibrate: { type: "boolean", default: true },
      },
      required: ["hour", "minute"],
    },
  },
  {
    name: "phone_calendar_manage",
    description:
      "查询小米手机系统日历中的日程（自动展开重复会议以防排期冲突），或直接向手机系统日历写入新日程与提前提醒。",
    inputSchema: {
      type: "object",
      properties: {
        action: {
          type: "string",
          enum: ["list_events", "add_event", "delete_event"],
        },
        startIso: {
          type: "string",
          description: "ISO 8601 起始时间，如 '2026-10-22T09:00:00+08:00'",
        },
        endIso: {
          type: "string",
          description: "ISO 8601 结束时间",
        },
        title: { type: "string", description: "add_event 时的日程标题" },
        description: { type: "string", description: "add_event 时的日程备注" },
        reminderMinutesBefore: {
          type: "number",
          default: 15,
          description: "提前多少分钟触发手机原生日历响铃提醒",
        },
        eventId: { type: "number", description: "delete_event 时要删除的日程 ID" },
      },
      required: ["action"],
    },
  },
  {
    name: "phone_notify",
    description:
      "向小米手机发送分级通知提醒 (silent=静默记入时间流; normal=常规通知; urgent=突破勿扰+锁屏全屏高亮+强震动+闹钟声道强制 Neural TTS 真人语音播报)。",
    inputSchema: {
      type: "object",
      properties: {
        title: { type: "string" },
        body: { type: "string" },
        level: {
          type: "string",
          enum: ["silent", "normal", "urgent"],
          default: "normal",
        },
        ttsSpeak: {
          type: "boolean",
          default: false,
          description: "是否让手机直接用真人语音播报通知内容（urgent 级别下静音也能发声）",
        },
        voice: {
          type: "string",
          description:
            "可选：Microsoft Edge Neural TTS 音色别名（xiaoyi=甜美元气女声、xiaoxiao=温柔知性女声、yunxia=动漫元气少年音、yunxi=阳光青年男声、yunjian=沉稳磁性男声、xiaobei=东北话幽默女声、xiaoni=陕西话亲切女声、hsiaochen=台湾腔甜美女声、hiugaai=粤语自然女声）",
        },
        repeat: {
          type: "number",
          minimum: 1,
          maximum: 20,
          default: 1,
          description: "可选：语音重复播报次数（1~20 次）",
        },
        syncDingtalk: {
          type: "boolean",
          default: false,
          description: "是否同时抄送一份到钉钉机器人",
        },
      },
      required: ["title", "body"],
    },
  },
  {
    name: "phone_request_approval",
    description:
      "当长耗时任务遇到关键决策点、高风险操作、或用户离开座位需要远程拍板时，向小米手机推送交互式审批卡片并阻塞等待用户点击决策。",
    inputSchema: {
      type: "object",
      properties: {
        title: { type: "string", description: "审批标题" },
        summary: { type: "string", description: "背景说明" },
        diffOrContext: { type: "string", description: "可选：代码 Diff 或上下文摘要" },
        options: {
          type: "array",
          items: { type: "string" },
          default: ["批准继续", "拒绝并中止"],
        },
        timeoutSec: { type: "number", default: 1800 },
      },
      required: ["title", "summary"],
    },
  },
  {
    name: "phone_presence_status",
    description:
      "查询小米手机实时全维状态：空间在场状态 (AT_DESK vs AWAY)、传输通道 (LAN_WS / BT_RFCOMM / WAN_WEBRTC)、电池电量/充电/温度、屏幕亮灭与锁屏状态、当前前台应用名称与包名、局域网 IP / Wi-Fi 信号强度、音量与响铃模式、存储与内存余量、以及小米满血权限健康度。",
    inputSchema: {
      type: "object",
      properties: {
        force: {
          type: "boolean",
          default: false,
          description: "是否强制向手机发起实时状态刷新",
        },
      },
    },
  },
  {
    name: "phone_device_control",
    description:
      "远程操控小米手机系统能力：响铃/真人语音找手机 (find_phone)、Neural TTS 真人语音播报与停止 (tts_speak / stop_tts / tts_voices)、调节媒体/铃声/闹钟音量或静音 (set_volume)、震动 (vibrate)、立即抓取屏幕截图并存入收件箱 (take_screenshot)、打开指定网址 (open_url)、启动常用 App (launch_app: wechat/lark/dingtalk/camera/settings/phone 等)、远程点亮屏幕并绘制 3x3 图案解锁手机 (unlock_screen)、自动解锁并拨打电话/选卡外呼 (call_phone / dial)、挂断电话 (hangup_call)、或执行系统级按键 (system_action: home/back/recents/lock_screen/notifications)。",
    inputSchema: {
      type: "object",
      properties: {
        action: {
          type: "string",
          enum: [
            "status",
            "find_phone",
            "tts_speak",
            "tts_voices",
            "stop_tts",
            "set_volume",
            "vibrate",
            "take_screenshot",
            "open_url",
            "launch_app",
            "unlock_screen",
            "call_phone",
            "dial",
            "hangup_call",
            "system_action",
          ],
          description: "要执行的远程控制动作",
        },
        text: {
          type: "string",
          description: "find_phone 或 tts_speak 时播报的语音文本",
        },
        voice: {
          type: "string",
          description:
            "find_phone 或 tts_speak 时的 Neural TTS 音色别名（xiaoyi=甜美元气女声[默认]、xiaoxiao=温柔知性女声、yunxia=动漫元气少年音、yunxi=阳光青年男声、yunjian=沉稳磁性男声、xiaobei=东北话幽默女声、xiaoni=陕西话亲切女声、hsiaochen=台湾腔甜美女声、hiugaai=粤语自然女声）",
        },
        repeat: {
          type: "number",
          minimum: 1,
          maximum: 20,
          description:
            "find_phone 或 tts_speak 时的语音循环播报次数（1~20 次；find_phone 默认 3 次，tts_speak 默认 1 次）",
        },
        saveVoice: {
          type: "boolean",
          default: false,
          description: "是否将本次指定的 voice 设为默认语音音色",
        },
        useAlarmStream: {
          type: "boolean",
          default: true,
          description: "tts_speak 时是否走闹钟声道（静音模式下也能发声）",
        },
        stream: {
          type: "string",
          enum: ["music", "ring", "alarm", "notification"],
          default: "music",
          description: "set_volume 时调节的音频声道",
        },
        direction: {
          type: "string",
          enum: ["up", "down", "mute", "unmute"],
          description: "set_volume 时的增减或静音方向",
        },
        volumePct: {
          type: "number",
          minimum: 0,
          maximum: 100,
          description: "set_volume 时直接设定的目标音量百分比 (0-100)",
        },
        url: {
          type: "string",
          description: "open_url 时在手机浏览器打开的链接",
        },
        packageOrAlias: {
          type: "string",
          description: "launch_app 时的应用别名 (wechat/lark/dingtalk/camera/settings/phone) 或包名",
        },
        pattern: {
          type: "string",
          description:
            "unlock_screen 时的 3x3 图案密码点位序列（1~9 从左上到右下，如 '14789' 或 '1-4-7-8-9'；若已在 Mac 本地保存默认图案可省略）",
        },
        pin: {
          type: "string",
          description: "unlock_screen 时的可选数字 PIN 密码",
        },
        bounds: {
          type: "string",
          description:
            "unlock_screen 时的可选九宫格自定义矩形区域 'left,top,right,bottom'（像素或 0~1 比例）",
        },
        savePattern: {
          type: "boolean",
          default: false,
          description: "是否将本次传入的 pattern 安全保存到 Mac 本地配置中，供后续免输调用",
        },
        number: {
          type: "string",
          description: "call_phone / dial 时要拨打的电话号码（如 '10000' 或 '13800138000'）",
        },
        simSlot: {
          type: "string",
          description:
            "call_phone 时双卡手机优先使用的 SIM 卡槽或运营商关键字（如 '1'、'2'、'电信'、'联通'、'移动'，默认 SIM 1）",
        },
        dialOnly: {
          type: "boolean",
          default: false,
          description: "call_phone 时是否仅打开拨号盘填入号码而不自动按拨出键",
        },
        speaker: {
          type: "boolean",
          default: false,
          description: "call_phone 时拨通后是否自动开启免提扬声器",
        },
        target: {
          type: "string",
          enum: ["home", "back", "recents", "lock_screen", "notifications", "quick_settings"],
          description: "system_action 时的系统导航动作",
        },
      },
      required: ["action"],
    },
  },
  {
    name: "phone_gui_observe",
    description:
      "获取小米手机当前前台 App 的紧凑无障碍语义控件树（每个可操作元素带 #1, #2 短编号），可选附带静默截屏或带 #ID 视觉角标 (Set-of-Mark) 的标注截图。",
    inputSchema: {
      type: "object",
      properties: {
        includeScreenshot: {
          type: "boolean",
          default: false,
          description: "是否同时拉取当前屏幕截图到本地",
        },
        somAnnotation: {
          type: "boolean",
          default: true,
          description: "截图中是否自动绘制 #1, #2 控件边框与角标",
        },
      },
    },
  },
  {
    name: "phone_gui_action",
    description:
      "在小米手机上执行无障碍 GUI 自动化操作（按 #ID 或坐标点击、输入文本、滑动、启动 App/URL、按返回/桌面键）。支持 requireUserConfirm=true 在提交/发送前停顿等待用户确认。",
    inputSchema: {
      type: "object",
      properties: {
        action: {
          type: "string",
          enum: [
            "click",
            "long_click",
            "input_text",
            "scroll_down",
            "scroll_up",
            "swipe",
            "back",
            "home",
            "launch_app",
            "open_url",
          ],
        },
        observationId: { type: "string", description: "Observation ID from the most recent phone_gui_observe; required for node actions" },
        targetNodeId: {
          type: "string",
          description: "phone_gui_observe 返回的节点短编号，如 '#3'",
        },
        x: { type: "number" },
        y: { type: "number" },
        text: {
          type: "string",
          description: "input_text 时填入的文本，或 launch_app 时的包名，或 open_url 时的链接",
        },
        requireUserConfirm: {
          type: "boolean",
          default: false,
          description: "是否在点击前在手机屏幕上弹窗等待用户点一下确认放行",
        },
      },
      required: ["action"],
    },
  },
];

export const OMS_MCP_RESOURCES = [
  {
    uri: "oms://inbox/latest",
    name: "Latest Mobile Submission",
    description: "最新一条从小米手机投递到 Mac 的图文/闪念元数据与文件路径",
    mimeType: "application/json",
  },
  {
    uri: "oms://inbox/pkb_pending",
    name: "Pending Personal Knowledge Base Submissions",
    description: "待归入个人知识库 (pkb_vault / voice_idea) 的手机投递队列",
    mimeType: "application/json",
  },
  {
    uri: "oms://phone/status",
    name: "Xiaomi Phone Presence & Connection Status",
    description: "手机在场状态 (AT_DESK/AWAY)、传输通道与权限状态",
    mimeType: "application/json",
  },
];

export class OmsMcpServer {
  private readonly ipc: OmsIpcClient;

  constructor(customHomeDir?: string) {
    this.ipc = new OmsIpcClient(customHomeDir);
  }

  public async handleRpcRequest(req: {
    jsonrpc: string;
    id?: string | number;
    method: string;
    params?: any;
  }): Promise<any | null> {
    if (req.id === undefined) {
      // Notification (e.g. notifications/initialized)
      return null;
    }

    try {
      switch (req.method) {
        case "initialize":
          return {
            jsonrpc: "2.0",
            id: req.id,
            result: {
              protocolVersion: "2024-11-05",
              capabilities: {
                tools: {},
                resources: {},
              },
              serverInfo: {
                name: "one-more-step-mcp",
                version: "1.5.8",
              },
            },
          };

        case "ping":
          return { jsonrpc: "2.0", id: req.id, result: {} };

        case "tools/list":
          return {
            jsonrpc: "2.0",
            id: req.id,
            result: { tools: OMS_MCP_TOOLS },
          };

        case "tools/call": {
          const toolName = req.params?.name;
          if (!OMS_MCP_TOOLS.some((tool) => tool.name === toolName)) throw new Error("Unknown MCP tool");
          const toolArgs = req.params?.arguments ?? {};
          const res = await this.ipc.call(toolName, toolArgs);
          return {
            jsonrpc: "2.0",
            id: req.id,
            result: {
              content: [
                {
                  type: "text",
                  text: JSON.stringify(res, null, 2),
                },
              ],
            },
          };
        }

        case "resources/list":
          return {
            jsonrpc: "2.0",
            id: req.id,
            result: { resources: OMS_MCP_RESOURCES },
          };

        case "resources/read": {
          const uri = req.params?.uri;
          let data: unknown;
          if (uri === "oms://inbox/latest") {
            data = await this.ipc.call("phone_inbox_latest", { limit: 1 });
          } else if (uri === "oms://inbox/pkb_pending") {
            data = await this.ipc.call("phone_inbox_latest", {
              tag: "pkb_vault",
              onlyUnprocessed: true,
              limit: 20,
            });
          } else if (uri === "oms://phone/status") {
            data = await this.ipc.call("phone_presence_status", {});
          } else {
            throw new Error(`Unknown resource URI: ${uri}`);
          }
          return {
            jsonrpc: "2.0",
            id: req.id,
            result: {
              contents: [
                {
                  uri,
                  mimeType: "application/json",
                  text: JSON.stringify(data, null, 2),
                },
              ],
            },
          };
        }

        default:
          return {
            jsonrpc: "2.0",
            id: req.id,
            error: {
              code: -32601,
              message: `Method not found: ${req.method}`,
            },
          };
      }
    } catch (err: any) {
      return {
        jsonrpc: "2.0",
        id: req.id,
        result: {
          isError: true,
          content: [
            {
              type: "text",
              text: JSON.stringify(
                {
                  error: true,
                  code: err?.code ?? -32000,
                  message: err?.message ?? String(err),
                },
                null,
                2
              ),
            },
          ],
        },
      };
    }
  }

  public startStdioLoop(): void {
    let buffer = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", async (chunk: string) => {
      buffer += chunk;
      let idx: number;
      while ((idx = buffer.indexOf("\n")) !== -1) {
        const line = buffer.slice(0, idx).trim();
        buffer = buffer.slice(idx + 1);
        if (!line) continue;
        try {
          const req = JSON.parse(line);
          const resp = await this.handleRpcRequest(req);
          if (resp) {
            process.stdout.write(JSON.stringify(resp) + "\n");
          }
        } catch {
          // ignore malformed line
        }
      }
    });
  }
}

if (import.meta.main) {
  const server = new OmsMcpServer(process.env.OMS_HOME);
  server.startStdioLoop();
}
