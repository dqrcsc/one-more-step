/**
 * Module 01: Shared Protocol Definitions for OneMoreStep (Mac <-> Xiaomi Android)
 */

export const OMS_MAGIC_BYTE = 0x4f; // 'O'
export const OMS_PROTOCOL_VERSION = 0x01;
export const OMS_DEFAULT_LAN_PORT = 19527;
export const OMS_BLE_SERVICE_SHORT_UUID = "FD9A";
export const OMS_BLE_SERVICE_UUID = "0000fd9a-0000-1000-8000-00805f9b34fb";
export const OMS_RFCOMM_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";

export enum FrameType {
  CONTROL_JSON_RPC = 0x01,
  STREAM_START = 0x02,
  STREAM_CHUNK = 0x03,
  STREAM_ACK_RESUME = 0x04,
  PING = 0x05,
  PONG = 0x06,
  SCREEN_FRAME = 0x07,
  SECURE_RECORD = 0x7e,
}

export enum ScreenFrameCodec {
  STATUS_ONLY = 0x00,
  WEBP = 0x01,
  JPEG = 0x02,
  H264_ANNEXB = 0x10,
}

export enum ScreenFrameFlags {
  NONE = 0x00,
  IS_SECURE_WINDOW = 0x01,
  IS_SCREEN_OFF = 0x02,
  IS_SCREEN_LOCKED = 0x04,
  IS_KEY_FRAME = 0x08,
}

export interface ScreenFrameHeader {
  codec: ScreenFrameCodec | number;
  flags: number;
  width: number; // physical phone screen width in px
  height: number; // physical phone screen height in px
  seq: number; // uint16 wrapping sequence number
  timestampMs: number;
}

export interface DecodedScreenFrame extends ScreenFrameHeader {
  data: Uint8Array;
}

export interface MirrorStatusSnapshot {
  running: boolean;
  mode: "native_stream" | "legacy_poll" | "stopped";
  codec: string;
  fps: number;
  seq: number;
  width: number;
  height: number;
  isSecureWindow: boolean;
  isScreenOff: boolean;
  isScreenLocked: boolean;
  foregroundPackage?: string;
  foregroundAppLabel?: string;
  lastFrameAtMs: number;
  clientCount: number;
  maxWidth: number;
  quality: number;
  maxFps: number;
}

export enum FramePriority {
  P0_URGENT_CONTROL = 0,
  P1_STREAM_META = 1,
  P2_BULK_DATA = 2,
}

export enum OmsErrorCode {
  ERR_PERMISSION_MISSING = -32001,
  ERR_SCOPED_PATH_DENIED = -32002,
  ERR_ACCESSIBILITY_DISABLED = -32003,
  ERR_SECURE_WINDOW_BLOCKED = -32004,
  ERR_USER_INTERRUPTED = -32005,
  ERR_APPROVAL_TIMEOUT = -32006,
  ERR_DEVICE_OFFLINE = -32007,
}

export type TransportTier = "LAN_WS" | "BT_RFCOMM" | "WAN_WEBRTC" | "DISCONNECTED";
export type PresenceState = "AT_DESK" | "AWAY";
export type IntentTag = "default_inbox" | "pkb_vault" | "voice_idea" | "ui_bug" | "sms_otp";
export type NotificationLevel = "silent" | "normal" | "urgent";

export interface JsonRpcRequest<TParams = unknown> {
  jsonrpc: "2.0";
  id?: string;
  method: string;
  params?: TParams;
}

export interface JsonRpcError {
  code: number;
  message: string;
  data?: unknown;
}

export interface JsonRpcResponse<TResult = unknown> {
  jsonrpc: "2.0";
  id: string;
  result?: TResult;
  error?: JsonRpcError;
}

export type JsonRpcMessage = JsonRpcRequest | JsonRpcResponse;

/**
 * Binary File / Media Streaming Headers (FrameType 0x02 & 0x04)
 */
export interface StreamStartHeader {
  streamId: string; // 16-byte UUID string (UUIDv7)
  itemId?: string;
  fileName: string;
  mimeType: string;
  totalBytes: number;
  chunkSize: number;
  totalChunks: number;
  sha256: string;
  intentTag?: IntentTag;
  title?: string;
  enableCommuteTts?: boolean;
}

export interface StreamAckResumeHeader {
  streamId: string;
  status: "PROGRESS" | "COMPLETED" | "RESUME_REQUEST" | "ERROR";
  nextExpectedChunkIndex: number;
  savedPath?: string;
  errorMessage?: string;
}

/**
 * Pairing & Mutual Authentication Payloads
 */
export interface PairingQrPayload {
  v: number;
  id: string;
  name: string;
  ips: string[];
  port: number;
  btUuid: string;
  pubKeyBase64Url: string;
  pinCode: string;
  ephemeralToken: string;
  expiresAtMs: number;
}

export interface PairRequestParams {
  phoneId: string;
  phoneModel: string;
  phoneName: string;
  phonePubKeyBase64Url: string;
  pinCode: string;
  hmacHex: string;
  timestampMs: number;
}

export interface PairResponseResult {
  ok: boolean;
  macId: string;
  macName: string;
  macPubKeyBase64Url: string;
  wanRoomId: string;
  macSignatureBase64Url: string;
  pairSecretHex?: string;
}

export interface HelloChallengeParams {
  macId: string;
  macPubKeyBase64Url?: string;
  nonceMacHex: string;
  timestampMs: number;
}

export interface AuthResponseParams {
  phoneId: string;
  noncePhoneHex: string;
  phoneSignatureBase64Url: string;
  deviceSnapshot: PhoneDeviceStatus;
}

export interface AuthAckResult {
  ok: boolean;
  macId?: string;
  macSignatureBase64Url: string;
  macAuthHmacHex?: string;
  pairSecretHex?: string;
}

/**
 * Host -> Phone RPC Method Contracts
 */
export interface PhonePermissionStatus {
  xiaomiBackgroundPopupAllowed: boolean; // OpCode 10021
  xiaomiLockScreenDisplayAllowed: boolean; // OpCode 10008
  xiaomiNotificationSmsAllowed?: boolean; // OpCode 10018 (MIUI/HyperOS 读取通知类/验证码短信)
  batteryOptimizationIgnored: boolean;
  overlayAllowed: boolean;
  accessibilityEnabled: boolean;
  notificationListenerEnabled?: boolean; // 通知使用权 (实时监听短信/App验证码通知)
  smsReadGranted?: boolean; // RECEIVE_SMS & READ_SMS 权限
  calendarGranted: boolean;
  mediaReadGranted: boolean;
  recordAudioGranted?: boolean;
}

export interface PhoneDeviceStatus {
  batteryPct: number;
  isCharging: boolean;
  chargePlug?: string;
  temperatureC?: number;
  voltageMv?: number;
  powerSaveMode?: boolean;
  wifiSsid?: string;
  wifiRssiDbm?: number;
  wifiLinkSpeedMbps?: number;
  bluetoothEnabled?: boolean;
  activeTransportTier?: string;
  lanIps: string[];
  foregroundPackage?: string;
  foregroundActivity?: string;
  foregroundAppLabel?: string;
  screenLocked: boolean;
  screenInteractive?: boolean;
  screenBrightness?: number;
  ringerMode?: string;
  volumeMusicPct?: number;
  volumeRingPct?: number;
  volumeNotificationPct?: number;
  volumeAlarmPct?: number;
  storageTotalGb?: number;
  storageAvailableGb?: number;
  storageUsedPct?: number;
  ramTotalMb?: number;
  ramAvailableMb?: number;
  ramUsedPct?: number;
  deviceBrand?: string;
  deviceManufacturer?: string;
  deviceModel?: string;
  androidVersion?: string;
  sdkInt?: number;
  romVersion?: string;
  appVersionName?: string;
  secureProtocolVersion?: number;
  appVersionCode?: number;
  uptimeMinutes?: number;
  pendingOutboxCount?: number;
  updatedAtMs?: number;
  airpodsAutoSwitchEnabled?: boolean;
  airpodsConnected?: boolean;
  airpodsName?: string;
  airpodsMac?: string;
  pairedAirpods?: Array<{ name: string; mac: string; connected: boolean }>;
  mediaPlaying?: boolean;
  audioModeInCall?: boolean;
  permissions: PhonePermissionStatus;
}

export interface PairedAirPodsDevice {
  name: string;
  mac: string;
  connected: boolean;
}

export interface AirPodsStatusSnapshot {
  autoSwitchEnabled: boolean;
  pauseOtherOnSwitch: boolean;
  preferredAirpodsMac?: string;
  activeOwner: "MAC" | "PHONE" | "NONE";
  activeDeviceName?: string;
  activeDeviceMac?: string;
  mac: {
    connected: boolean;
    isDefaultOutput: boolean;
    audioRunning: boolean;
    defaultOutputName?: string;
    deviceName?: string;
    deviceMac?: string;
    pairedAirpods: PairedAirPodsDevice[];
  };
  phone: {
    connected: boolean;
    mediaPlaying: boolean;
    inCall: boolean;
    deviceName?: string;
    deviceMac?: string;
    pairedAirpods: PairedAirPodsDevice[];
  };
  lastSwitchEvent?: {
    direction: "MAC_TO_PHONE" | "PHONE_TO_MAC";
    reason: string;
    deviceName?: string;
    deviceMac?: string;
    atMs: number;
  };
}

export interface MediaItemMeta {
  mediaId: string;
  fileName: string;
  remotePath: string;
  mimeType: string;
  sizeBytes: number;
  width?: number;
  height?: number;
  createdAtMs: number;
}

export interface SemanticNode {
  id: string; // e.g., "#1", "#2"
  role: string;
  label: string;
  text?: string;
  clickable: boolean;
  editable: boolean;
  scrollable: boolean;
  checkable?: boolean;
  checked?: boolean;
  selected?: boolean;
  bounds: [number, number, number, number]; // [left, top, right, bottom]
  center: [number, number]; // [cx, cy]
}

export interface GuiObserveResult {
  packageName: string;
  activityName: string;
  isSecureWindow: boolean;
  screenWidth: number;
  screenHeight: number;
  nodes: SemanticNode[];
  screenshotStreamId?: string;
  screenshotLocalAbsPath?: string;
}

/**
 * Phone -> Host RPC Method Contracts
 */
export interface InboxSubmitItemParams {
  clientItemId: string; // UUIDv7 for idempotent deduplication
  intentTag: IntentTag;
  title?: string;
  textContent?: string;
  voiceTranscript?: string;
  sourceApp?: string;
  otpCode?: string;
  otpSender?: string;
  createdAtMs: number;
  attachments?: Array<{
    streamId: string;
    fileName: string;
    mimeType: string;
    totalBytes: number;
    sha256: string;
  }>;
}

export interface ApprovalRespondParams {
  approvalId: string;
  approved: boolean;
  selectedOption: string;
  userComment?: string;
  decidedAtMs: number;
}
