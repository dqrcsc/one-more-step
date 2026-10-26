#!/usr/bin/env bun
import { OmsDaemon } from "./oms-daemon.js";

const daemon = new OmsDaemon(process.env.OMS_HOME);

async function run() {
  await daemon.start();
  console.log(`[omsd] OneMoreStep Host Daemon listening on IPC ${daemon.ipcSocketPath}`);

  const shutdown = async () => {
    await daemon.stop();
    process.exit(0);
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

run().catch((err) => {
  console.error("[omsd] Fatal error:", err);
  process.exit(1);
});
