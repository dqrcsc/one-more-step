import { test, expect } from "bun:test";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

test.skipIf(process.platform !== "darwin")("native AirPods operation blocks expired/cancelled effects and late successes", () => {
  const root = path.resolve(import.meta.dir, "..");
  const output = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "oms-airpods-native-")), "test");
  execFileSync("swiftc", [path.join(root, "bluetooth-bridge/AirPodsOperation.swift"),
    path.join(root, "tests/swift/AirPodsOperationTests.swift"), "-o", output], { timeout: 30000 });
  expect(execFileSync(output, { encoding: "utf8", timeout: 5000 })).toContain("HCI units passed");
}, 35000);


test.skipIf(process.platform !== "darwin")("native AirPods worker reaps a stalled client and a fresh request still completes", () => {
  const root = path.resolve(import.meta.dir, "..");
  const output = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "oms-airpods-worker-")), "test");
  execFileSync("swiftc", [path.join(root, "bluetooth-bridge/AirPodsNativeRequest.swift"),
    path.join(root, "tests/swift/AirPodsNativeRequestTests.swift"), "-o", output], { timeout: 30000 });
  expect(execFileSync(output, { encoding: "utf8", timeout: 10000 })).toContain("fresh request succeeds");
}, 45000);
