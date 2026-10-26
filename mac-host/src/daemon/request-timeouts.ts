export function approvalTimeoutSec(value: unknown = 1800): number {
  const seconds = Number(value);
  if (!Number.isInteger(seconds) || seconds < 1 || seconds > 3600) {
    throw new Error("Approval timeout must be an integer between 1 and 3600 seconds");
  }
  return seconds;
}

export function ipcTimeoutForAction(action: string, params: Record<string, any>): number {
  if (action === "phone_request_approval") return (approvalTimeoutSec(params.timeoutSec ?? 1800) + 20) * 1000;
  if (action === "phone_device_control" && params.action === "system_action") return 70000;
  if (action === "phone_gui_action" || action === "phone_file_push") return 70000;
  if (action === "phone_gui_observe" || action === "phone_file_pull" || action === "phone_device_control") return 45000;
  return 30000;
}
