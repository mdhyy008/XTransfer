import type {
  AppConfig,
  BleState,
  Conversation,
  DeviceInfo,
  Peer,
  RelayState,
  ServiceState,
  Settings,
  Snapshot
} from '../shared/types'
import { PUBLIC_CONVERSATION_ID } from '../shared/types'
import type { Db } from './db'

// 广播每秒一次，但 Wi-Fi 省电会把广播攒着送，丢两三个包很常见；
// 窗口留到 12 秒才不会因为一次抖动在在线/离线之间反复跳。
export const ONLINE_WINDOW_SEC = 12

type Presence = 'online' | 'unknown' | 'offline'

export class AppState {
  info: DeviceInfo | null = null
  service: ServiceState = { running: false, base: '', tls: true, foreign: false }
  settings: Settings = {
    theme: 'system',
    seed: 'green',
    notifications: true,
    publicNotifications: false,
    closeToTray: true,
    quickPreview: true,
    enterToSend: true,
    misans: true,
    bleEnabled: false,
    webChat: false,
    relayUrl: '',
    blocked: [],
    aliases: {},
    muted: [],
    pinned: [],
    hidden: {},
    speedPeaks: {}
  }
  config: AppConfig = { deviceName: '', recvDir: '', autostart: false }
  bluetooth: BleState = {
    supported: process.platform === 'win32',
    on: false,
    scanning: false,
    advertising: false,
    devices: []
  }
  relay: RelayState = {
    configured: false,
    connected: false,
    connecting: false,
    address: '',
    deviceId: '',
    rooms: [],
    members: {}
  }
  peers = new Map<string, Peer>()
  private lastPresence = new Map<string, Presence>()
  // 服务会把上次运行留下的 last_seen 一起带出来，早于这个时刻的心跳不算本次确认过。
  private presenceEpoch = Date.now() / 1000
  private lanPresent = false
  onChange: (snapshot: Snapshot) => void = () => {}

  constructor(private readonly db: Db) {}

  setSettings(settings: Settings): void {
    this.settings = settings
    this.emit()
  }

  setConfig(config: Partial<AppConfig>): void {
    this.config = { ...this.config, ...config }
    this.emit()
  }

  setInfo(info: DeviceInfo | null): void {
    this.info = info
    this.emit()
  }

  setService(patch: Partial<ServiceState>): void {
    this.service = { ...this.service, ...patch }
    this.emit()
  }

  setBle(patch: Partial<BleState>): void {
    this.bluetooth = { ...this.bluetooth, ...patch }
    this.emit()
  }

  setRelay(relay: RelayState): void {
    this.relay = relay
    this.emit()
  }

  markServiceStarted(): void {
    this.presenceEpoch = Date.now() / 1000
    this.emit()
  }

  setLan(present: boolean): void {
    if (this.lanPresent === present) return
    this.lanPresent = present
    this.emit()
  }

  upsertPeer(peer: Peer): void {
    if (!peer?.id) return
    if (this.applyPeer(peer)) this.emit()
  }

  markPeerOffline(id: string): void {
    const peer = this.peers.get(id)
    if (!peer || peer.last_seen === 0) return
    this.peers.set(id, { ...peer, last_seen: 0 })
    this.emit()
  }

  upsertPeers(list: Peer[]): void {
    let changed = false
    for (const peer of list) {
      if (peer?.id && this.applyPeer(peer)) changed = true
    }
    if (changed) this.emit()
  }

  // 设备静默消失时不会有任何事件，靠定时重算把在线状态翻过来，变了才推送界面。
  refreshOnline(): void {
    const nowSec = Date.now() / 1000
    for (const peer of this.peers.values()) {
      if (this.lastPresence.get(peer.id) !== this.presenceOf(peer, nowSec)) {
        this.emit()
        return
      }
    }
  }

  // 上次运行留下的心跳不能直接当在线：本次启动后还没再听到的设备，
  // 有局域网时先显示“未知”，没有局域网就直接算离线。
  private presenceOf(peer: Peer, nowSec: number): Presence {
    const lastSeen = peer.last_seen || 0
    if (nowSec - lastSeen > ONLINE_WINDOW_SEC) return 'offline'
    if (lastSeen >= this.presenceEpoch) return 'online'
    return this.lanPresent ? 'unknown' : 'offline'
  }

  private applyPeer(peer: Peer): boolean {
    const old = this.peers.get(peer.id)
    const next = { ...old, ...peer }
    this.peers.set(peer.id, next)
    const nowSec = Date.now() / 1000
    // 心跳只刷新 last_seen；在线状态没变时不必整包重推，否则每 3 秒全界面重绘一次。
    const wasPresence = old ? this.presenceOf(old, nowSec) : 'offline'
    const isPresence = this.presenceOf(next, nowSec)
    return (
      !old ||
      old.name !== next.name ||
      old.addr !== next.addr ||
      old.port !== next.port ||
      old.platform !== next.platform ||
      old.trusted !== next.trusted ||
      wasPresence !== isPresence
    )
  }

  snapshot(): Snapshot {
    const merged = new Map<string, Conversation>()
    for (const conv of this.db.listConversations()) merged.set(conv.id, conv)

    const nowSec = Date.now() / 1000
    const presenceNow = new Map<string, Presence>()
    const hidden = this.settings.hidden ?? {}
    for (const peer of this.peers.values()) {
      // 用户删除过的设备，在被重新发现之前不再出现。
      const hiddenAt = hidden[peer.id] ?? 0
      if (hiddenAt > 0 && (peer.last_seen || 0) <= hiddenAt) continue
      const presence = this.presenceOf(peer, nowSec)
      presenceNow.set(peer.id, presence)
      const online = presence === 'online'
      const unknown = presence === 'unknown'
      const existing = merged.get(peer.id)
      if (existing) {
        existing.online = online
        existing.unknown = unknown
        existing.name = peer.name || existing.name
        existing.platform = peer.platform ?? existing.platform
        continue
      }
      merged.set(peer.id, {
        id: peer.id,
        name: peer.name || peer.id,
        platform: peer.platform,
        online,
        unknown,
        last_message_at: 0,
        last_read_at: 0,
        unread: 0,
        created_at: 0
      })
    }

    this.lastPresence = presenceNow

    const pub = merged.get(PUBLIC_CONVERSATION_ID)
    if (pub) {
      pub.online = true
      pub.unknown = false
    }

    const blocked = new Set(this.settings.blocked ?? [])
    const pinned = new Set(this.settings.pinned ?? [])
    const aliases = this.settings.aliases ?? {}
    const muted = new Set(this.settings.muted ?? [])
    const conversations = [...merged.values()]
      .filter((conv) => conv.id === PUBLIC_CONVERSATION_ID || !blocked.has(conv.id))
      .map((conv) => {
        // 免打扰的设备不显示未读数。
        const base = muted.has(conv.id) ? { ...conv, unread: 0 } : conv
        const alias = aliases[base.id]
        return alias ? { ...base, name: alias, originalName: base.name } : base
      })
      .sort((a, b) => {
        if (a.id === PUBLIC_CONVERSATION_ID) return -1
        if (b.id === PUBLIC_CONVERSATION_ID) return 1
        if (pinned.has(a.id) !== pinned.has(b.id)) return pinned.has(a.id) ? -1 : 1
        return (b.last_message_at || 0) - (a.last_message_at || 0)
      })

    return {
      info: this.info,
      service: this.service,
      settings: this.settings,
      config: this.config,
      peers: [...this.peers.values()],
      conversations,
      messages: this.db.listMessages(),
      bluetooth: this.bluetooth,
      relay: this.relay,
      at: Date.now()
    }
  }

  emit(): void {
    this.onChange(this.snapshot())
  }
}
