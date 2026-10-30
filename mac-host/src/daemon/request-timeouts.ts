export function approvalTimeoutSec(value: unknown = 1800): number {
  const seconds = Number(value);
  if (!Number.isInteger(seconds) || seconds < 1 || seconds > 3600) {
    throw new Error("Approval timeout must be an integer between 1 and 3600 seconds");
  }
  return seconds;
}

/** A caller's shorter deadline survives every hop; malformed/expired actions never dispatch. */
export function boundedControlDeadline(requested: unknown, maximumMs: number, now = Date.now()): number {
  if (requested !== undefined && (typeof requested !== "number" || !Number.isSafeInteger(requested) || requested <= now)) {
    throw new Error("ERR_GUI_DEADLINE: Invalid or expired action deadline");
  }
  return requested === undefined ? now + maximumMs : Math.min(requested as number, now + maximumMs);
}

export function ipcTimeoutForAction(action: string, params: Record<string, any>): number {
  if (action === "airpods.switch_to_mac" || action === "airpods.switch_to_phone") return 35000;
  if (action === "phone_request_approval") return (approvalTimeoutSec(params.timeoutSec ?? 1800) + 20) * 1000;
  if (
    action === "phone_device_control" &&
    (params.action === "call_phone" || params.action === "call") &&
    Boolean(params.say || params.sayText || params.ttsText)
  ) {
    return 95000;
  }
  if (action === "phone_device_control" && params.action === "system_action") return 70000;
  if (action === "phone_gui_action" || action === "phone_file_push") return 70000;
  if (action === "phone_gui_observe" || action === "phone_file_pull" || action === "phone_device_control") return 45000;
  return 30000;
}
