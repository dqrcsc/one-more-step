import { execFile } from "node:child_process";

/** Keeps local post-answer effects inside the original phone authorization. */
export class CallContinuation {
  private readonly controller = new AbortController();
  private readonly timer: ReturnType<typeof setInterval>;
  readonly signal = this.controller.signal;

  constructor(readonly deadlineMs: number, private readonly assertAuthorized: () => void) {
    this.check();
    this.timer = setInterval(() => {
      try { this.check(); } catch { /* check aborts the player and pending delay */ }
    }, 50);
  }

  check(): void {
    if (this.signal.aborted) throw this.signal.reason;
    try {
      if (Date.now() >= this.deadlineMs) throw new Error("ERR_GUI_DEADLINE: Call continuation expired");
      this.assertAuthorized();
    } catch (error) {
      this.controller.abort(error);
      throw error;
    }
  }

  async pause(ms: number): Promise<void> {
    this.check();
    await new Promise<void>((resolve, reject) => {
      const cleanup = () => { clearTimeout(timer); this.signal.removeEventListener("abort", onAbort); };
      const onAbort = () => { cleanup(); reject(this.signal.reason); };
      const timer = setTimeout(() => { cleanup(); resolve(); }, Math.min(ms, this.deadlineMs - Date.now()));
      this.signal.addEventListener("abort", onAbort, { once: true });
    });
    this.check();
  }

  dispose(): void { clearInterval(this.timer); }

  cancel(error: Error): void { this.controller.abort(error); }
}

/** Wait for close as well as cancellation: an aborted playback must leave no player behind. */
export function runCallAudioProcess(command: string, args: string[], continuation: CallContinuation): Promise<void> {
  continuation.check();
  return new Promise<void>((resolve, reject) => {
    let completionError: Error | null = null;
    const child = execFile(command, args, {
      timeout: Math.max(1, Math.min(45000, continuation.deadlineMs - Date.now())),
      signal: continuation.signal,
      killSignal: "SIGKILL",
    }, (err) => { completionError = err; });
    child.once("error", (err) => { completionError = err; });
    child.once("close", () => {
      if (continuation.signal.aborted) reject(continuation.signal.reason);
      else if (completionError) reject(completionError);
      else resolve();
    });
  });
}
