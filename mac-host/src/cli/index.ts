#!/usr/bin/env bun
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { execFile, execFileSync } from "node:child_process";
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

async function readStdinText(): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of process.stdin) {
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function main() {
  const rawArgs = process.argv.slice(2);
  const cmd = rawArgs[0] || "help";
  const sub = rawArgs[1];
  const { positional, flags } = parseFlags(rawArgs.slice(1));
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
      const lines = flags.lines ? Number(flags.lines) : 50;
      if (!fs.existsSync(logFile)) {
        console.log(`No daemon log file yet at ${logFile}`);
        return;
      }
      const content = fs.readFileSync(logFile, "utf8").split("\n");
      console.log(content.slice(-lines).join("\n"));
      return;
    }

    if (sub === "stop" || sub === "restart") {
      if (fs.existsSync(pidFile)) {
        const pid = Number(fs.readFileSync(pidFile, "utf8").trim());
        if (pid > 0) {
          try {
            process.kill(pid, "SIGTERM");
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
      await new Promise((r) => setTimeout(r, 300));
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
            `   Mode: ${mirrorRes.mode} (${mirrorRes.targetMaxWidth || 720}px @ ${mirrorRes.targetMaxFps || 12}fps)`
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
      const res = await ipc.call("phone_notify", {
        title: (flags.title as string) || "Mac AI 提醒",
        body,
        level: (flags.level as string) || "normal",
        ttsSpeak: Boolean(flags.tts),
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
      const number = (flags.number as string) || positional[0];
      if (!number) {
        throw new Error(
          "Usage: oms call <name-or-number> [--sim 1|2] [--say <text> | --mac-say <text> | --ai-call <text>] [--voice <v>] [--repeat <N>] [--hangup-after] [--wait-answer <sec>] [--dial-only] [--speaker|--no-speaker]"
        );
      }
      const sayText =
        typeof flags.say === "string"
          ? flags.say
          : typeof flags.tts === "string"
            ? flags.tts
            : typeof flags.speak === "string"
              ? flags.speak
              : positional.length > 1
                ? positional.slice(1).join(" ")
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
          dialOnly: cmd === "dial" || Boolean(flags["dial-only"]),
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
            "Usage: oms phone say <text> [--alarm] [--voice xiaoyi|xiaoxiao|yunxia|yunxi|xiaobei|hsiaochen] [--repeat N] [--save]"
          );
        }
        const res = await ipc.call("phone_device_control", {
          action: "tts_speak",
          text,
          useAlarmStream: Boolean(flags.alarm),
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
        const number = (flags.number as string) || positional[1];
        if (!number) {
          throw new Error(
            "Usage: oms phone call <name-or-number> [--sim 1|2] [--say <text> | --mac-say <text> | --ai-call <text>] [--voice <v>] [--repeat <N>] [--hangup-after] [--wait-answer <sec>] [--dial-only] [--speaker|--no-speaker]"
          );
        }
        const sayText =
          typeof flags.say === "string"
            ? flags.say
            : typeof flags.tts === "string"
              ? flags.tts
              : typeof flags.speak === "string"
                ? flags.speak
                : positional.length > 2
                  ? positional.slice(2).join(" ")
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
            dialOnly: actionSub === "dial" || Boolean(flags["dial-only"]),
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

    default:
      console.log(`OneMoreStep CLI (oms) - Mac AI <-> Xiaomi Android Bidirectional Bridge
Commands:
  oms status [--live]                                  Inspect live presence, transport tier, battery, temp, audio, storage & permissions
  oms airpods [status|mac|phone|auto <on|off>|pause <on|off>]
                                                       Seamless AirPods auto-switch between Mac & Android on audio output + media pause
  oms phone <status|find|say|voices|stop-tts|volume|screenshot|open|launch|unlock|call|hangup|home|back|lock>
                                                       Inspect rich phone telemetry or remotely control phone from Mac
  oms phone find [text] [--voice <v>] [--repeat <N>] [--save]
                                                       Ring/speak at max alarm volume with 9 Neural TTS human voices (e.g. xiaoyi, yunxia, hsiaochen)
  oms phone say <text> [--voice <v>] [--repeat <N>] [--save]
                                                       Speak text on phone via Neural TTS (--voice xiaoyi|xiaoxiao|yunxia|yunxi|yunjian|xiaobei|xiaoni|hsiaochen|hiugaai)
  oms phone voices                                     List all 9 Neural TTS human voices & current default voice
  oms phone stop-tts                                   Stop active Find-My-Phone or TTS voice playback on phone
  oms phone unlock [pattern] [--save] [--bounds l,t,r,b] Wake screen, swipe up bouncer & draw 3x3 pattern (1~9) to unlock phone
  oms phone call <number> [--sim 1|2|电信|联通] [--say <text>] [--voice <v>] [--repeat <N>] [--hangup-after] [--speaker]
                                                       Auto-unlock, place phone call, wait for answer & speak with Neural TTS voice
  oms phone hangup                                     Hang up / end active or dialing phone call on Xiaomi phone
  oms pair [--json]                                    Generate v2 pairing URI & local wireless install URL (/install)
  oms web                                              Open Mac real-time Inbox & Stream Web Dashboard (/inbox)
  oms mirror [open|start|stop|status] [--fps 12]       Open real-time Phone Screen Mirror & remote control studio (/mirror)
  oms otp [--limit N] [--copy] [--ack]                 Query latest synced verification codes (OTP) from phone
  oms prefs [--auto-otp] [--auto-copy] [--tts-voice <v>] [--unlock-pattern <1~9>]
                                                       View or update Mac auto-OTP, clipboard, preview, default TTS voice & unlock pattern
  oms inbox [latest] [--tag <tag>] [--unprocessed]     Query items sent from phone (--ack to mark read, --q <keyword>)
  oms inbox show <id> | ack <id> | transcribe <id>     Inspect item, mark processed, or run SenseVoice offline ASR
  oms media [recent] [--type screenshot|camera]        List recent screenshots/photos/videos on phone (--since <min>)
  oms pull <remote-path | latest-screenshot> [-o dir]  Pull authorized file or latest screenshot/photo from phone to Mac
  oms push <file-or-text> [--title <t>] [--tts]        Push file/Markdown/text to phone stream (--file, --text, --stdin)
  oms clip [get [--otp] | set <text> | push | pull]    Bidirectional clipboard sync between Mac and Xiaomi phone
  oms alarm [set] <HH:MM> [--label <t>] [--days 1,2]   Set native Xiaomi system clock alarm
  oms calendar [list [--days N] | add --title <t>]     Query conflict-free schedule or insert phone calendar event
  oms notify <text> [--level urgent] [--tts] [--voice <v>] [--repeat <N>]
                                                       Send graded notification (L1-L3) or alarm-stream Neural TTS voice
  oms approve --title <t> --summary <s> [--diff <d>]   Push blocking remote approval card to phone and wait for decision
  oms gui observe [--screenshot] [--som]               Extract compact Accessibility semantic tree (#1, #2) & SoM screenshot
  oms gui act <click|input_text|...> [#id] --observation <id> [--confirm] Perform GUI automation action on phone with safety guardrails
  oms ota                                              Push latest APK update to phone over LAN
  oms daemon [status|start|stop|restart|logs]          Manage background omsd daemon
  oms mcp                                              Start stdio MCP server for Codex/Antigravity/OpenCode`);
      break;
  }
}

main().catch((err) => {
  console.error("Error:", err?.message || err);
  process.exit(1);
});
