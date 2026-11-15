import { expect, test } from "bun:test";
import { PhoneSessionManager } from "../src/transport/session-manager.js";

for (const outcome of ["timeout", "ERROR", "disconnect", "COMPLETED", "sendFailure"] as const) {
  test(`stream ${outcome} during backpressure is observed, propagated and cleaned up`, async () => {
    // Run the production transfer/ACK/close handlers without opening transports.
    const manager: any = Object.create(PhoneSessionManager.prototype);
    const pipe = { tier: "LAN_WS", authenticated: true, closed: false, getBufferedAmount: () => 3 * 1024 * 1024 };
    let sent = 0;
    const failure = new Error("fixture synchronous send failure");
    Object.assign(manager, {
      activeTier: "LAN_WS", pipes: new Map([["LAN_WS", pipe]]), openPipes: new Set([pipe]),
      outgoingStreams: new Map(), incomingStreams: new Map(), pendingRpc: new Map(),
      outQueue: { size: 0 }, isLivePipe: () => !pipe.closed, recomputeActiveTier: () => {},
      sendSecure: () => { if (outcome === "sendFailure") throw failure; sent++; },
    });
    const unhandled: unknown[] = [];
    const observe = (err: unknown) => { unhandled.push(err); };
    process.on("unhandledRejection", observe);
    try {
      const transfer = manager.pushStreamToPhone({ fileName: "fixture.bin", mimeType: "application/octet-stream",
        data: new Uint8Array(131072), timeoutMs: outcome === "timeout" ? 5 : 1000 });
      const result = transfer.then((value: any) => ({ value }), (error: Error) => ({ error }));
      if (outcome !== "timeout" && outcome !== "sendFailure") {
        setTimeout(() => {
          const streamId = manager.outgoingStreams.keys().next().value;
          if (outcome === "disconnect") manager.onPipeClosed(pipe);
          else manager.handleStreamAck(pipe, { streamId, status: outcome, errorMessage: "fixture remote error" });
        }, 2);
      }
      const settled = await result;
      await Bun.sleep(25);
      expect(unhandled).toEqual([]);
      expect(manager.outgoingStreams.size).toBe(0);
      expect(sent).toBe(outcome === "sendFailure" ? 0 : 2); // header + first chunk only
      if (outcome === "COMPLETED") expect(settled.value.streamId).toBeTruthy();
      else expect(settled.error?.message).toContain({ timeout: "acknowledgement timed out", ERROR: "fixture remote error",
        disconnect: "Transport closed", sendFailure: failure.message }[outcome]);
    } finally {
      process.off("unhandledRejection", observe);
      for (const pending of manager.outgoingStreams.values()) clearTimeout(pending.timer);
    }
  });
}
