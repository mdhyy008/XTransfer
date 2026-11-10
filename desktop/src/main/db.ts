import path from 'node:path'
import Database from 'better-sqlite3'
import type {
  Conversation,
  Item,
  Message,
  MessageStatus,
  Peer
} from '../shared/types'
import { PUBLIC_CONVERSATION_ID } from '../shared/types'

const BLE_PREFIX = 'ble:'

const SCHEMA = `
CREATE TABLE IF NOT EXISTS conversations (
  peer_id         TEXT PRIMARY KEY,
  peer_name       TEXT NOT NULL,
  platform        TEXT,
  last_message_at INTEGER NOT NULL DEFAULT 0,
  last_read_at    INTEGER NOT NULL DEFAULT 0,
  created_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS messages (
  id              TEXT PRIMARY KEY,
  conversation_id TEXT NOT NULL,
  direction       TEXT NOT NULL,
  kind            TEXT NOT NULL,
  channel         TEXT NOT NULL,
  from_id         TEXT NOT NULL DEFAULT '',
  from_name       TEXT,
  body            TEXT,
  file_name       TEXT,
  file_size       INTEGER,
  local_path      TEXT,
  status          TEXT NOT NULL,
  received        INTEGER DEFAULT 0,
  total           INTEGER DEFAULT 0,
  speed_bps       INTEGER DEFAULT 0,
  error_code      TEXT,
  xlan_item_id    TEXT,
  offer_id        TEXT,
  relay_file_id   TEXT,
  recalled        INTEGER NOT NULL DEFAULT 0,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conversation_id, created_at DESC);
`

function now(): number {
  return Math.floor(Date.now() / 1000)
}

function mapStatus(status: string | undefined): MessageStatus {
  switch (status) {
    case 'queued':
    case 'pending':
    case 'active':
    case 'done':
    case 'duplicate':
    case 'failed':
    case 'canceled':
    case 'rejected':
    case 'peer_canceled':
    case 'sending':
    case 'accepted':
      return status
    case 'expired':
      return 'failed'
    default:
      return 'done'
  }
}

// 接收目录里已有同名同内容的文件时，服务端直接标成已完成并带上 duplicate，
// 界面上要和真正传完的文件区分开。
function itemStatus(item: Item): MessageStatus {
  const status = mapStatus(item.status)
  return item.duplicate && status === 'done' ? 'duplicate' : status
}

export class Db {
  private readonly db: Database.Database

  constructor(dir: string) {
    this.db = new Database(path.join(dir, 'xt.db'))
    this.db.pragma('journal_mode = WAL')
    this.db.exec(SCHEMA)
    const columns = this.db.prepare('PRAGMA table_info(messages)').all() as { name: string }[]
    if (!columns.some((column) => column.name === 'recalled')) {
      this.db.exec('ALTER TABLE messages ADD COLUMN recalled INTEGER NOT NULL DEFAULT 0')
    }
    if (!columns.some((column) => column.name === 'relay_file_id')) {
      this.db.exec('ALTER TABLE messages ADD COLUMN relay_file_id TEXT')
    }
    this.ensureConversation(PUBLIC_CONVERSATION_ID, '局域网广播', undefined)
  }

  close(): void {
    this.db.close()
  }

  ensureConversation(id: string, name: string, platform?: string): void {
    if (!id) return
    this.db
      .prepare(
        `INSERT INTO conversations (peer_id, peer_name, platform, created_at)
         VALUES (?, ?, ?, ?)
         ON CONFLICT(peer_id) DO UPDATE SET
           peer_name = CASE WHEN excluded.peer_name <> '' THEN excluded.peer_name ELSE conversations.peer_name END,
           platform = COALESCE(excluded.platform, conversations.platform)`
      )
      .run(id, name, platform ?? null, now())
  }

  updatePeerMeta(peers: Peer[]): void {
    const stmt = this.db.prepare(
      `UPDATE conversations SET peer_name = ?, platform = COALESCE(?, platform) WHERE peer_id = ?`
    )
    for (const peer of peers) {
      if (!peer?.id || peer.id === PUBLIC_CONVERSATION_ID) continue
      stmt.run(peer.name || peer.id, peer.platform ?? null, peer.id)
    }
  }

  ingestItems(items: Item[], selfId: string): void {
    for (const item of items) this.ingestItem(item, selfId)
  }

  purgeSelfConversation(selfId: string): void {
    if (!selfId) return
    this.db.prepare('DELETE FROM messages WHERE conversation_id = ?').run(selfId)
    this.db.prepare('DELETE FROM conversations WHERE peer_id = ?').run(selfId)
  }

  ingestItem(item: Item, selfId: string): void {
    if (!item?.id) return
    // 自测是发给自己，不是和某台设备的会话。
    if (item.channel !== 'public' && item.from_id === selfId && item.to_id === selfId) return
    const channel = item.channel === 'public' ? 'public' : 'dm'
    const mine = item.from_id === selfId
    const convId =
      channel === 'public'
        ? PUBLIC_CONVERSATION_ID
        : mine
          ? item.to_id
          : item.from_id
    if (!convId) return

    const direction = mine ? 'out' : 'in'
    const existing = this.db
      .prepare('SELECT id, status FROM messages WHERE id = ?')
      .get(item.id) as { id: string; status: string } | undefined

    if (existing) {
      let status: MessageStatus = itemStatus(item)
      // 本地已取消/拒绝的，晚到的失败状态不要覆盖。
      if (
        status === 'failed' &&
        (existing.status === 'canceled' ||
          existing.status === 'rejected' ||
          existing.status === 'peer_canceled')
      ) {
        status = existing.status as MessageStatus
      }
      this.db
        .prepare(
          `UPDATE messages SET status = ?, local_path = COALESCE(?, local_path),
             file_name = COALESCE(?, file_name), file_size = COALESCE(?, file_size), updated_at = ?
           WHERE id = ?`
        )
        .run(
          status,
          item.path ?? null,
          item.name ?? null,
          item.size ?? null,
          now(),
          item.id
        )
      return
    }

    if (convId !== PUBLIC_CONVERSATION_ID) {
      const name = direction === 'in' ? item.from_name || item.from_id : item.to_id
      this.ensureConversation(convId, name)
    }

    const createdAt = item.created_at || now()

    // 服务端回显自己的公共消息时，先删掉本地乐观插入的那条，避免重复气泡。
    if (channel === 'public' && mine && item.body) {
      const local = this.findRecentOutText(PUBLIC_CONVERSATION_ID, item.body, createdAt - 30)
      if (local) this.db.prepare('DELETE FROM messages WHERE id = ?').run(local.id)
    }

    this.db
      .prepare(
        `INSERT INTO messages (id, conversation_id, direction, kind, channel, from_id, from_name,
           body, file_name, file_size, local_path, status, received, total, speed_bps, error_code,
           xlan_item_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, NULL, ?, ?, ?)`
      )
      .run(
        item.id,
        convId,
        direction,
        item.kind,
        channel,
        item.from_id,
        item.from_name ?? null,
        item.body ?? null,
        item.name ?? null,
        item.size ?? null,
        item.path ?? null,
        itemStatus(item),
        item.size ?? 0,
        item.id,
        createdAt,
        now()
      )

    this.touchConversation(convId, createdAt)
  }

  applyItemUpdate(data: {
    id?: string
    status?: string
    reason?: string
    path?: string
    name?: string
    size?: number
  }): void {
    if (!data?.id) return
    let status: MessageStatus | null = data.status ? mapStatus(data.status) : null
    // 对端退出/取消导致的失败，对用户来说就是"已取消"，别显示成"失败"。
    if (status === 'failed' && (data.reason === 'stale' || data.reason === 'expired')) {
      status = 'canceled'
    }
    // 发送方撤回了这批文件，接收方要看出是对方取消的。
    if (status === 'failed' && data.reason === 'peer_canceled') {
      status = 'peer_canceled'
    }
    // 本地已经取消/拒绝的，晚到的失败通知不要把它改回失败。
    if (status === 'failed' || status === 'peer_canceled') {
      const cur = this.db.prepare('SELECT status FROM messages WHERE id = ?').get(data.id) as
        | { status: string }
        | undefined
      if (cur && (cur.status === 'canceled' || cur.status === 'rejected')) status = null
    }
    this.db
      .prepare(
        `UPDATE messages SET status = COALESCE(?, status), local_path = COALESCE(?, local_path),
           file_name = COALESCE(?, file_name), file_size = COALESCE(?, file_size), updated_at = ?
         WHERE id = ?`
      )
      .run(
        status,
        data.path ?? null,
        data.name ?? null,
        data.size ?? null,
        now(),
        data.id
      )
  }

  // failUnfinishedOutbound 把上次进程退出时没结束的发送标记为失败。
  failUnfinishedOutbound(): number {
    const res = this.db
      .prepare(
        `UPDATE messages SET status = 'failed', error_code = COALESCE(error_code, 'INTERRUPTED'),
           updated_at = ? WHERE direction = 'out' AND status IN ('queued', 'sending', 'accepted', 'active')`
      )
      .run(now())
    return res.changes
  }

  setProgress(id: string, received: number, total: number, speedBps: number): void {
    if (!id) return
    this.db
      .prepare(
        `UPDATE messages SET received = ?, total = ?, speed_bps = ?, status = CASE
           WHEN status IN ('pending', 'done', 'failed', 'canceled', 'rejected', 'peer_canceled')
             THEN status
           ELSE 'active' END, updated_at = ?
         WHERE id = ?`
      )
      .run(
        Math.max(0, Math.floor(received)),
        total > 0 ? Math.floor(total) : 0,
        Math.max(0, Math.floor(speedBps)),
        now(),
        id
      )
  }

  addOutboundText(
    convId: string,
    body: string,
    id: string,
    selfId: string,
    selfName: string,
    peerName?: string
  ): void {
    const channel =
      convId === PUBLIC_CONVERSATION_ID ? 'public' : convId.startsWith(BLE_PREFIX) ? 'ble' : 'dm'
    if (convId !== PUBLIC_CONVERSATION_ID) {
      this.ensureConversation(convId, peerName || convId)
    }
    const ts = now()
    this.db
      .prepare(
        `INSERT INTO messages (id, conversation_id, direction, kind, channel, from_id, from_name,
           body, status, created_at, updated_at)
         VALUES (?, ?, 'out', 'text', ?, ?, ?, ?, 'sending', ?, ?)`
      )
      .run(id, convId, channel, selfId, selfName, body, ts, ts)
    this.touchConversation(convId, ts)
  }

  // 蓝牙帧自带 id，直接拿它当主键去重：同一条消息重发进来不会再多出一个气泡。
  addInboundBluetooth(params: {
    id: string
    fromId: string
    fromName: string
    platform?: string
    body: string
    ts: number
  }): boolean {
    if (!params.id || !params.fromId) return false
    const convId = `${BLE_PREFIX}${params.fromId}`
    this.ensureConversation(convId, params.fromName || params.fromId, params.platform)
    const nowSec = now()
    // 对方时钟可能偏快，消息时间不晚于本机现在。
    const ts = params.ts > 0 ? Math.min(Math.floor(params.ts), nowSec) : nowSec
    const res = this.db
      .prepare(
        `INSERT OR IGNORE INTO messages (id, conversation_id, direction, kind, channel, from_id, from_name,
           body, status, created_at, updated_at)
         VALUES (?, ?, 'in', 'text', 'ble', ?, ?, ?, 'done', ?, ?)`
      )
      .run(
        params.id,
        convId,
        params.fromId,
        params.fromName || null,
        params.body,
        ts,
        nowSec
      )
    if (res.changes === 0) return false
    this.touchConversation(convId, ts)
    return true
  }

  addOutboundBluetoothFile(params: {
    convId: string
    peerName: string
    id: string
    sourcePath: string
    fileName: string
    size: number
    selfId: string
    selfName: string
  }): void {
    this.ensureConversation(params.convId, params.peerName || params.convId)
    const ts = now()
    this.db
      .prepare(
        `INSERT OR IGNORE INTO messages (id, conversation_id, direction, kind, channel, from_id, from_name,
           file_name, file_size, local_path, status, received, total, created_at, updated_at)
         VALUES (?, ?, 'out', 'file', 'ble', ?, ?, ?, ?, ?, 'sending', 0, ?, ?, ?)`
      )
      .run(
        params.id,
        params.convId,
        params.selfId,
        params.selfName,
        params.fileName,
        params.size,
        params.sourcePath,
        params.size,
        ts,
        ts
      )
    this.touchConversation(params.convId, ts)
  }

  addInboundBluetoothFile(params: {
    id: string
    fromId: string
    fromName: string
    platform?: string
    name: string
    size: number
    ts: number
  }): boolean {
    if (!params.id || !params.fromId) return false
    const convId = `${BLE_PREFIX}${params.fromId}`
    this.ensureConversation(convId, params.fromName || params.fromId, params.platform)
    const nowSec = now()
    const ts = params.ts > 0 ? Math.min(Math.floor(params.ts), nowSec) : nowSec
    const res = this.db
      .prepare(
        `INSERT OR IGNORE INTO messages (id, conversation_id, direction, kind, channel, from_id, from_name,
           file_name, file_size, status, received, total, created_at, updated_at)
         VALUES (?, ?, 'in', 'file', 'ble', ?, ?, ?, ?, 'sending', 0, ?, ?, ?)`
      )
      .run(
        params.id,
        convId,
        params.fromId,
        params.fromName || null,
        params.name,
        params.size,
        params.size,
        ts,
        nowSec
      )
    if (res.changes === 0) return false
    this.touchConversation(convId, ts)
    return true
  }

  finishBluetoothFile(params: {
    id: string
    path?: string
    name?: string
    size?: number
  }): void {
    this.db
      .prepare(
        `UPDATE messages SET status = 'done', local_path = COALESCE(?, local_path),
           file_name = COALESCE(?, file_name), file_size = COALESCE(?, file_size),
           received = COALESCE(?, total), total = COALESCE(?, total), updated_at = ?
         WHERE id = ?`
      )
      .run(
        params.path ?? null,
        params.name ?? null,
        params.size ?? null,
        params.size ?? null,
        params.size ?? null,
        now(),
        params.id
      )
  }

  addOutboundFile(params: {
    convId: string
    peerName: string
    id: string
    sourcePath: string
    fileName: string
    kind: 'file' | 'folder'
    size: number
    selfId: string
    selfName: string
  }): void {
    this.ensureConversation(params.convId, params.peerName || params.convId)
    const ts = now()
    this.db
      .prepare(
        `INSERT INTO messages (id, conversation_id, direction, kind, channel, from_id, from_name,
           file_name, file_size, local_path, status, received, total, created_at, updated_at)
         VALUES (?, ?, 'out', ?, 'dm', ?, ?, ?, ?, ?, 'queued', 0, ?, ?, ?)`
      )
      .run(
        params.id,
        params.convId,
        params.kind,
        params.selfId,
        params.selfName,
        params.fileName,
        params.size,
        params.sourcePath,
        params.size,
        ts,
        ts
      )
    this.touchConversation(params.convId, ts)
  }

  // 公网聊天室的会话统一用 relay: 前缀，房间消息复用现有消息表。
  ensureRelayConversation(roomId: string, name: string): string {
    const convId = `relay:${roomId}`
    this.ensureConversation(convId, name)
    return convId
  }

  upsertRelayMessage(params: {
    id: string
    roomId: string
    roomName: string
    direction: 'in' | 'out'
    fromId: string
    fromName: string
    kind: 'text' | 'file'
    body?: string
    fileName?: string
    fileSize?: number
    fileId?: string
    localPath?: string
    status: MessageStatus
    recalled?: boolean
    createdAt: number
  }): void {
    if (!params.id || !params.roomId) return
    const convId = this.ensureRelayConversation(params.roomId, params.roomName)
    const ts = params.createdAt || now()
    const total = params.fileSize ?? 0
    this.db
      .prepare(
        `INSERT INTO messages (id, conversation_id, direction, kind, channel, from_id, from_name,
           body, file_name, file_size, relay_file_id, local_path, status, received, total, recalled,
           created_at, updated_at)
         VALUES (?, ?, ?, ?, 'relay', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(id) DO UPDATE SET
           status = CASE
             WHEN messages.status IN ('done', 'duplicate') AND excluded.status = 'active'
               THEN messages.status
             ELSE excluded.status END,
           from_name = excluded.from_name,
           body = COALESCE(excluded.body, messages.body),
           file_name = COALESCE(excluded.file_name, messages.file_name),
           file_size = COALESCE(excluded.file_size, messages.file_size),
           relay_file_id = COALESCE(excluded.relay_file_id, messages.relay_file_id),
           local_path = COALESCE(messages.local_path, excluded.local_path),
           received = MAX(messages.received, excluded.received),
           total = MAX(messages.total, excluded.total),
           recalled = MAX(messages.recalled, excluded.recalled),
           updated_at = excluded.updated_at`
      )
      .run(
        params.id,
        convId,
        params.direction,
        params.kind,
        params.fromId,
        params.fromName || params.fromId,
        params.body ?? null,
        params.fileName ?? null,
        params.fileSize ?? null,
        params.fileId ?? null,
        params.localPath ?? null,
        params.status,
        params.status === 'done' ? total : 0,
        total,
        params.recalled ? 1 : 0,
        ts,
        now()
      )
    this.touchConversation(convId, ts)
  }

  setRelayFileId(id: string, fileId: string): void {
    this.db.prepare('UPDATE messages SET relay_file_id = ? WHERE id = ?').run(fileId, id)
  }

  getRelayFileId(id: string): string | undefined {
    const row = this.db
      .prepare('SELECT relay_file_id FROM messages WHERE id = ?')
      .get(id) as { relay_file_id?: string } | undefined
    return row?.relay_file_id || undefined
  }

  setRelayFile(id: string, localPath: string, status: MessageStatus): void {
    this.db
      .prepare(
        `UPDATE messages SET local_path = ?, status = ?, received = total, updated_at = ?
         WHERE id = ?`
      )
      .run(localPath, status, now(), id)
  }

  setRelayStatus(id: string, status: MessageStatus): void {
    this.db.prepare('UPDATE messages SET status = ?, updated_at = ? WHERE id = ?').run(status, now(), id)
  }

  recallMessage(id: string): void {
    this.db.prepare('UPDATE messages SET recalled = 1, updated_at = ? WHERE id = ?').run(now(), id)
  }

  deleteRelayConversations(): void {
    this.db.prepare("DELETE FROM messages WHERE conversation_id LIKE 'relay:%'").run()
    this.db.prepare("DELETE FROM conversations WHERE peer_id LIKE 'relay:%'").run()
  }

  getMessage(id: string): Message | undefined {
    return this.db.prepare('SELECT * FROM messages WHERE id = ?').get(id) as Message | undefined
  }

  deleteMessage(id: string): void {
    this.db.prepare('DELETE FROM messages WHERE id = ?').run(id)
  }

  clearConversation(convId: string): void {
    this.db.prepare('DELETE FROM messages WHERE conversation_id = ?').run(convId)
    this.db.prepare('UPDATE conversations SET last_message_at = 0 WHERE peer_id = ?').run(convId)
  }

  deleteConversation(convId: string): void {
    this.db.prepare('DELETE FROM messages WHERE conversation_id = ?').run(convId)
    this.db.prepare('DELETE FROM conversations WHERE peer_id = ?').run(convId)
  }

  listMessageIds(convId: string): string[] {
    return (
      this.db
        .prepare('SELECT id FROM messages WHERE conversation_id = ?')
        .all(convId) as { id: string }[]
    ).map((row) => row.id)
  }

  findRecentOutText(convId: string, body: string, since: number): { id: string } | undefined {
    return this.db
      .prepare(
        `SELECT id FROM messages WHERE conversation_id = ? AND direction = 'out'
           AND kind = 'text' AND body = ? AND created_at >= ?
         ORDER BY created_at DESC LIMIT 1`
      )
      .get(convId, body, since) as { id: string } | undefined
  }

  resetForRetry(id: string): void {
    this.db
      .prepare(
        `UPDATE messages SET status = 'queued', received = 0, speed_bps = 0, error_code = NULL,
           total = COALESCE(file_size, total), updated_at = ? WHERE id = ?`
      )
      .run(now(), id)
  }

  setMessageStatus(id: string, status: MessageStatus, errorCode?: string): void {
    this.db
      .prepare('UPDATE messages SET status = ?, error_code = ?, updated_at = ? WHERE id = ?')
      .run(status, errorCode ?? null, now(), id)
  }

  markRead(convId: string): void {
    if (!convId) return
    this.db
      .prepare(
        `UPDATE conversations SET last_read_at = MAX(last_read_at, COALESCE(
           (SELECT MAX(created_at) FROM messages WHERE conversation_id = ? AND direction = 'in'), 0))
         WHERE peer_id = ?`
      )
      .run(convId, convId)
  }

  listConversations(): Conversation[] {
    return this.db
      .prepare(
        `SELECT c.peer_id AS id, c.peer_name AS name, c.platform,
           c.last_message_at, c.last_read_at, c.created_at,
           (SELECT COUNT(*) FROM messages m
             WHERE m.conversation_id = c.peer_id AND m.direction = 'in'
               AND m.created_at > c.last_read_at) AS unread
         FROM conversations c
         ORDER BY c.last_message_at DESC`
      )
      .all() as Conversation[]
  }

  listMessages(): Message[] {
    const rows = this.db
      .prepare('SELECT * FROM messages ORDER BY created_at ASC, rowid ASC')
      .all() as (Omit<Message, 'recalled'> & { recalled?: number | boolean })[]
    return rows.map((row) => ({ ...row, recalled: row.recalled === 1 || row.recalled === true }))
  }

  listPublicTexts(
    limit = 300
  ): { id: string; from_id: string; from_name: string | null; body: string; created_at: number }[] {
    const rows = this.db
      .prepare(
        `SELECT id, from_id, from_name, body, created_at FROM messages
           WHERE conversation_id = ? AND kind = 'text' AND status <> 'failed'
           ORDER BY created_at DESC, rowid DESC LIMIT ?`
      )
      .all(PUBLIC_CONVERSATION_ID, limit) as {
      id: string
      from_id: string
      from_name: string | null
      body: string
      created_at: number
    }[]
    return rows.reverse()
  }

  listActiveInbound(): Message[] {
    return this.db
      .prepare(
        `SELECT * FROM messages WHERE direction = 'in' AND kind <> 'text'
           AND status IN ('pending', 'active', 'accepted')`
      )
      .all() as Message[]
  }

  private touchConversation(convId: string, at: number): void {
    this.db
      .prepare('UPDATE conversations SET last_message_at = MAX(last_message_at, ?) WHERE peer_id = ?')
      .run(at, convId)
  }
}
