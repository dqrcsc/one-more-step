#!/usr/bin/env bun
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { execFile, execFileSync, spawnSync } from "node:child_process";
import { OmsIpcClient } from "../daemon/ipc-client.js";
import { OmsDaemon } from "../daemon/oms-daemon.js";
import { OmsMcpServer } from "../mcp/server.js";

/**
 * Module 07: Unified `oms` Command Line Interface (CLI) for Humans & AI Agents
 */

function parseFlags(args: string[]): { positional: string[]; flags: Record<string, string | boolean> } {
  const positional: string[] = [];
  const flags: Record<string, string | boolean> = {};
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a.startsWith("--")) {
      const key = a.slice(2);
      const next = args[i + 1];
      if (next !== undefined && !next.startsWith("-")) {
        flags[key] = next;
        i++;
      } else {
        flags[key] = true;
      }
    } else if (a.startsWith("-") && a.length >= 2 && !/^\d+$/.test(a.slice(1))) {
      const key = a.startsWith("--") ? a.slice(2) : a.slice(1);
      const next = args[i + 1];
      if (next !== undefined && !next.startsWith("-")) {
        flags[key] = next;
        i++;
      } else {
        flags[key] = true;
      }
    } else {
      positional.push(a);
    }
  }
  return { positional, flags };
}

function parseBoolFlag(val: string | boolean | undefined): boolean | undefined {
  if (val === undefined) return undefined;
  if (typeof val === "boolean") return val;
  const s = val.toLowerCase();
  if (s === "true" || s === "1" || s === "on" || s === "yes") return true;
  if (s === "false" || s === "0" || s === "off" || s === "no") return false;
  return undefined;
}

function resolveCliSpeechFlags(flags: Record<string, string | boolean>): {
  audioPolicy?: string;
  useAlarmStream?: boolean;
  forceSpeakerAlarmStream?: boolean;
} {
  const explicitPolicy =
    typeof flags["audio-policy"] === "string" ? flags["audio-policy"] : undefined;
  const forceSpeaker =
    flags["force-speaker"] === true
      ? true
      : parseBoolFlag(flags["force-speaker"]);
  const noAlarm = Boolean(flags["no-alarm"] || flags.media);
  const alarmFlag = parseBoolFlag(flags.alarm);
  const useAlarmStream = noAlarm ? false : alarmFlag;
  const audioPolicy =
    explicitPolicy ||
    (forceSpeaker === true
      ? "force_speaker_alarm"
      : useAlarmStream === false
        ? "media_commute"
        : undefined);
  return {
    ...(audioPolicy ? { audioPolicy } : {}),
    ...(useAlarmStream !== undefined ? { useAlarmStream } : {}),
    ...(forceSpeaker !== undefined ? { forceSpeakerAlarmStream: forceSpeaker } : {}),
  };
}

async function executeCliPhoneCall(
  ipc: OmsIpcClient,
  flags: Record<string, string | boolean>,
  positional: string[],
  posOffset: number,
  isDialOnlyCmd: boolean,
  usagePrefix: string
): Promise<void> {
  const number = (flags.number as string) || positional[posOffset];
  if (!number) {
    throw new Error(
      `Usage: ${usagePrefix} <name-or-number> [--sim 1|2] [--say <text> | --mac-say <text> | --ai-call <text>] [--voice <v>] [--repeat <N>] [--hangup-after] [--wait-answer <sec>] [--dial-only] [--speaker|--no-speaker]`
    );
  }
  const trailingTextPos = posOffset + 1;
  const sayText =
    typeof flags.say === "string"
      ? flags.say
      : typeof flags.tts === "string"
        ? flags.tts
        : typeof flags.speak === "string"
          ? flags.speak
          : positional.length > trailingTextPos
            ? positional.slice(trailingTextPos).join(" ")
            : undefined;
  const macSayText =
    typeof flags["mac-say"] === "string"
      ? flags["mac-say"]
      : typeof flags["mac-tts"] === "string"
        ? flags["mac-tts"]
        : undefined;
  const aiCallText =
    typeof flags["ai-call"] === "string"
      ? flags["ai-call"]
      : typeof flags.ai === "string"
        ? flags.ai
        : undefined;
  const speakerFlag =
    flags["no-speaker"] ? false : flags.speaker !== undefined ? Boolean(flags.speaker) : undefined;
  const hasSpeechTask = Boolean(sayText || macSayText || aiCallText);
  const res = await ipc.call(
    "phone_device_control",
    {
      action: "call_phone",
      number,
      simSlot: flags.sim !== undefined ? String(flags.sim) : undefined,
      dialOnly: isDialOnlyCmd || Boolean(flags["dial-only"]),
      speaker: speakerFlag,
      say: sayText,
      macSay: macSayText,
      aiCall: aiCallText,
      voice: typeof flags.voice === "string" ? flags.voice : undefined,
      repeat: flags.repeat !== undefined ? Number(flags.repeat) : undefined,
      hangupAfter: Boolean(flags["hangup-after"] || flags["auto-hangup"] || flags.hangup),
      waitAnswerSec: flags["wait-answer"] !== undefined ? Number(flags["wait-answer"]) : undefined,
      postAnswerDelayMs: flags["speak-delay"] !== undefined ? Number(flags["speak-delay"]) : undefined,
      saveVoice: Boolean(flags.save),
    },
    hasSpeechTask ? 95000 : 35000
  );
  console.log(JSON.stringify(res, null, 2));
}

async function readStdinText(): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of process.stdin) {
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks).toString("utf8");
}

function getCliVersion(): string {
  try {
    const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../../..");
    const pkg = JSON.parse(fs.readFileSync(path.join(repoRoot, "package.json"), "utf8"));
    return String(pkg.version || "2.2.0");
  } catch {
    return "2.2.0";
  }
}

const HELP_SECTIONS: Record<string, string> = {
  system: `【一、连接诊断、跨网中继与守护进程 (system)】
  oms status [--live]                           全维状态总览（在场 AT_DESK/AWAY、三级通道、手机遥测、中继、防休眠、微信尺素）
  oms wan [status|test|on|off|set]              跨网加密中继（Tier 3 WAN）状态、HTTPS 延迟探测、开关与配置下发
    • oms wan status                              查看本机与跨网中继房间连接状态
    • oms wan test                                实测本机到云端中继 HTTPS 健康度与 RTT 延迟
    • oms wan on | off                            启用或停用跨网远程加密通道
    • oms wan set --url <wss> --token <t> [--ca <pem>]  更新中继配置并自动加密同步至手机
  oms stay-awake [status|on|off]                Mac 离家驻守防休眠（托管 caffeinate -i -s：允许息屏锁屏，防止断网睡眠）
  oms pair [--json]                             生成 10 分钟有效的安全 v2 完整配对 URI 与本地无线安装页 (/install)
  oms ota                                       校验 SHA-256 与签名，向手机推送最新 APK 覆盖升级
  oms prefs [--auto-copy|--auto-preview|--auto-otp|--auto-wx-phone|--tts-voice|--unlock-pattern|--unlock-pin]
                                                查看或更新宿主自动复制、预览、验证码、默认 TTS 音色与解锁凭据偏好
  oms edge-sync                                 立即向手机加密同步代理节点快照、AI 配置、跨网中继凭据、上下文胶囊与微信游标
  oms daemon [status|start|stop|restart|logs [--lines N] [--wx]|install-launchagent]
                                                管理后台单例守护进程 omsd、查看主日志/微信子进程日志或注册开机自启
  oms version                                   显示当前版本号`,

  transfer: `【二、手机 ⇄ Mac 收件箱、媒体流转与剪贴板/验证码 (transfer)】
  oms inbox [--tag <tag>] [--unprocessed] [--q <kw>] [--limit N] [--ack]
                                                查询手机投递记录（tag: all|sms_otp|default_inbox|pkb_vault|voice_idea|ui_bug）
    • oms inbox show <itemId>                     查看单条投递完整元数据与本地文件绝对路径
    • oms inbox peek [itemId]                     在 Mac 原生无打扰 HUD 速览浮窗中内联预览图片/PDF/Office/音频
    • oms inbox transcribe <itemId>               对语音附件触发本机 SenseVoiceSmall 离线转写
    • oms inbox ack <itemId> [--unprocessed]      将记录标记为已处理（或恢复待处理）
    • oms inbox clear-processed                   清理收件箱索引中已处理的记录（保留磁盘原始附件）
  oms otp [--limit N] [--copy] [--ack]          查询手机锁屏实时同步的最新验证码（--copy 将纯数字写入 Mac 剪贴板）
  oms media [--type screenshot|camera|all_images|video] [--limit N] [--since <min>]
                                                主动列出手机近期截屏/相机照片/视频元数据
  oms pull <latest-screenshot|latest-camera|remote-path> [-o <dir>] [--compress]
                                                一键拉取手机最新截屏、最新照片或受权目录文件到 Mac 本地
  oms push <file-or-text> | --file <p> | --text <md> | --stdin [--title <t>] [--tts]
                                                向手机时间流推送文件或 Markdown（别名 oms send；--tts 开启通勤朗读）
  oms clip [get [--otp] | set <text> [--stdin] | pull | push]
                                                读写手机剪贴板，或与 Mac 系统剪贴板 (pbcopy/pbpaste) 一键互推互拉`,

  device: `【三、手机硬件遥控、真人语音、解锁与通话 (device)】
  oms phone <subcommand>                        手机全能系统级控制（别名 oms control）：
    • oms phone status                            强制拉取手机最新电量、温度、前台应用、网络、存储与权限健康度
    • oms phone find [text] [--voice <v>] [--repeat <N>] [--save]
                                                  强制外放 + 闹钟声道突破静音真人语音寻机（手机支持按键/解锁/通知 5 种即停）
    • oms phone say <text> [--force-speaker|--no-alarm] [--voice <v>] [--repeat <N>] [--save]
                                                  9 款 Edge Neural TTS 真人语音播报（默认戴耳机私密走耳机，无耳机走闹钟声道）
    • oms phone voices | stop-tts                 查看 9 款真人音色列表 / 立即停止手机正在播放的语音
    • oms phone volume <up|down|mute|unmute|0..100> [--stream music|ring|alarm|notification]
    • oms phone vibrate [ms]                      触发手机线性马达震动
    • oms phone screenshot [--no-inbox]           远程静默抓取手机当前屏幕并落盘至 Mac
    • oms phone open <url> | launch <wechat|lark|dingtalk|camera|settings|pkg>
    • oms phone <home|back|recents|notifications|lock>
  oms unlock [pattern] [--pin <p>] [--bounds l,t,r,b] [--save]
                                                远程唤醒屏幕、上滑调出密码盘并绘制 3x3 九宫格图案（1~9）或 PIN 解锁
  oms contacts [list | set <name> <number> [--sim 1|2] [--note <s>] | remove <name>]
                                                管理本地常用联系人速查表（供 oms call 直接按姓名外呼）
  oms call <name-or-number> [--sim 1|2|电信|联通|移动] [--speaker] [--mac-say <t>|--ai-call <t>|--say <t>] [--voice <v>] [--hangup-after]
                                                自动解锁选卡拨号，支持接通后 Mac 扬声器喂麦或小米 AI 通话自动传话（oms dial 仅拨号盘）
  oms hangup                                    远程挂断正在振铃或通话中的电话`,

  assistant: `【四、主动式日程助理、分级强提醒与远程审批 (assistant)】
  oms alarm <HH:MM> [--label <t>] [--days 1,2,3,4,5] [--vibrate false]
                                                在小米原生系统时钟 App 中静默设定一次性或周期闹钟
  oms calendar [list [--days N] [--start <iso>] [--end <iso>] | add --title <t> --start <iso> [--duration <m>] [--remind <m>] | delete <id>]
                                                防冲突查询手机日历（自动展开重复例会），或新增/删除带响铃提醒的日程
  oms notify <text> [--title <t>] [--level silent|normal|urgent] [--tts] [--voice <v>] [--repeat <N>]
                                                L1~L3 在场感知分级通知（urgent + --tts 突破勿扰与锁屏强提醒播报）
  oms approve --title <t> --summary <s> [--diff <d>] [--options "批准继续,拒绝并中止"] [--timeout 1800]
                                                推送高风险操作远程审批卡至手机，阻塞等待用户点按决策后返回`,

  gui: `【五、跨生态外设、实时投屏与无障碍 GUI 自动化 (gui)】
  oms airpods [status [--live] | mac | phone | auto <on|off> | pause <on|off>]
                                                AirPods 在 Mac 与小米手机间随音频焦点无感抢占切换与对端媒体自动暂停
  oms mirror [open|start|stop|status] [--fps 12] [--width 720] [--quality 72] [--no-wake]
                                                打开实时低延迟手机屏幕镜像与电脑鼠标触控工作台 (/mirror)
  oms web                                       在 Mac 默认浏览器打开本地实时协同与微信尺素总控台 (/inbox)
  oms gui observe [--screenshot] [--som]        提取当前屏幕紧凑无障碍语义控件树（#1, #2）及可选 Set-of-Mark 标注图
  oms gui act <click|long_click|input_text|scroll_down|scroll_up|back|home|launch_app|open_url> [#id] --observation <obsId> [--text <t>] [--confirm]
                                                受 observationId 时效校验、隐私页避让、顶部急停胶囊与 --confirm 保护的 App 自动化`,

  wx: `【六、微信尺素：公众号/视频深读、知识库沉淀与推送微信 (wx)】
  oms wx <subcommand>                           微信 ClawBot 上下文桥接总入口（兼容独立别名 wx-ctx）：
    • oms wx list [--triage <t>] [--json]         查看微信转发文章收件箱与初筛状态（默认只读，不主动联网轮询）
    • oms wx show <id> [--json]                   查看单篇文章详情、采集质量标签、原文路径与伴读笔记
    • oms wx peek [id] | to-phone <id>            在 Mac 原生 HUD 浮窗打开阅读 / 同步至小米手机通勤 TTS 朗读
    • oms wx recent | inbox | tasks [--json]      查看近期非文章消息、想法速记与异步任务记录
    • oms wx sync | ingest <url> [--note <n>]     主动拉取微信新消息 / 手动导入单篇链接生成初筛伴读
    • oms wx reanalyze <id> [--force] [--send]    重新运行模型初筛分析（可选回发微信）
    • oms wx push [--text <t>|--stdin|--file <p>|--article <id>] [--formats md,html,pdf] [--with-file] [--json]
                                                  将总结文本、图片或自动编译的 MD/HTML/PDF 多格式文档推送回手机微信
    • oms wx schedule <list|add|run|done|cancel>  管理微信待办提醒与周期性信息收集定时任务
    • oms wx status | doctor | login | web | config-web`,

  agents: `【七、桌面重脑会话中枢与 MCP 接入 (agents)】
  oms agents [list [--limit N] | projects]      列出本机活跃/历史 Codex 与 Antigravity 会话及可派单项目工作区目录
  oms agents show <codex|agy> <sessionId>       查看指定桌面/远程 Agent 会话的步骤流与文件变更详情
  oms agents start <prompt> [--backend codex|agy] [--cwd <dir>]
                                                在指定工作区后台拉起新的 Codex 或 Antigravity 任务会话
  oms agents resume <codex|agy> <sessionId> <prompt>
                                                按原 session_id / conversation_id 跨端多轮接续追问已有会话
  oms agents cancel <codex|agy> <sessionId>     中止正在运行的受管 Agent 会话
  oms mcp                                       以标准 stdio 模式启动 MCP Server（暴露 13 个 Tools 与 3 个 Resources）`,
};

const CMD_TO_HELP_SECTION: Record<string, string> = {
  system: "system",
  status: "system",
  doctor: "system",
  wan: "system",
  "stay-awake": "system",
  awake: "system",
  pair: "system",
  ota: "system",
  prefs: "system",
  "edge-sync": "system",
  daemon: "system",
  version: "system",
  transfer: "transfer",
  inbox: "transfer",
  otp: "transfer",
  media: "transfer",
  pull: "transfer",
  push: "transfer",
  send: "transfer",
  clip: "transfer",
  device: "device",
  phone: "device",
  control: "device",
  unlock: "device",
  contacts: "device",
  contact: "device",
  call: "device",
  dial: "device",
  hangup: "device",
  assistant: "assistant",
  alarm: "assistant",
  calendar: "assistant",
  notify: "assistant",
  approve: "assistant",
  gui: "gui",
  airpods: "gui",
  mirror: "gui",
  web: "gui",
  wx: "wx",
  wechat: "wx",
  agents: "agents",
  agent: "agents",
  mcp: "agents",
};

function printStructuredHelp(topic?: string): void {
  const ver = getCliVersion();
  const key = topic ? CMD_TO_HELP_SECTION[topic.toLowerCase()] : undefined;
  console.log(`青简 · OneMoreStep CLI (oms v${ver}) — macOS 本地 AI ⇄ 小米 Android 认知座舱 & 微信尺素统一命令行\n`);
  if (key && HELP_SECTIONS[key]) {
    console.log(HELP_SECTIONS[key]);
    console.log(`\n提示：运行 \`oms help\` 查看全部七大命令域总览。`);
    return;
  }
  console.log(Object.values(HELP_SECTIONS).join("\n\n"));
  console.log(`\n快速按域查询帮助：oms help <system | transfer | device | assistant | gui | wx | agents>`);
}

async function main() {
  const rawArgs = process.argv.slice(2);
  const firstArg = rawArgs[0] || "help";

  if (firstArg === "--version" || firstArg === "-v" || firstArg === "version") {
    console.log(`one-more-step v${getCliVersion()}`);
    return;
  }

  if (firstArg === "--help" || firstArg === "-h" || firstArg === "help") {
    printStructuredHelp(rawArgs[1]);
    return;
  }

  const cmd = firstArg;
  const sub = rawArgs[1];
  const { positional, flags } = parseFlags(rawArgs.slice(1));

  if ((flags.help || flags.h) && cmd !== "wx" && cmd !== "wechat") {
    printStructuredHelp(cmd);
    return;
  }

  const homeDir = process.env.OMS_HOME || path.join(os.homedir(), ".one_more_step");
  const ipc = new OmsIpcClient(homeDir);

  if (cmd === "mcp") {
    const server = new OmsMcpServer(homeDir);
    server.startStdioLoop();
    return;
  }

  if (cmd === "daemon") {
    const pidFile = path.join(homeDir, "run", "omsd.pid");
    const sockFile = path.join(homeDir, "run", "omsd.sock");
    const logFile = path.join(homeDir, "logs", "omsd.log");

    if (sub === "install-launchagent") {
      const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../../..");
      const plist = OmsDaemon.generateLaunchAgentPlist(repoRoot);
      const plistPath = path.join(
        os.homedir(),
        "Library/LaunchAgents/com.oms.daemon.plist"
      );
      fs.mkdirSync(path.dirname(plistPath), { recursive: true });
      fs.writeFileSync(plistPath, plist, "utf8");
      console.log(`✅ Installed LaunchAgent at: ${plistPath}`);
      return;
    }

    if (sub === "logs") {
      const targetLog = flags.wx || flags.wechat ? path.join(homeDir, "logs", "wechat.log") : logFile;
      const lines = flags.lines ? Number(flags.lines) : 50;
      if (!fs.existsSync(targetLog)) {
        console.log(`No daemon log file yet at ${targetLog}`);
        return;
      }
      const content = fs.readFileSync(targetLog, "utf8").split("\n");
      console.log(content.slice(-lines).join("\n"));
      return;
    }

    if (sub === "stop" || sub === "restart") {
      const defaultHome = path.join(os.homedir(), ".one_more_step");
      const plistPath = path.join(os.homedir(), "Library/LaunchAgents/com.oms.daemon.plist");
      const uid = typeof process.getuid === "function" ? process.getuid() : undefined;
      const isManagedDefaultDaemon =
        process.platform === "darwin" &&
        uid !== undefined &&
        path.resolve(homeDir) === path.resolve(defaultHome) &&
        fs.existsSync(plistPath);

      if (sub === "restart" && isManagedDefaultDaemon) {
        const kick = spawnSync("launchctl", ["kickstart", "-k", `gui/${uid}/com.oms.daemon`], {
          stdio: "ignore",
        });
        if (kick.status === 0) {
          await new Promise((r) => setTimeout(r, 600));
          const status = await ipc.call("phone_presence_status", {});
          console.log(JSON.stringify(status, null, 2));
          return;
        }
      }

      if (fs.existsSync(pidFile)) {
        const pid = Number(fs.readFileSync(pidFile, "utf8").trim());
        if (pid > 0) {
          try {
            process.kill(pid, "SIGTERM");
            for (let i = 0; i < 15; i++) {
              await new Promise((r) => setTimeout(r, 100));
              try {
                process.kill(pid, 0);
              } catch {
                break;
              }
            }
            try {
              process.kill(pid, 0);
              process.kill(pid, "SIGKILL");
            } catch {
              // already exited cleanly
            }
          } catch {
            // already exited
          }
        }
        try {
          fs.unlinkSync(pidFile);
        } catch {}
      }
      if (fs.existsSync(sockFile)) {
        try {
          fs.unlinkSync(sockFile);
        } catch {}
      }
      if (sub === "stop") {
        console.log(JSON.stringify({ ok: true, status: "stopped" }, null, 2));
        return;
      }
      await new Promise((r) => setTimeout(r, 250));
    }

    // start / restart / status
    const status = await ipc.call("phone_presence_status", {});
    console.log(JSON.stringify(status, null, 2));
    return;
  }

  switch (cmd) {
    case "pair": {
      const res = await ipc.call("daemon.pair", {});
      if (flags.json) {
        console.log(JSON.stringify(res, null, 2));
        break;
      }
      console.log("==============================================================");
      console.log("🔗 OneMoreStep · 小米手机无线安装与绑定凭证 (10分钟内有效)");
      console.log("==============================================================");
      console.log(`📲 【无数据线 APK 下载页】(仅提供安装，不提供配对凭据):`);
      console.log(`   👉 ${res.wirelessInstallUrl}\n`);
      console.log(`📬 【Mac 实时收件箱看板】(电脑浏览器打开):`);
      console.log(`   👉 ${res.dashboardUrl}\n`);
      console.log("🔐 安全 v2：旧版 PIN/链接不可用。请通过自己控制的可信途径将下方完整 URI 输入手机，不要经旧 OMS 明文通道传递。");
      console.log(`🌐 本机局域网地址:   ${res.lanIps.join(", ")} : ${res.lanPort}`);
      console.log(`🔑 完整配对 URI:\n   ${res.pairingUri}`);
      console.log("==============================================================");
      break;
    }

    case "web": {
      const st = await ipc.call("phone_presence_status", {});
      const url = st.dashboardUrl || `http://127.0.0.1:${st.lanPort || 19527}/inbox`;
      console.log(`📬 Opening Mac Inbox Dashboard: ${url}`);
      if (process.env.OMS_DISABLE_OSASCRIPT !== "1") {
        execFile("open", [url]);
      }
      break;
    }

    case "mirror": {
      const action = (sub || "open").toLowerCase();
      if (action === "stop") {
        const res = await ipc.call("phone_mirror_stop", { reason: "cli_stop" });
        console.log(JSON.stringify(res, null, 2));
        break;
      }
      if (action === "status") {
        const res = await ipc.call("phone_mirror_status", {});
        console.log(JSON.stringify(res, null, 2));
        break;
      }
      const st = await ipc.call("phone_presence_status", {});
      const port = st.lanPort || 19527;
      const mirrorUrl = `http://127.0.0.1:${port}/mirror`;
      let mirrorRes: any = null;
      try {
        mirrorRes = await ipc.call("phone_mirror_start", {
          maxWidth: flags.width ? Number(flags.width) : 720,
          quality: flags.quality ? Number(flags.quality) : 72,
          maxFps: flags.fps ? Number(flags.fps) : 12,
          autoWake: flags["no-wake"] ? false : true,
          wakeScreen: flags["no-wake"] ? false : true,
        });
      } catch (err: any) {
        mirrorRes = { ok: false, error: err?.message || String(err) };
      }
      const shouldOpen =
        action !== "start" || Boolean(flags.open)
          ? !flags["no-open"] && process.env.OMS_DISABLE_OSASCRIPT !== "1"
          : false;
      if (shouldOpen) {
        execFile("open", [mirrorUrl]);
      }
      if (flags.json || action === "start") {
        console.log(
          JSON.stringify(
            {
              ...mirrorRes,
              mirrorUrl,
            },
            null,
            2
          )
        );
      } else {
        console.log(`🪞 Opening Live Phone Mirror Studio: ${mirrorUrl}`);
        if (mirrorRes?.mode) {
          console.log(
            `   Mode: ${mirrorRes.mode} (${mirrorRes.maxWidth || 720}px @ ${mirrorRes.maxFps || 12}fps)`
          );
        } else if (mirrorRes?.error) {
          console.log(`   Note: ${mirrorRes.error}`);
        }
      }
      break;
    }

    case "status":
    case "doctor": {
      const res = await ipc.call("phone_presence_status", {
        force: Boolean(flags.live || flags.force),
      });
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case "prefs": {
      const autoCopy = parseBoolFlag(flags["auto-copy"]);
      const autoPreview = parseBoolFlag(flags["auto-preview"]);
      const autoOtp = parseBoolFlag(flags["auto-otp"]);
      const autoWxPhone = parseBoolFlag(flags["auto-wx-phone"]);
      const preferredTtsVoice =
        typeof flags["tts-voice"] === "string"
          ? flags["tts-voice"]
          : typeof flags.voice === "string"
            ? flags.voice
            : undefined;
      const unlockPattern =
        typeof flags["unlock-pattern"] === "string"
          ? flags["unlock-pattern"]
          : typeof flags.pattern === "string"
            ? flags.pattern
            : undefined;
      const unlockPin =
        typeof flags["unlock-pin"] === "string"
          ? flags["unlock-pin"]
          : typeof flags.pin === "string"
            ? flags.pin
            : undefined;
      const res = await ipc.call("daemon.prefs", {
        autoCopyTextToClipboard: autoCopy,
        autoPreviewImageOnMac: autoPreview,
        autoSyncOtpToMac: autoOtp,
        autoSyncWeChatToPhone: autoWxPhone,
        preferredTtsVoice,
        unlockPattern,
        unlockPin,
      });
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case "otp": {
      const res = await ipc.call("phone_inbox_latest", {
        tag: "sms_otp",
        limit: flags.limit ? Number(flags.limit) : 3,
        onlyUnprocessed: Boolean(flags.unprocessed),
        markAsProcessed: Boolean(flags.ack),
      });
      if (flags.copy && res?.items?.[0]?.otpCode && process.env.OMS_DISABLE_OSASCRIPT !== "1") {
        try {
          execFileSync("pbcopy", { input: String(res.items[0].otpCode) });
        } catch {}
      }
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case "inbox": {
      if (flags.web || sub === "web") {
        const st = await ipc.call("phone_presence_status", {});
        const url = st.dashboardUrl || `http://127.0.0.1:${st.lanPort || 19527}/inbox`;
        console.log(`📬 Opening Mac Inbox Dashboard: ${url}`);
        if (process.env.OMS_DISABLE_OSASCRIPT !== "1") {
          execFile("open", [url]);
        }
        break;
      }

      if (sub === "peek") {
        const res = await ipc.call("inbox.peek", {
          ...(positional[1] ? { itemId: positional[1] } : {}),
        });
        console.log(JSON.stringify(res, null, 2));
        break;
      }

      if (sub === "show" && positional[1]) {
        const res = await ipc.call("inbox.get_item", { itemId: positional[1] });
        console.log(JSON.stringify(res, null, 2));
        break;
      }

      if ((sub === "ack" || sub === "mark") && positional[1]) {
        const res = await ipc.call("inbox.mark_processed", {
          itemId: positional[1],
          processed: flags.unprocessed ? false : true,
        });
        console.log(JSON.stringify(res, null, 2));
        break;
      }

      if (sub === "clear-processed") {
        const res = await ipc.call("inbox.clear_processed", {});
        console.log(JSON.stringify(res, null, 2));
        break;
      }

      if ((sub === "transcribe" || sub === "asr") && positional[1]) {
        const res = await ipc.call("inbox.transcribe_item", { itemId: positional[1] }, 65000);
        console.log(JSON.stringify(res, null, 2));
        break;
      }

      const res = await ipc.call("phone_inbox_latest", {
        tag: (flags.tag as string) || "all",
        limit: flags.limit ? Number(flags.limit) : 5,
        onlyUnprocessed: Boolean(flags.unprocessed),
        markAsProcessed: Boolean(flags.ack),
        searchQuery: (flags.q as string) || (flags.query as string) || undefined,
      });
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case "media": {
      const res = await ipc.call("phone_media_recent", {
        mediaType: (flags.type as string) || "screenshot",
        limit: flags.limit ? Number(flags.limit) : 10,
        sinceMinutesAgo: flags.since ? Number(flags.since) : undefined,
      });
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case "pull": {
      let remotePath = positional[0];
      if (
        flags.latest ||
        remotePath === "latest" ||
        remotePath === "latest-screenshot" ||
        remotePath === "latest-camera"
      ) {
        const mediaType =
          remotePath === "latest-camera"
            ? "camera"
            : (flags.type as string) || "screenshot";
        const recent = await ipc.call("phone_media_recent", {
          mediaType,
          limit: 1,
        });
        const first = recent?.items?.[0];
        if (!first?.remotePath) {
          throw new Error(`No recent ${mediaType} found on phone.`);
        }
        remotePath = first.remotePath;
      }
      if (!remotePath) {
        throw new Error(
          "Usage: oms pull <remote-uri-or-path | latest | latest-screenshot | latest-camera> [-o <dir>] [--compress]"
        );
      }
      const res = await ipc.call("phone_file_pull", {
        sourceUriOrPath: remotePath,
        saveToDir: (flags.o as string) || (flags.out as string),
        compressImage: Boolean(flags.compress),
      });
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case "push":
    case "send": {
      const fileFlag = typeof flags.file === "string" ? flags.file : undefined;
      const textFlag = typeof flags.text === "string" ? flags.text : undefined;
      const stdinText = flags.stdin ? (await readStdinText()).trim() : undefined;
      const target = fileFlag || textFlag || stdinText || positional.join(" ");

      if (!target) {
        throw new Error(
          "Usage: oms push <local-file-or-text> | --file <path> | --text <md> | --stdin [--title <t>] [--tts]"
        );
      }
      const isFile = Boolean(fileFlag) || (!textFlag && !stdinText && fs.existsSync(target));
      const res = await ipc.call("phone_file_push", {
        ...(isFile ? { localFilePath: path.resolve(target) } : { markdownOrText: target }),
        title: (flags.title as string) || (isFile ? path.basename(target) : "Mac AI 推送"),
        enableCommuteTts: Boolean(flags.tts),
      });
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case "clip": {
      if (sub === "set") {
        const text =
          (typeof flags.text === "string" ? flags.text : "") ||
          (flags.stdin ? (await readStdinText()).trim() : "") ||
          positional.slice(1).join(" ");
        const res = await ipc.call("phone_clipboard_sync", {
          action: "set_phone_clipboard",
          textToSet: text,
        });
        console.log(JSON.stringify(res, null, 2));
      } else if (sub === "push") {
        const macClip = execFileSync("pbpaste", { encoding: "utf8" });
        const res = await ipc.call("phone_clipboard_sync", {
          action: "set_phone_clipboard",
          textToSet: macClip,
        });
        console.log(JSON.stringify({ ...res, pushedLength: macClip.length }, null, 2));
      } else if (sub === "pull") {
        const res = await ipc.call("phone_clipboard_sync", {
          action: "get_phone_clipboard",
          includeRecentOtp: Boolean(flags.otp),
        });
        if (res?.text && process.env.OMS_DISABLE_OSASCRIPT !== "1") {
          const proc = Bun.spawn(["pbcopy"], { stdin: "pipe" });
          proc.stdin.write(res.text);
          proc.stdin.end();
          await proc.exited;
        }
        console.log(JSON.stringify({ ...res, copiedToMacClipboard: Boolean(res?.text) }, null, 2));
      } else {
        const res = await ipc.call("phone_clipboard_sync", {
          action: "get_phone_clipboard",
          includeRecentOtp: Boolean(flags.otp),
        });
        console.log(JSON.stringify(res, null, 2));
      }
      break;
    }

    case "alarm": {
      const timeStr =
        positional[0] === "set" ? positional[1] || "08:00" : positional[0] || "08:00";
      const [h, m] = timeStr.split(":").map(Number);
      const daysOfWeek =
        typeof flags.days === "string"
          ? flags.days
              .split(",")
              .map((d) => Number(d.trim()))
              .filter((n) => n >= 1 && n <= 7)
          : undefined;
      const res = await ipc.call("phone_alarm_set", {
        hour: h,
        minute: m,
        label: (flags.label as string) || "OneMoreStep AI 提醒",
        daysOfWeek,
        vibrate: flags.vibrate !== "false",
      });
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case "calendar": {
      if (sub === "add") {
        let startIso = flags.start as string | undefined;
        let endIso = flags.end as string | undefined;
        if (startIso && !endIso && flags.duration) {
          const startMs = Date.parse(startIso);
          if (!Number.isNaN(startMs)) {
            endIso = new Date(startMs + Number(flags.duration) * 60 * 1000).toISOString();
          }
        }
        const res = await ipc.call("phone_calendar_manage", {
          action: "add_event",
          title: (flags.title as string) || "AI 日程",
          description: (flags.desc as string) || "",
          location: (flags.location as string) || "",
          startIso,
          endIso,
          reminderMinutesBefore: flags.remind ? Number(flags.remind) : 15,
        });
        console.log(JSON.stringify(res, null, 2));
      } else if (sub === "delete" && (positional[1] || flags.id)) {
        const eventId = Number(positional[1] || flags.id);
        const res = await ipc.call("phone_calendar_manage", {
          action: "delete_event",
          eventId,
        });
        console.log(JSON.stringify(res, null, 2));
      } else {
        const startIso = flags.start as string | undefined;
        let endIso = flags.end as string | undefined;
        if (!endIso && flags.days) {
          const baseMs = startIso ? Date.parse(startIso) : Date.now();
          endIso = new Date(baseMs + Number(flags.days) * 24 * 3600 * 1000).toISOString();
        }
        const res = await ipc.call("phone_calendar_manage", {
          action: "list_events",
          startIso,
          endIso,
        });
        console.log(JSON.stringify(res, null, 2));
      }
      break;
    }

    case "notify": {
      const body = (flags.body as string) || positional.join(" ") || "";
      const speechFlags = resolveCliSpeechFlags(flags);
      const res = await ipc.call("phone_notify", {
        title: (flags.title as string) || "Mac AI 提醒",
        body,
        level: (flags.level as string) || "normal",
        ttsSpeak: Boolean(flags.tts),
        ...speechFlags,
        voice: typeof flags.voice === "string" ? flags.voice : undefined,
        repeat: flags.repeat !== undefined ? Number(flags.repeat) : undefined,
        syncDingtalk: Boolean(flags.dingtalk),
      });
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case "approve": {
      const options = typeof flags.options === "string"
        ? flags.options.split(",").map((s) => s.trim())
        : ["批准继续", "拒绝并中止"];
      const timeoutSec = flags.timeout ? Number(flags.timeout) : 1800;
      const res = await ipc.call(
        "phone_request_approval",
        {
          title: (flags.title as string) || "需要远程确认",
          summary: (flags.summary as string) || positional.join(" "),
          diffOrContext: flags.diff as string,
          options,
          timeoutSec,
        }
      );
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case "gui": {
      if (sub === "observe") {
        const res = await ipc.call("phone_gui_observe", {
          includeScreenshot: Boolean(flags.screenshot),
          somAnnotation: flags.som !== "false",
        });
        console.log(JSON.stringify(res, null, 2));
      } else if (sub === "act") {
        const rawAct = positional[1] || "click";
        const actType = rawAct === "input" ? "input_text" : rawAct;
        const target = positional[2];
        const res = await ipc.call("phone_gui_action", {
          action: actType,
          targetNodeId: target?.startsWith("#") ? target : undefined,
          x: flags.x !== undefined ? Number(flags.x) : undefined,
          y: flags.y !== undefined ? Number(flags.y) : undefined,
          text: (flags.text as string) || (!target?.startsWith("#") ? target : undefined),
          observationId: flags.observation,
          requireUserConfirm: Boolean(flags.confirm),
        });
        console.log(JSON.stringify(res, null, 2));
      }
      break;
    }

    case "ota": {
      const res = await ipc.call("phone_ota_update", {});
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case "wan": {
      const op = (positional[0] || "status").toLowerCase();
      if (op === "test" || op === "probe") {
        const res = await ipc.call("wan.probe", {}, 12000);
        console.log(JSON.stringify(res, null, 2));
      } else if (op === "on" || op === "enable") {
        const res = await ipc.call("wan.configure", { enabled: true });
        console.log(JSON.stringify(res, null, 2));
      } else if (op === "off" || op === "disable") {
        const res = await ipc.call("wan.configure", { enabled: false });
        console.log(JSON.stringify(res, null, 2));
      } else if (op === "set" || op === "config") {
        const res = await ipc.call("wan.configure", {
          enabled: flags.enabled !== undefined ? flags.enabled !== "false" : true,
          wssUrl: typeof flags.url === "string" ? flags.url : positional[1],
          authToken: typeof flags.token === "string" ? flags.token : undefined,
          caCertPath: typeof flags.ca === "string" ? flags.ca : undefined,
        });
        console.log(JSON.stringify(res, null, 2));
      } else {
        const res = await ipc.call("wan.status", {});
        console.log(JSON.stringify(res, null, 2));
      }
      break;
    }

    case "stay-awake":
    case "awake": {
      const op = (positional[0] || "status").toLowerCase();
      if (op === "on" || op === "enable" || op === "true") {
        const res = await ipc.call("host.stay_awake", { enabled: true });
        console.log(JSON.stringify(res, null, 2));
      } else if (op === "off" || op === "disable" || op === "false") {
        const res = await ipc.call("host.stay_awake", { enabled: false });
        console.log(JSON.stringify(res, null, 2));
      } else {
        const res = await ipc.call("host.stay_awake", {});
        console.log(JSON.stringify(res, null, 2));
      }
      break;
    }

    case "unlock": {
      const pattern =
        (typeof flags.pattern === "string" ? flags.pattern : undefined) ||
        positional[0];
      const pin = typeof flags.pin === "string" ? flags.pin : undefined;
      const bounds = typeof flags.bounds === "string" ? flags.bounds : undefined;
      const res = await ipc.call(
        "phone_device_control",
        {
          action: "unlock_screen",
          pattern,
          pin,
          bounds,
          savePattern: Boolean(flags.save),
          savePin: Boolean(flags.save && pin),
          saveBounds: Boolean(flags.save && bounds),
        },
        30000
      );
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case "contacts":
    case "contact": {
      const op = (positional[0] || "list").toLowerCase();
      if (op === "set" || op === "add") {
        const name = (flags.name as string) || positional[1];
        const number = (flags.number as string) || positional[2];
        const res = await ipc.call("phone_device_control", {
          action: "contacts",
          op: "set",
          name,
          number,
          sim: flags.sim !== undefined ? String(flags.sim) : undefined,
          note: typeof flags.note === "string" ? flags.note : undefined,
        });
        console.log(JSON.stringify(res, null, 2));
      } else if (op === "remove" || op === "delete" || op === "rm") {
        const name = (flags.name as string) || positional[1];
        const res = await ipc.call("phone_device_control", {
          action: "contacts",
          op: "remove",
          name,
        });
        console.log(JSON.stringify(res, null, 2));
      } else {
        const res = await ipc.call("phone_device_control", { action: "contacts", op: "list" });
        console.log(JSON.stringify(res, null, 2));
      }
      break;
    }

    case "call":
    case "dial": {
      await executeCliPhoneCall(ipc, flags, positional, 0, cmd === "dial", "oms call");
      break;
    }

    case "hangup": {
      const res = await ipc.call("phone_device_control", { action: "hangup_call" }, 20000);
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case "phone":
    case "control": {
      const actionSub = (sub || "status").toLowerCase();
      if (actionSub === "status" || actionSub === "info") {
        const res = await ipc.call("phone_device_control", { action: "status" });
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "find" || actionSub === "ring") {
        const text =
          (flags.text as string) ||
          positional.slice(1).join(" ") ||
          "正在寻找手机，我在这里！请查看青简咫尺。";
        const res = await ipc.call("phone_device_control", {
          action: "find_phone",
          text,
          voice: typeof flags.voice === "string" ? flags.voice : undefined,
          repeat: flags.repeat !== undefined ? Number(flags.repeat) : undefined,
          saveVoice: Boolean(flags.save),
        });
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "say" || actionSub === "speak" || actionSub === "tts") {
        const text = (flags.text as string) || positional.slice(1).join(" ");
        if (!text) {
          throw new Error(
            "Usage: oms phone say <text> [--force-speaker | --no-alarm] [--voice xiaoyi|xiaoxiao|yunxia|yunxi|yunjian|xiaobei|xiaoni|hsiaochen|hiugaai] [--repeat N] [--save]"
          );
        }
        const speechFlags = resolveCliSpeechFlags(flags);
        const res = await ipc.call("phone_device_control", {
          action: "tts_speak",
          text,
          ...speechFlags,
          voice: typeof flags.voice === "string" ? flags.voice : undefined,
          repeat: flags.repeat !== undefined ? Number(flags.repeat) : undefined,
          saveVoice: Boolean(flags.save),
        });
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "voices" || actionSub === "tts-voices") {
        const res = await ipc.call("phone_device_control", {
          action: "tts_voices",
          voice: (flags.voice as string) || positional[1],
        });
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "stop-tts" || actionSub === "mute-tts") {
        const res = await ipc.call("phone_device_control", { action: "stop_tts" });
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "vibrate") {
        const durationMs = positional[1] ? Number(positional[1]) : Number(flags.ms || 600);
        const res = await ipc.call("phone_device_control", {
          action: "vibrate",
          durationMs,
        });
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "volume" || actionSub === "vol") {
        const val = positional[1] || (flags.level as string) || "up";
        const stream = (flags.stream as string) || "music";
        const isDir = ["up", "down", "mute", "unmute"].includes(String(val).toLowerCase());
        const res = await ipc.call("phone_device_control", {
          action: "set_volume",
          stream,
          ...(isDir
            ? { direction: String(val).toLowerCase() }
            : { volumePct: Number(val) }),
        });
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "screenshot" || actionSub === "shot") {
        const res = await ipc.call("phone_device_control", {
          action: "take_screenshot",
          saveToInbox: flags["no-inbox"] ? false : true,
          title: (flags.title as string) || "📸 CLI 远程抓取手机截屏",
        });
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "open" || actionSub === "url") {
        const url = positional[1] || (flags.url as string);
        if (!url) throw new Error("Usage: oms phone open <url>");
        const res = await ipc.call("phone_device_control", {
          action: "open_url",
          url,
        });
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "launch" || actionSub === "app") {
        const pkg = positional[1] || (flags.app as string);
        if (!pkg) throw new Error("Usage: oms phone launch <wechat|lark|dingtalk|camera|settings|pkg>");
        const res = await ipc.call("phone_device_control", {
          action: "launch_app",
          packageOrAlias: pkg,
        });
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "unlock" || actionSub === "unlock_screen") {
        const pattern =
          (typeof flags.pattern === "string" ? flags.pattern : undefined) ||
          positional[1];
        const pin = typeof flags.pin === "string" ? flags.pin : undefined;
        const bounds = typeof flags.bounds === "string" ? flags.bounds : undefined;
        const res = await ipc.call(
          "phone_device_control",
          {
            action: "unlock_screen",
            pattern,
            pin,
            bounds,
            savePattern: Boolean(flags.save),
            savePin: Boolean(flags.save && pin),
            saveBounds: Boolean(flags.save && bounds),
          },
          30000
        );
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "call" || actionSub === "dial" || actionSub === "call_phone") {
        await executeCliPhoneCall(ipc, flags, positional, 1, actionSub === "dial", "oms phone call");
      } else if (actionSub === "hangup" || actionSub === "end-call" || actionSub === "hangup_call") {
        const res = await ipc.call("phone_device_control", { action: "hangup_call" }, 20000);
        console.log(JSON.stringify(res, null, 2));
      } else if (
        ["key", "home", "back", "recents", "lock", "lock_screen", "notifications"].includes(
          actionSub
        )
      ) {
        const target = actionSub === "key" ? positional[1] || "home" : actionSub;
        const res = await ipc.call("phone_device_control", {
          action: "system_action",
          target,
        });
        console.log(JSON.stringify(res, null, 2));
      } else {
        throw new Error(
          `Unknown phone subcommand: ${actionSub}. Supported: status | find | say | voices | stop-tts | vibrate | volume | screenshot | open | launch | unlock | call | dial | hangup | key | home | back | lock`
        );
      }
      break;
    }

    case "airpods": {
      const actionSub = (sub || "status").toLowerCase();
      const targetMac = (flags.mac as string) || undefined;
      if (actionSub === "status") {
        const res = await ipc.call("airpods.status", { refresh: true, force: Boolean(flags.live) });
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "mac" || actionSub === "to-mac" || actionSub === "connect-mac") {
        const res = await ipc.call("airpods.switch_to_mac", { targetMac }, 35000);
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "phone" || actionSub === "to-phone" || actionSub === "connect-phone") {
        const res = await ipc.call("airpods.switch_to_phone", { targetMac }, 35000);
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "auto" || actionSub === "config") {
        const toggleVal = parseBoolFlag(positional[1] ?? flags.enable ?? flags.auto);
        const pauseVal = parseBoolFlag(flags.pause);
        const res = await ipc.call("airpods.config", {
          autoSwitchAirpods: toggleVal,
          pauseOtherOnAirpodsSwitch: pauseVal,
          preferredAirpodsMac: typeof flags.preferred === "string" ? flags.preferred : undefined,
        });
        console.log(JSON.stringify(res, null, 2));
      } else if (actionSub === "pause") {
        const pauseVal = parseBoolFlag(positional[1] ?? flags.enable);
        const res = await ipc.call("airpods.config", {
          pauseOtherOnAirpodsSwitch: pauseVal ?? true,
        });
        console.log(JSON.stringify(res, null, 2));
      } else {
        throw new Error(
          `Unknown airpods subcommand: ${actionSub}. Supported: status | mac | phone | auto <on|off> | pause <on|off>`
        );
      }
      break;
    }

    case "edge-sync": {
      const res = await ipc.call("edge.sync_now", {}, 20000);
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case "agents":
    case "agent": {
      const op = (positional[0] || "list").toLowerCase();
      if (op === "projects") {
        const res = await ipc.call("agent.sessions.list", { limit: Number(flags.limit || 20) }, 10000);
        console.log(JSON.stringify({ projects: res.projects || [] }, null, 2));
      } else if (op === "quotas" || op === "quota") {
        const res = await ipc.call("agent.quotas", {}, 10000);
        console.log(JSON.stringify(res, null, 2));
      } else if (op === "show" || op === "detail") {
        const backend = (positional[1] || (flags.backend as string) || "codex").toLowerCase();
        const sessionId = positional[2] || (flags.id as string) || positional[1];
        if (!sessionId) throw new Error("Usage: oms agents show <codex|agy> <sessionId>");
        const res = await ipc.call(
          "agent.session.detail",
          {
            backend: backend === "agy" ? "agy" : "codex",
            sessionId,
          },
          10000
        );
        console.log(JSON.stringify(res, null, 2));
      } else if (op === "start" || op === "run") {
        const backend = ((flags.backend as string) || "codex").toLowerCase() === "agy" ? "agy" : "codex";
        const cwd = typeof flags.cwd === "string" ? path.resolve(flags.cwd) : process.cwd();
        const prompt = (typeof flags.prompt === "string" ? flags.prompt : "") || positional.slice(1).join(" ").trim();
        if (!prompt) throw new Error("Usage: oms agents start [--backend codex|agy] [--cwd <dir>] <prompt>");
        const res = await ipc.call("agent.session.start", { backend, cwd, prompt }, 15000);
        console.log(JSON.stringify(res, null, 2));
      } else if (op === "resume" || op === "continue") {
        const backend = (positional[1] || (flags.backend as string) || "codex").toLowerCase() === "agy" ? "agy" : "codex";
        const sessionId = positional[2] || (flags.id as string);
        const prompt = (typeof flags.prompt === "string" ? flags.prompt : "") || positional.slice(3).join(" ").trim();
        if (!sessionId || !prompt) {
          throw new Error("Usage: oms agents resume <codex|agy> <sessionId> <prompt>");
        }
        const res = await ipc.call(
          "agent.session.continue",
          {
            backend,
            sessionId,
            prompt,
            cwd: typeof flags.cwd === "string" ? path.resolve(flags.cwd) : undefined,
          },
          15000
        );
        console.log(JSON.stringify(res, null, 2));
      } else if (op === "cancel" || op === "stop") {
        const backend = (positional[1] || (flags.backend as string) || "codex").toLowerCase() === "agy" ? "agy" : "codex";
        const sessionId = positional[2] || (flags.id as string);
        if (!sessionId) throw new Error("Usage: oms agents cancel <codex|agy> <sessionId>");
        const res = await ipc.call("agent.session.cancel", { backend, sessionId }, 10000);
        console.log(JSON.stringify(res, null, 2));
      } else {
        const res = await ipc.call("agent.sessions.list", { limit: Number(flags.limit || 20) }, 10000);
        console.log(JSON.stringify(res, null, 2));
      }
      break;
    }

    case "wx":
    case "wechat": {
      const wxSub = (sub || "list").toLowerCase();
      if (wxSub === "status") {
        const res = await ipc.call("wechat.status", {});
        console.log(JSON.stringify(res, null, 2));
        break;
      }
      if (wxSub === "peek") {
        const articleId = positional[1] ? Number(positional[1]) : undefined;
        const res = await ipc.call("wechat.peek", {
          ...(articleId && Number.isFinite(articleId) ? { articleId } : {}),
        });
        console.log(JSON.stringify(res, null, 2));
        break;
      }
      if (wxSub === "to-phone" || wxSub === "sync-phone") {
        const articleId = positional[1] ? Number(positional[1]) : Number(flags.article || 0);
        if (!articleId || !Number.isFinite(articleId)) {
          throw new Error("Usage: oms wx to-phone <articleId>");
        }
        const res = await ipc.call("wechat.sync_to_phone", { articleId }, 35000);
        console.log(JSON.stringify(res, null, 2));
        break;
      }
      if (wxSub === "web") {
        const st = await ipc.call("phone_presence_status", { refresh: false });
        const url = `http://127.0.0.1:${st.lanPort || 19527}/inbox?module=wechat`;
        console.log(`💬 Opening WeChat Context Dashboard Tab: ${url}`);
        if (process.env.OMS_DISABLE_OSASCRIPT !== "1") {
          execFile("open", [url]);
        }
        break;
      }
      if (wxSub === "config-web") {
        const st = await ipc.call("phone_presence_status", { refresh: false });
        const url = `http://127.0.0.1:${st.lanPort || 19527}/wechat/config`;
        console.log(`⚙️ Opening WeChat Context Config Workbench: ${url}`);
        if (process.env.OMS_DISABLE_OSASCRIPT !== "1") {
          execFile("open", [url]);
        }
        break;
      }

      const repoRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../../..");
      const wxCliEntry = path.join(repoRoot, "wechat-context", "src", "cli.ts");
      const wechatHome = process.env.OMS_WECHAT_HOME || path.join(homeDir, "wechat");
      const forwardArgs = rawArgs.slice(1);
      const child = spawnSync(
        process.execPath,
        ["run", wxCliEntry, ...(forwardArgs.length > 0 ? forwardArgs : ["list", "--json"])],
        {
          cwd: fs.existsSync(wechatHome) ? wechatHome : repoRoot,
          env: {
            ...process.env,
            OMS_HOME: homeDir,
            OMS_WECHAT_HOME: wechatHome,
            BUN_CONFIG_DNS_TIME_TO_LIVE_SECONDS: "0",
          },
          stdio: "inherit",
        }
      );
      process.exit(child.status ?? 0);
    }

    default:
      printStructuredHelp(cmd);
      break;
  }
}

main().catch((err) => {
  console.error("Error:", err?.message || err);
  process.exit(1);
});
