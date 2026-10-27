import { expect, test } from "bun:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { ConnectionDiagnostics } from "../src/transport/connection-diagnostics.js";

test("diagnostic history is bounded, survives restart and projects only safe fields", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "oms-connection-log-"));
  const file = path.join(root, "connections.jsonl");
  const log = new ConnectionDiagnostics(file);
  for (let i = 0; i < 220; i++) log.record("CLOSED", "LAN_WS", i.toString(16).padStart(12, "0"), 1006);
  expect(log.snapshot()).toHaveLength(200);
  const last = log.snapshot().at(-1)!;
  fs.appendFileSync(file, JSON.stringify({ ...last, secret: "PRIVATE_PAYLOAD" }) + "\n");
  const loaded = new ConnectionDiagnostics(file);
  expect(loaded.snapshot()).toHaveLength(200);
  expect(JSON.stringify(loaded.snapshot())).not.toContain("PRIVATE_PAYLOAD");
  loaded.record("OPENED", "LAN_WS", "aaaaaaaaaaaa");
  expect(fs.readFileSync(file, "utf8")).not.toContain("PRIVATE_PAYLOAD");
  expect(fs.statSync(file).size).toBeLessThan(65536);
});

test("invalid remote fields and storage errors cannot disrupt connection diagnostics", () => {
  const log = new ConnectionDiagnostics(os.tmpdir());
  log.record("CLOSED", "LAN_WS", "token=DO_NOT_LOG");
  expect(log.snapshot()).toHaveLength(0);
  log.record("CLOSED", "LAN_WS", "aaaaaaaaaaaa", 1006);
  expect(log.snapshot()).toHaveLength(1);
  expect(log.persistenceAvailable).toBe(false);
});
