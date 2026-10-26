import { PhoneSessionManager } from "../src/transport/session-manager.js";

// Tests use simulated phones on loopback; never advertise to or discover real devices.
(PhoneSessionManager.prototype as any).startLanDiscoveryAndHousekeeping = function () {};
const originalServe = Bun.serve.bind(Bun);
Bun.serve = ((options: any) => originalServe({ ...options, hostname: "127.0.0.1" })) as typeof Bun.serve;
process.env.OMS_DISABLE_OSASCRIPT = "1";
