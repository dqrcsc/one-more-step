import { describe, expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { generateUuidV7 } from "@oms/shared-protocol";
import { OmsDaemon } from "../src/daemon/oms-daemon.js";

describe("Delayed Unlock SMS OTP Deduplication on Mac Host", () => {
  test("suppresses duplicate OTP submitted minutes later on phone unlock while returning valid outbox ACK", async () => {
    const oldDisable = process.env.OMS_DISABLE_OSASCRIPT;
    process.env.OMS_DISABLE_OSASCRIPT = "1";
    const tmpHome = fs.mkdtempSync(path.join(os.tmpdir(), "oms-otp-dedup-"));
    fs.writeFileSync(
      path.join(tmpHome, "config.json"),
      JSON.stringify({ enableBluetoothBridge: false, dingtalk: { enabled: false } }),
      "utf8"
    );

    const daemon = new OmsDaemon(tmpHome);
    let notificationsShown = 0;
    (daemon as any).showMacDesktopNotification = () => {
      notificationsShown += 1;
    };

    const submit = (params: Record<string, unknown>) =>
      new Promise<any>((resolve) => {
        daemon.session.emit(
          "phoneRequest",
          { jsonrpc: "2.0", id: "req", method: "inbox.submitItem", params },
          resolve,
          { tier: "LAN_WS", connectionId: "conn_1" }
        );
      });

    try {
      const t0 = Date.now() - 30_000;
      const firstId = generateUuidV7();
      const firstRes = await submit({
        clientItemId: firstId,
        intentTag: "sms_otp",
        title: "🔑 【小红书】验证码: 192268",
        textContent: "【小红书】您的验证码是: 192268， 5分钟内有效。请勿向他人泄漏。如非本人操作，可忽略本消息。",
        otpCode: "192268",
        otpSender: "小红书",
        sourceApp: "com.android.mms",
        createdAtMs: t0,
      });
      expect(firstRes.result.ack).toBe(true);
      expect(firstRes.result.savedId).toBe(firstId);
      expect(notificationsShown).toBe(1);

      // 9m20s later when phone unlocks: new clientItemId + truncated leading ellipsis "…"
      const secondId = generateUuidV7();
      const secondRes = await submit({
        clientItemId: secondId,
        intentTag: "sms_otp",
        title: "🔑 【小红书】验证码: 192268",
        textContent: "…您的验证码是: 192268， 5分钟内有效。请勿向他人泄漏。如非本人操作，可忽略本消息。",
        otpCode: "192268",
        otpSender: "小红书",
        sourceApp: "com.android.mms",
        createdAtMs: t0 + 560_000,
      });
      // Must return savedId === secondId so Android outbox marks it DELIVERED without retrying,
      // while suppressing duplicate inbox storage and duplicate Mac desktop notifications.
      expect(secondRes.result.ack).toBe(true);
      expect(secondRes.result.deduplicated).toBe(true);
      expect(secondRes.result.savedId).toBe(secondId);
      expect(notificationsShown).toBe(1);
      expect(daemon.inbox.queryLatest({ tag: "sms_otp", limit: 10 }).length).toBe(1);

      // A genuinely independent OTP from another service ("飞书") 3 minutes later still goes through
      const thirdId = generateUuidV7();
      const thirdRes = await submit({
        clientItemId: thirdId,
        intentTag: "sms_otp",
        title: "🔑 【飞书】验证码: 192268",
        textContent: "【飞书】验证码：192268，有效期10分钟。如非本人操作，请忽略。",
        otpCode: "192268",
        otpSender: "飞书",
        sourceApp: "com.ss.android.lark",
        createdAtMs: Date.now(),
      });
      expect(thirdRes.result.ack).toBe(true);
      expect(thirdRes.result.deduplicated).toBeUndefined();
      expect(notificationsShown).toBe(2);
      expect(daemon.inbox.queryLatest({ tag: "sms_otp", limit: 10 }).length).toBe(2);
    } finally {
      await daemon.stop();
      if (oldDisable === undefined) delete process.env.OMS_DISABLE_OSASCRIPT;
      else process.env.OMS_DISABLE_OSASCRIPT = oldDisable;
    }
  });
});
