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
  starting?: boolean;
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
export type IntentTag =
  | "default_inbox"
  | "pkb_vault"
  | "voice_idea"
  | "ui_bug"
  | "sms_otp"
  | "wechat_context";
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
  diagnostics?: { mac?: Record<string, unknown>; phone?: Record<string, unknown> };
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

export type SpeechAudioPolicyName =
  | "force_speaker_alarm"
  | "alarm_respect_headset"
  | "media_commute";

export interface GuiObserveResult {
  observationId?: string;
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

/**
 * Milestone 1 (v2.0): Edge Proxy, Dual-Engine AI, Context Capsule & Zero-Split-Brain WeChat Standby Contracts
 */
export interface EdgeProxyNodePayload {
  name: string;
  protocol: "ss" | "trojan" | "socks5" | "http";
  server: string;
  port: number;
  cipher?: string;
  password?: string;
  username?: string;
  plugin?: string | null;
  pluginOpts?: Record<string, string>;
  sni?: string;
  skipCertVerify?: boolean;
  udp?: boolean;
  source?: string;
}

export interface EdgeProviderPayload {
  providerId: "openai" | "gemini" | "fallback";
  baseUrl: string;
  apiKey: string;
  fastModel: string;
  flagshipModel: string;
  reasoningModel?: string;
  liveModel?: string;
  transcribeModel?: string;
}

export interface EdgeClawBotAuthPayload {
  botToken: string;
  baseUrl: string;
  botId: string;
  userId: string;
}

export interface WanRelayClientConfig {
  enabled: boolean;
  wssUrl: string;
  authToken: string;
  caCertPem?: string;
}

export interface EdgeConfigSyncParams {
  subscriptionUrls: string[];
  bootstrapNodes: EdgeProxyNodePayload[];
  preferRegions: string;
  maxPreferredLatencyMs: number;
  openai?: EdgeProviderPayload;
  gemini?: EdgeProviderPayload;
  domesticFallback?: EdgeProviderPayload;
  clawbotAuth?: EdgeClawBotAuthPayload;
  pairedLanSsidHint?: string;
  wanRelay?: WanRelayClientConfig;
  syncedAtMs: number;
}

export interface EdgeContextCapsuleParams {
  revision: string;
  generatedAtIso: string;
  nowSummary: string;
  queueSummary: string;
  topicsOverview: string;
  analysisBackgroundFormatted: string;
  analysisWarnings: string[];
  matchedCandidates: Array<{
    title: string;
    wikiLink: string;
    excerpt: string;
  }>;
  recentWorkingMemory: string[];
}

export interface WeChatCursorCheckpointParams {
  updatesBuf: string;
  recipientUserId?: string;
  contextToken?: string;
  contextSourceTimeMs?: number;
  macSleeping?: boolean;
  checkpointAtMs: number;
}

export interface WeChatStandbyProcessedArticle {
  messageKey: string;
  senderId: string;
  url: string;
  title: string;
  author: string;
  publishDate: string;
  digest: string;
  userNotes: string;
  englishSlug: string;
  triage: "keep_raw" | "reference" | "process_later" | "process_now";
  extractionQualityStatus: "complete" | "partial" | "metadata_only" | "unavailable";
  rawMarkdownBody: string;
  wechatReplyText: string;
  companionNoteMarkdown: string;
  replyDeliveredToWeChat: boolean;
  modelUsed: string;
  createdAtMs: number;
}

export interface WeChatStandbyHandoffParams {
  handoffId: string;
  latestUpdatesBuf: string;
  recipientUserId?: string;
  latestContextToken?: string;
  contextSourceTimeMs?: number;
  cursorAdvancedByPhone: boolean;
  processedArticles: WeChatStandbyProcessedArticle[];
  deferredMessages: Array<{
    messageKey: string;
    rawPayloadJson: string;
  }>;
  handedOffAtMs: number;
}

export interface WeChatStandbyHandoffResult {
  ok: boolean;
  importedArticlesCount: number;
  queuedDeferredCount: number;
  cursorUpdated: boolean;
}

/**
 * Milestone 2 (v2.0): 3-Tier Mobile Chat Memory Funnel & Vault Promotion Contracts
 */
export interface MobileChatThreadSnapshot {
  threadId: string;
  title: string;
  pinned: boolean;
  incognito: boolean;
  updatedAtMs: number;
  createdAtMs: number;
}

export interface MobileChatMessageSnapshot {
  messageId: string;
  threadId: string;
  role: "user" | "assistant" | "system";
  content: string;
  reasoning?: string;
  modelUsed?: string;
  sourcesJson?: string;
  createdAtMs: number;
}

export interface ChatSyncBatchParams {
  threads: MobileChatThreadSnapshot[];
  messages: MobileChatMessageSnapshot[];
  workingMemoryItems?: string[];
  syncedAtMs: number;
}

export interface MemoryPromoteVaultParams {
  itemId: string;
  kind: "chat_highlight" | "botanical_specimen";
  title: string;
  slugHint?: string;
  markdownContent: string;
  jpegBase64?: string;
  imageFileName?: string;
  createdAtMs: number;
}

export interface MemoryPromoteVaultResult {
  ok: boolean;
  savedNotePath: string;
  savedImagePath?: string;
}

export interface MemorySearchVaultParams {
  query: string;
  limit?: number;
}

export interface MemorySearchVaultResult {
  hits: Array<{
    source: "vault" | "mobile_chat";
    title: string;
    pathOrRef: string;
    snippet: string;
  }>;
}

/**
 * Milestone 3 (v2.0): Mac Heavy-Brain Hub (Codex & Antigravity Live Session Monitor + Dispatch + Resume)
 */
export type AgentBackendType = "codex" | "agy";

export interface AgentSessionSummary {
  sessionId: string;
  backend: AgentBackendType;
  origin: "desktop" | "mobile";
  title: string;
  workspacePath: string;
  projectDisplayName?: string;
  status: "running" | "idle" | "completed" | "failed";
  lastSummary: string;
  updatedAtMs: number;
  canContinue: boolean;
}

export interface AgentProjectItem {
  projectPath: string;
  displayName: string;
  group: string;
  sessionCount: number;
  runningCount: number;
  lastActiveAtMs: number;
}

export interface AgentQuotaWindowItem {
  key: string;
  label: string;
  usedPercent: number;
  remainingPercent: number;
  windowMinutes?: number;
  resetsAtMs?: number;
}

export interface AgentBackendQuotaStatus {
  backend: AgentBackendType;
  available: boolean;
  planLabel: string;
  activeModel?: string;
  windows: AgentQuotaWindowItem[];
  creditsSummary?: string;
  statusNote?: string;
  capturedAtMs: number;
}

export interface AgentQuotasSnapshot {
  codex: AgentBackendQuotaStatus;
  agy: AgentBackendQuotaStatus;
  fetchedAtMs: number;
}

export interface AgentWorkspaceCatalogResult {
  projects: AgentProjectItem[];
  sessions: AgentSessionSummary[];
  quotas?: AgentQuotasSnapshot;
}

export interface AgentStepItem {
  stepIndex: number;
  kind: "user" | "thinking" | "tool" | "assistant" | "status";
  title: string;
  detail: string;
  timestampMs: number;
}

export interface AgentSessionDetailResult {
  session: AgentSessionSummary;
  steps: AgentStepItem[];
}

export interface AgentSessionStartParams {
  clientRequestId: string;
  backend: AgentBackendType;
  prompt: string;
  workspacePath?: string;
  titleHint?: string;
}

export interface AgentSessionContinueParams {
  clientRequestId: string;
  backend: AgentBackendType;
  sessionId: string;
  prompt: string;
  workspacePath?: string;
}

export interface AgentSessionStreamEventParams {
  backend: AgentBackendType;
  sessionId: string;
  status: "running" | "completed" | "failed";
  step?: AgentStepItem;
  finalSummary?: string;
  updatedAtMs: number;
}
