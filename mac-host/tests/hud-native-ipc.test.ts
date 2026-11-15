import { expect, test } from "bun:test";
import { execFileSync, spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { MacHudServer } from "../src/hud/mac-hud-server.js";
import { InboxManager } from "../src/inbox/inbox-manager.js";
import { generateUuidV7 } from "@oms/shared-protocol";

test.skipIf(process.platform !== "darwin")("native HUD sends while reading, confirms persisted state, reconnects and stops", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-hud-native-"));
  const source = fs.readFileSync(path.resolve(import.meta.dir, "../mac-hud/main.swift"), "utf8");
  // Compile the actual models, write helper and IPC implementation without AppKit.
  const models = source.slice(source.indexOf("struct HudStoredFile"), source.indexOf("// MARK: - Helpers"));
  const writer = source.slice(source.indexOf("private func writeAllFD"), source.indexOf("private func formatByteSize"));
  const client = source.slice(source.indexOf("final class HudIpcClient"), source.indexOf("// MARK: - Main HUD Application Controller"));
  const swiftFile = path.join(dir, "main.swift");
  const binary = path.join(dir, "ipc-test");
  fs.writeFileSync(swiftFile, `import Foundation\nimport Darwin\n${models}\n${writer}\n${client}\n
let client = HudIpcClient(socketPath: CommandLine.arguments[1])
var phase = 0
var finished = false
client.onEnvelope = { env in
    if env.type == "sync_items", let item = env.items?.first {
        if phase == 0 && !item.processed {
            phase = 1
            for _ in 0..<100 { client.sendJson(["type": "mark_processed", "itemId": item.itemId]) }
        } else if phase == 1 && item.processed {
            phase = 2
            print("PERSISTED_CONFIRMED")
            client.sendJson(["type": "request_sync"])
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                client.sendJson(["type": "request_sync"])
            }
        }
    } else if env.type == "finished" {
        client.stop()
        client.sendJson(["type": "mark_processed", "itemId": "must-not-send-after-stop"])
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { finished = true }
    }
}
client.sendJson(["type": "request_sync"])
client.start()
client.start() // idempotent: no concurrent readers or duplicate connection
let deadline = Date().addingTimeInterval(8)
while !finished && Date() < deadline { RunLoop.main.run(until: Date().addingTimeInterval(0.01)) }
client.stop()
print(finished ? "STOPPED" : "TIMED_OUT")
exit(finished ? 0 : 1)
`);
  execFileSync("swiftc", [swiftFile, "-o", binary], { timeout: 30000 });
  const inboxDir = path.join(dir, "inbox");
  const inbox = new InboxManager(inboxDir);
  const itemId = generateUuidV7();
  inbox.storeSubmittedItem({ clientItemId: itemId, intentTag: "pkb_vault", title: "Fixture", textContent: "fixture",
    createdAtMs: Date.now(), attachments: [] }, () => undefined);
  const server = new MacHudServer({ socketPath: path.join(dir, "hud.sock"), autoSpawnBinary: false });
  let marks = 0, syncs = 0, connections = 0;
  let wrongId = false;
  server.on("clientConnected", () => connections++);
  const sync = () => server.syncState([inbox.getItem(itemId)!], { activeTier: "DISCONNECTED", inboxDir });
  server.on("markProcessed", (id: string) => {
    wrongId ||= id !== itemId;
    if (++marks === 1) inbox.markProcessed(id, true);
    if (marks === 100) sync();
  });
  server.on("requestSync", () => {
    if (++syncs === 1) sync();
    else if (syncs === 2) (server as any).activeClient.destroy();
    else (server as any).sendEnvelope({ type: "finished" });
  });
  await server.start();
  const child = spawn(binary, [path.join(dir, "hud.sock")], { stdio: ["ignore", "pipe", "pipe"] });
  let output = "", stderr = "";
  child.stdout.on("data", data => output += data);
  child.stderr.on("data", data => stderr += data);
  try {
    const code = await new Promise<number | null>((resolve, reject) => {
      child.on("error", reject); child.on("close", resolve);
    });
    expect(stderr).toBe("");
    expect(code).toBe(0);
    expect(output).toContain("PERSISTED_CONFIRMED");
    expect(output).toContain("STOPPED");
    expect(marks).toBe(100);
    expect(syncs).toBe(3);
    expect(connections).toBe(2);
    expect(wrongId).toBe(false);
    expect(new InboxManager(inboxDir).getItem(itemId)?.processed).toBe(true);
  } finally {
    child.kill();
    await server.stop();
  }
}, 45000);
