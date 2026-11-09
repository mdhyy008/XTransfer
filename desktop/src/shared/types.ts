export const PUBLIC_CONVERSATION_ID = '__public__'

export type Peer = {
  id: string
  name: string
  addr?: string
  port?: number
  platform?: string
  last_seen: number
  trusted: boolean
}

export type ItemKind = 'text' | 'file' | 'folder'

export type Channel = 'dm' | 'public' | 'ble'

export type Item = {
  id: string
  kind: ItemKind
  from_id: string
  from_name?: string
  platform?: string
  to_id: string
  channel: Channel
  body?: string
  name?: string
  size?: number
  sha256?: string
  status: string
  path?: string
  duplicate?: boolean
  created_at: number
}

export type LanInfo = {
  name: string
  cidr: string
}

export type HotspotStatus = {
  supported: boolean
  running: boolean
  ssid: string
  password: string
  message?: string
}

export type DeviceInfo = {
  id: string
  name: string
  platform?: string
  version?: string
  port?: number
  uptime_sec?: number
  auto_accept?: boolean
  tls?: boolean
  fingerprint?: string
}

export type Conversation = {
  id: string
  name: string
  platform?: string
  online: boolean
  last_message_at: number
  last_read_at: number
  unread: number
  created_at: number
  originalName?: string
}

export type MessageStatus =
  | 'pending'
  | 'queued'
  | 'accepted'
  | 'active'
  | 'sending'
  | 'done'
  | 'duplicate'
  | 'failed'
  | 'canceled'
  | 'peer_canceled'
  | 'rejected'

export type Message = {
  id: string
  conversation_id: string
  direction: 'in' | 'out'
  kind: ItemKind
  channel: Channel
  from_id: string
  from_name?: string
  body?: string
  file_name?: string
  file_size?: number
  local_path?: string
  status: MessageStatus
  received: number
  total: number
  speed_bps: number
  error_code?: string
  created_at: number
  updated_at: number
}

export type ServiceState = {
  running: boolean
  starting?: boolean
  base: string
  tls: boolean
  foreign: boolean
  warning?: string
}

export type BleDevice = {
  id: string
  name: string
  platform?: string
  address?: string
  rssi: number
  lastSeen: number
  connected?: boolean
  connecting?: boolean
}

export type BleState = {
  supported: boolean
  on: boolean
  scanning: boolean
  advertising: boolean
  devices: BleDevice[]
  error?: string
  version?: string
  address?: string
}

export type WebChatStatus = {
  running: boolean
  url: string
  port: number
  online: number
  error?: string
}

export type SeedColor = 'purple' | 'blue' | 'green' | 'orange'
export type ThemeMode = 'system' | 'light' | 'dark'

export type Settings = {
  theme: ThemeMode
  seed: SeedColor
  notifications: boolean
  publicNotifications: boolean
  closeToTray: boolean
  quickPreview: boolean
  enterToSend: boolean
  misans: boolean
  bleEnabled: boolean
  webChat: boolean
  blocked: string[]
  aliases: Record<string, string>
  muted: string[]
  clipboardPeers: string[]
  pinned: string[]
  // 已删除的设备 → 删除时刻（秒）。设备再次被发现（last_seen 更新）后自然重新出现。
  hidden: Record<string, number>
}

export type AppConfig = {
  deviceName: string
  recvDir: string
  autostart: boolean
}

export type DoctorCheck = {
  name: string
  ok: boolean
  detail?: string
}

export type DoctorResult = {
  ok: boolean
  checks: DoctorCheck[]
}

export type Snapshot = {
  info: DeviceInfo | null
  service: ServiceState
  settings: Settings
  config: AppConfig
  peers: Peer[]
  conversations: Conversation[]
  messages: Message[]
  bluetooth: BleState
  at: number
}

export type SendTextReq = {
  to: string
  body: string
}

export type SendTextResp = {
  ok: boolean
  channel?: string
  acked?: string[]
  degraded?: boolean
  warn?: string
}

export type DecisionReq = {
  id: string
  action: 'accept' | 'reject' | 'cancel'
}

export type FileEntry = {
  path: string
  name: string
  kind: 'file' | 'folder'
  size: number
  exists: boolean
  mtime?: number
}

export type SendFilesReq = {
  to: string
  paths: string[]
}

export type RetryFileReq = {
  to: string
  id: string
}

export type SpeedTestResult = {
  peak: number
  avg: number
  bytes: number
  seconds: number
  samples: number[]
}

export type SpeedTestProgress = {
  id: string
  sample: number
  index: number
}

export type ShareStatus = {
  running: boolean
  url: string
  count: number
  bytes: number
  error?: string
}

export type XtApi = {
  getState: () => Promise<Snapshot>
  onState: (cb: (s: Snapshot) => void) => () => void
  onMessages: (cb: (messages: Message[]) => void) => () => void
  onClipboardCopied: (cb: (text: string) => void) => () => void
  scan: () => Promise<Snapshot>
  sendText: (req: SendTextReq) => Promise<SendTextResp>
  retryText: (id: string) => Promise<void>
  decide: (req: DecisionReq) => Promise<void>
  markRead: (convId: string) => Promise<void>
  clearConversation: (convId: string) => Promise<void>
  deleteConversation: (convId: string) => Promise<void>
  deleteOfflineDevices: () => Promise<number>
  deleteMessage: (id: string) => Promise<void>
  revealPath: (path: string) => Promise<void>
  openPath: (path: string) => Promise<void>
  previewFile: (path: string) => Promise<void>
  fileThumb: (path: string) => Promise<string | null>
  pathExists: (path: string) => Promise<boolean>
  pathSize: (path: string) => Promise<number>
  deleteFile: (path: string) => Promise<void>
  saveFileAs: (source: string, name: string) => Promise<boolean>
  pickFiles: () => Promise<FileEntry[]>
  pickImages: () => Promise<FileEntry[]>
  pickApks: () => Promise<FileEntry[]>
  pickFolders: () => Promise<FileEntry[]>
  pickMedia: () => Promise<FileEntry[]>
  pickFolder: () => Promise<FileEntry | null>
  inspectPaths: (paths: string[]) => Promise<FileEntry[]>
  listDir: (dir: string) => Promise<FileEntry[]>
  renamePath: (path: string, name: string) => Promise<string>
  trashPath: (path: string) => Promise<void>
  onFilesChanged: (cb: () => void) => () => void
  pathForFile: (file: File) => string
  onBucketAdd: (cb: (entries: FileEntry[]) => void) => () => void
  padDrag: (active: boolean) => void
  padReceive: (req: { paths: string[]; open: boolean }) => Promise<{ count: number }>
  sendFiles: (req: SendFilesReq) => Promise<string>
  captureScreenshot: () => Promise<FileEntry | null>
  cancelSend: (messageId: string) => Promise<void>
  retryFile: (req: RetryFileReq) => Promise<void>
  onNavigate: (cb: (convId: string) => void) => () => void
  onUiAction: (cb: (action: string) => void) => () => void
  onLanChange: (cb: (info: LanInfo | null) => void) => () => void
  updateSettings: (patch: Partial<Settings>) => Promise<void>
  setDeviceName: (name: string) => Promise<void>
  pickRecvDir: () => Promise<string | null>
  resetRecvDir: () => Promise<string>
  setAutostart: (enabled: boolean) => Promise<void>
  setTrust: (id: string, trusted: boolean) => Promise<void>
  speedTest: (peerId: string) => Promise<SpeedTestResult>
  onSpeedProgress: (cb: (progress: SpeedTestProgress) => void) => () => void
  shareStart: (entries: FileEntry[]) => Promise<ShareStatus>
  shareStop: () => Promise<ShareStatus>
  shareStatus: () => Promise<ShareStatus>
  restartService: () => Promise<void>
  cacheSize: () => Promise<{ total: number }>
  cleanCache: (
    preservePaths?: string[]
  ) => Promise<{ parts: number; freedBytes: number; records: number }>
  runDoctor: () => Promise<DoctorResult>
  lanInfo: () => Promise<LanInfo | null>
  onHotspotChange: (cb: (status: HotspotStatus) => void) => () => void
  hotspotStatus: () => Promise<HotspotStatus>
  hotspotStart: () => Promise<HotspotStatus>
  hotspotStop: () => Promise<HotspotStatus>
  openHotspotSettings: () => Promise<void>
  bleStart: () => Promise<BleState>
  bleStop: () => Promise<BleState>
  bleConnect: (peerId: string) => Promise<void>
  webChatStatus: () => Promise<WebChatStatus>
  webChatStart: () => Promise<WebChatStatus>
  webChatStop: () => Promise<WebChatStatus>
  syncPublic: () => Promise<void>
  minimize: () => void
  toggleMaximize: () => void
  close: () => void
  version: () => Promise<{ app: string; xlan: string }>
  openExternal: (url: string) => Promise<void>
}
