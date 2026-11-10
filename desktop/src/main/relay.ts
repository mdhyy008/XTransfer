import fs from 'node:fs'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import type { RelayMember, RelayRoom, RelayState } from '../shared/types'
import type { Db } from './db'

const PUBLIC_ROOM_ID = 'public'
const PUBLIC_ROOM_NAME = '公网广播'
const RECONNECT_MIN = 2000
const RECONNECT_MAX = 30000
const ACK_TIMEOUT = 15000
const DEVICE_FILE = 'relay-device'

type ServerRoom = {
  id?: string
  code?: string
  name?: string
  creator?: string
  createdAt?: number
  memberCount?: number
}

type ServerMember = {
  deviceId?: string
  name?: string
  platform?: string
  online?: boolean
  creator?: boolean
}

type ServerMessage = {
  id?: string
  room?: string
  from?: string
  fromName?: string
  kind?: string
  body?: string
  fileId?: string
  fileName?: string
  fileSize?: number
  createdAt?: number
  recalled?: boolean
}

type Incoming = {
  id: string
  roomId: string
  roomName: string
  convId: string
  fromName: string
  kind: 'text' | 'file'
  body: string
  fileName?: string
}

type RelayOptions = {
  db: Db
  dataDir: string
  selfName: () => string
  recvDir: () => string
  cacheDir: () => string
  notify: (message: Incoming) => void
  onRelay: (state: RelayState) => void
  onChange: () => void
}

function now(): number {
  return Math.floor(Date.now() / 1000)
}

function sanitizeName(name: string): string {
  const base = path.basename(name || 'file').replace(/[\\/:*?"<>|]/g, '_')
  return base || 'file'
}

function uniquePath(dir: string, name: string): string {
  const ext = path.extname(name)
  const stem = path.basename(name, ext)
  let candidate = path.join(dir, name)
  let index = 1
  while (fs.existsSync(candidate)) {
    candidate = path.join(dir, `${stem} (${index})${ext}`)
    index += 1
  }
  return candidate
}

function mimeOf(name: string): string {
  const ext = path.extname(name).slice(1).toLowerCase()
  const table: Record<string, string> = {
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    png: 'image/png',
    gif: 'image/gif',
    webp: 'image/webp',
    bmp: 'image/bmp',
    svg: 'image/svg+xml',
    mp4: 'video/mp4',
    webm: 'video/webm',
    mov: 'video/quicktime',
    mp3: 'audio/mpeg',
    wav: 'audio/wav',
    m4a: 'audio/mp4',
    pdf: 'application/pdf',
    zip: 'application/zip',
    txt: 'text/plain'
  }
  return table[ext] ?? 'application/octet-stream'
}

function isImage(name: string): boolean {
  return /\.(jpe?g|png|gif|webp|bmp|svg)$/i.test(name)
}

export class RelayService {
  private ws: WebSocket | null = null
  private address = ''
  private disposed = false
  private connected = false
  private connecting = false
  private error = ''
  private serverName = ''
  private maxFileSize = 0
  private rooms: RelayRoom[] = []
  private members: Record<string, RelayMember[]> = {}
  private retry = RECONNECT_MIN
  private reconnectTimer: NodeJS.Timeout | null = null
  private connectTimer: NodeJS.Timeout | null = null
  private errorTimer: NodeJS.Timeout | null = null
  private readonly deviceId: string
  private readonly pending = new Map<string, NodeJS.Timeout>()
  private pendingJoin:
    | { resolve: (roomId: string) => void; reject: (error: Error) => void; timer: NodeJS.Timeout }
    | null = null
  private readonly downloading = new Set<string>()

  constructor(private readonly options: RelayOptions) {
    this.deviceId = this.loadDeviceId()
  }

  state(): RelayState {
    return {
      configured: this.address !== '',
      connected: this.connected,
      connecting: this.connecting,
      address: this.address,
      deviceId: this.deviceId,
      serverName: this.serverName || undefined,
      maxFileSize: this.maxFileSize || undefined,
      error: this.error || undefined,
      rooms: this.rooms,
      members: this.members
    }
  }

  setAddress(value: string): void {
    const next = (value ?? '').trim().replace(/\/+$/, '')
    if (next === this.address) {
      if (next && !this.ws && !this.reconnectTimer) this.open()
      return
    }
    this.closeSocket()
    this.address = next
    this.connected = false
    this.connecting = false
    this.error = ''
    this.rooms = []
    this.members = {}
    this.serverName = ''
    this.maxFileSize = 0
    if (!next) {
      this.options.db.deleteRelayConversations()
      this.emit()
      this.options.onChange()
      return
    }
    this.emit()
    this.open()
  }

  async testAddress(value: string): Promise<{ ok: boolean; name?: string; error?: string }> {
    const raw = (value ?? '').trim().replace(/\/+$/, '')
    if (!raw) return { ok: false, error: '请填写服务器地址' }
    try {
      const url = new URL(raw.includes('://') ? raw : `https://${raw}`)
      url.search = ''
      url.hash = ''
      url.pathname = url.pathname.replace(/\/+$/, '')
      const res = await fetch(url.toString().replace(/\/+$/, ''), {
        headers: { accept: 'application/json' },
        signal: AbortSignal.timeout(5000)
      })
      if (!res.ok) return { ok: false, error: `服务器返回 ${res.status}` }
      const data = (await res.json()) as { ok?: boolean; name?: string }
      if (!data.ok) return { ok: false, error: '地址无效' }
      return { ok: true, name: typeof data.name === 'string' ? data.name.trim() : '' }
    } catch (error) {
      const timeout = error instanceof Error && error.name === 'TimeoutError'
      return { ok: false, error: timeout ? '连接超时' : '地址无效' }
    }
  }

  createRoom(name: string): Promise<string> {
    return this.requestRoom({ type: 'create', name: (name ?? '').trim().slice(0, 32) })
  }

  joinRoom(code: string): Promise<string> {
    const value = (code ?? '').trim()
    if (!/^\d{6}$/.test(value)) return Promise.reject(new Error('房间号是六位数字'))
    return this.requestRoom({ type: 'join', code: value })
  }

  leaveRoom(roomId: string): void {
    if (!roomId) return
    this.send({ type: 'leave', room: roomId })
  }

  sendText(roomId: string, body: string): void {
    const text = (body ?? '').trim()
    if (!text || !roomId) return
    this.requireOpen()
    const id = randomUUID()
    const roomName = this.roomLabel(roomId)
    this.options.db.upsertRelayMessage({
      id,
      roomId,
      roomName,
      direction: 'out',
      fromId: this.deviceId,
      fromName: this.options.selfName(),
      kind: 'text',
      body: text,
      status: 'sending',
      createdAt: now()
    })
    this.trackAck(id, () => this.options.db.setRelayStatus(id, 'failed'))
    this.send({ type: 'send', id, room: roomId, kind: 'text', body: text })
    this.options.onChange()
  }

  async sendFiles(roomId: string, paths: string[]): Promise<void> {
    if (!roomId) return
    this.requireOpen()
    const roomName = this.roomLabel(roomId)
    let sent = 0
    for (const sourcePath of paths) {
      if (!sourcePath) continue
      let stat: fs.Stats
      try {
        stat = fs.statSync(sourcePath)
      } catch {
        throw new Error('文件不存在')
      }
      if (!stat.isFile()) throw new Error('公网聊天室只支持发送单个文件')
      if (this.maxFileSize > 0 && stat.size > this.maxFileSize) {
        throw new Error(`单个文件不能超过 ${Math.floor(this.maxFileSize / 1024 / 1024)} MB`)
      }
      const name = path.basename(sourcePath)
      const id = randomUUID()
      this.options.db.upsertRelayMessage({
        id,
        roomId,
        roomName,
        direction: 'out',
        fromId: this.deviceId,
        fromName: this.options.selfName(),
        kind: 'file',
        fileName: name,
        fileSize: stat.size,
        localPath: sourcePath,
        status: 'sending',
        createdAt: now()
      })
      this.options.onChange()
      try {
        const fileId = await this.upload(sourcePath, name)
        this.options.db.setRelayFileId(id, fileId)
        this.trackAck(id, () => this.options.db.setRelayStatus(id, 'failed'))
        this.send({
          type: 'send',
          id,
          room: roomId,
          kind: isImage(name) ? 'image' : 'file',
          fileId
        })
        sent += 1
      } catch (error) {
        this.options.db.setRelayStatus(id, 'failed')
        this.options.onChange()
        throw error
      }
    }
    if (sent === 0) throw new Error('没有可发送的文件')
  }

  recall(roomId: string, messageId: string): void {
    if (!roomId || !messageId) return
    this.send({ type: 'recall', room: roomId, id: messageId })
  }

  kick(roomId: string, deviceId: string): void {
    if (!roomId || !deviceId) return
    this.send({ type: 'kick', room: roomId, target: deviceId })
  }

  rename(roomId: string, name: string): void {
    const value = (name ?? '').trim().slice(0, 32)
    if (!roomId || !value) return
    this.send({ type: 'rename', room: roomId, name: value })
  }

  requestMembers(roomId: string): void {
    if (!roomId) return
    this.send({ type: 'members', room: roomId })
  }

  // 设备名改了要让服务端和房间里的人看到新名字，重连一次最省事。
  refreshProfile(): void {
    if (!this.address) return
    this.closeSocket()
    this.connected = false
    this.connecting = false
    this.open()
  }

  dispose(): void {
    this.disposed = true
    this.address = ''
    this.closeSocket()
    for (const timer of this.pending.values()) clearTimeout(timer)
    this.pending.clear()
    if (this.pendingJoin) clearTimeout(this.pendingJoin.timer)
    this.pendingJoin = null
    if (this.connectTimer) clearTimeout(this.connectTimer)
    if (this.errorTimer) clearTimeout(this.errorTimer)
  }

  private loadDeviceId(): string {
    const file = path.join(this.options.dataDir, DEVICE_FILE)
    try {
      const value = fs.readFileSync(file, 'utf8').trim()
      if (/^[A-Za-z0-9_-]{4,64}$/.test(value)) return value
    } catch {
      // 首次运行
    }
    const id = `xr${randomUUID().replace(/-/g, '')}`
    try {
      fs.mkdirSync(this.options.dataDir, { recursive: true })
      fs.writeFileSync(file, `${id}\n`, 'utf8')
    } catch {
      // 写不进去就用内存里的这个
    }
    return id
  }

  private open(): void {
    if (this.disposed || !this.address || this.ws) return
    let url: URL
    try {
      url = this.wsUrl()
    } catch {
      this.error = '中继地址无效'
      this.emit()
      return
    }
    this.connecting = true
    this.error = ''
    this.emit()
    let ws: WebSocket
    try {
      ws = new WebSocket(url.toString())
    } catch (error) {
      this.connecting = false
      this.error = error instanceof Error ? error.message : '无法连接中继服务器'
      this.emit()
      this.scheduleReconnect()
      return
    }
    this.ws = ws
    // 连不上就别一直挂着，5 秒内没握手成功按失败处理。
    this.connectTimer = setTimeout(() => {
      this.connectTimer = null
      if (this.ws !== ws || this.connected) return
      this.ws = null
      this.connected = false
      this.connecting = false
      this.failPending()
      this.error = '连接超时'
      try {
        ws.close()
      } catch {
        // 已经关闭
      }
      this.emit()
      this.scheduleReconnect()
    }, 5000)
    ws.addEventListener('open', () => {
      if (this.ws !== ws) return
      if (this.connectTimer) {
        clearTimeout(this.connectTimer)
        this.connectTimer = null
      }
      this.connected = true
      this.connecting = false
      this.retry = RECONNECT_MIN
      this.error = ''
      this.emit()
      void this.loadInfo()
    })
    ws.addEventListener('message', (event) => {
      if (this.ws !== ws) return
      this.handle(event.data)
    })
    ws.addEventListener('error', () => {
      if (this.ws !== ws) return
      this.error = '无法连接中继服务器'
      this.emit()
    })
    ws.addEventListener('close', () => {
      if (this.ws !== ws) return
      if (this.connectTimer) {
        clearTimeout(this.connectTimer)
        this.connectTimer = null
      }
      this.ws = null
      this.connected = false
      this.connecting = false
      this.failPending()
      this.error = '与中继服务器的连接已断开'
      this.emit()
      this.scheduleReconnect()
    })
  }

  private closeSocket(): void {
    const ws = this.ws
    this.ws = null
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.connectTimer) {
      clearTimeout(this.connectTimer)
      this.connectTimer = null
    }
    if (ws) {
      try {
        ws.close()
      } catch {
        // 已经关闭
      }
    }
    this.failPending()
  }

  private scheduleReconnect(): void {
    if (this.disposed || !this.address || this.ws || this.reconnectTimer) return
    this.connecting = true
    this.emit()
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.open()
    }, this.retry)
    this.retry = Math.min(RECONNECT_MAX, this.retry * 2)
  }

  private httpBase(): string {
    const raw = this.address.includes('://') ? this.address : `https://${this.address}`
    const url = new URL(raw)
    url.search = ''
    url.hash = ''
    url.pathname = url.pathname.replace(/\/+$/, '')
    return url.toString().replace(/\/+$/, '')
  }

  private wsUrl(): URL {
    const url = new URL(this.httpBase())
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    url.pathname = `${url.pathname}/ws`
    url.searchParams.set('device', this.deviceId)
    url.searchParams.set('name', this.options.selfName())
    url.searchParams.set('platform', 'windows')
    return url
  }

  private async loadInfo(): Promise<void> {
    try {
      const res = await fetch(this.httpBase(), { headers: { accept: 'application/json' } })
      if (!res.ok) return
      const data = (await res.json()) as { maxFileSize?: number; name?: string }
      if (typeof data.maxFileSize === 'number' && data.maxFileSize > 0) {
        this.maxFileSize = data.maxFileSize
      }
      if (typeof data.name === 'string' && data.name.trim()) {
        this.serverName = data.name.trim()
      }
      this.emit()
    } catch {
      // 拿不到上限就按服务端返回的错误提示
    }
  }

  private handle(raw: unknown): void {
    if (typeof raw !== 'string') return
    let data: Record<string, unknown>
    try {
      data = JSON.parse(raw) as Record<string, unknown>
    } catch {
      return
    }
    switch (String(data.type ?? '')) {
      case 'ready':
        this.handleReady(data)
        break
      case 'joined':
        this.handleJoined(data)
        break
      case 'left':
        this.removeRoom(String(data.room ?? ''))
        break
      case 'kicked':
        this.removeRoom(String(data.room ?? ''))
        break
      case 'room':
        this.handleRoom(data)
        break
      case 'members':
        this.handleMembers(data)
        break
      case 'message':
        this.handleMessage((data.message ?? {}) as ServerMessage)
        break
      case 'history':
        this.handleHistory(data)
        break
      case 'ack':
        this.handleAck(String(data.id ?? ''))
        break
      case 'recalled':
        this.handleRecalled(String(data.id ?? ''))
        break
      case 'error':
        this.handleError(String(data.message ?? '操作失败'))
        break
      default:
        break
    }
  }

  private handleReady(data: Record<string, unknown>): void {
    const rooms = Array.isArray(data.rooms) ? (data.rooms as ServerRoom[]) : []
    this.rooms = rooms.map((room) => this.toRoom(room)).filter((room) => room.id !== '')
    this.sortRooms()
    this.reconcileConversations()
    for (const room of this.rooms) {
      this.options.db.ensureRelayConversation(room.id, this.roomLabel(room.id))
      this.sendSafe({ type: 'history', room: room.id, limit: 60 })
      this.sendSafe({ type: 'members', room: room.id })
    }
    this.emit()
    this.options.onChange()
  }

  private handleJoined(data: Record<string, unknown>): void {
    const room = this.toRoom((data.room ?? {}) as ServerRoom)
    if (!room.id) return
    const index = this.rooms.findIndex((item) => item.id === room.id)
    if (index >= 0) this.rooms[index] = room
    else this.rooms.push(room)
    this.sortRooms()
    this.options.db.ensureRelayConversation(room.id, this.roomLabel(room.id))
    this.sendSafe({ type: 'history', room: room.id, limit: 60 })
    this.sendSafe({ type: 'members', room: room.id })
    this.emit()
    this.options.onChange()
    if (this.pendingJoin) {
      clearTimeout(this.pendingJoin.timer)
      const resolve = this.pendingJoin.resolve
      this.pendingJoin = null
      resolve(room.id)
    }
  }

  private removeRoom(roomId: string): void {
    if (!roomId) return
    this.rooms = this.rooms.filter((room) => room.id !== roomId)
    delete this.members[roomId]
    this.options.db.deleteConversation(`relay:${roomId}`)
    this.emit()
    this.options.onChange()
  }

  private handleRoom(data: Record<string, unknown>): void {
    const room = this.toRoom((data.room ?? {}) as ServerRoom)
    if (!room.id) return
    const index = this.rooms.findIndex((item) => item.id === room.id)
    if (index >= 0) this.rooms[index] = room
    else this.rooms.push(room)
    this.sortRooms()
    this.options.db.ensureRelayConversation(room.id, this.roomLabel(room.id))
    this.emit()
    this.options.onChange()
  }

  private handleMembers(data: Record<string, unknown>): void {
    const roomId = String(data.room ?? '')
    if (!roomId) return
    const list = Array.isArray(data.members) ? (data.members as ServerMember[]) : []
    this.members = {
      ...this.members,
      [roomId]: list
        .filter((member) => member.deviceId)
        .map((member) => ({
          deviceId: String(member.deviceId),
          name: String(member.name || member.deviceId),
          platform: member.platform ? String(member.platform) : undefined,
          online: member.online !== false,
          creator: member.creator === true
        }))
    }
    this.emit()
  }

  private handleMessage(message: ServerMessage): void {
    if (!message.id || !message.room) return
    this.ingest(message, true)
  }

  private handleHistory(data: Record<string, unknown>): void {
    const messages = Array.isArray(data.messages) ? (data.messages as ServerMessage[]) : []
    for (const message of messages) {
      if (!message?.id || !message.room) continue
      this.ingest(message, false)
    }
  }

  private ingest(message: ServerMessage, live: boolean): void {
    const id = String(message.id ?? '')
    const roomId = String(message.room ?? '')
    if (!id || !roomId) return
    const mine = String(message.from ?? '') === this.deviceId
    const kind: 'text' | 'file' = message.kind === 'text' || !message.kind ? 'text' : 'file'
    const roomName = this.roomLabel(roomId)
    const convId = this.options.db.ensureRelayConversation(roomId, roomName)
    const fileName = message.fileName ? String(message.fileName) : undefined
    const fileId = message.fileId ? String(message.fileId) : undefined
    const existing = this.options.db.getMessage(id)
    const isPublicRoom = roomId === PUBLIC_ROOM_ID
    let status: 'done' | 'active' = 'done'
    if (kind === 'file' && !mine && !existing?.local_path && !isPublicRoom) {
      status = 'active'
    }
    this.options.db.upsertRelayMessage({
      id,
      roomId,
      roomName,
      direction: mine ? 'out' : 'in',
      fromId: String(message.from ?? ''),
      fromName: String(message.fromName || message.from || ''),
      kind,
      body: message.body ? String(message.body) : undefined,
      fileName,
      fileSize: typeof message.fileSize === 'number' ? message.fileSize : undefined,
      fileId,
      status,
      recalled: message.recalled === true,
      createdAt: typeof message.createdAt === 'number' ? message.createdAt : now()
    })
    if (kind === 'file' && !mine && fileId && !existing?.local_path && !isPublicRoom) {
      void this.download(id, fileId, fileName ?? 'file')
    }
    if (live && !mine) {
      this.options.notify({
        id,
        roomId,
        roomName,
        convId,
        fromName: String(message.fromName || message.from || ''),
        kind,
        body: message.body ? String(message.body) : '',
        fileName
      })
    }
    this.options.onChange()
  }

  private async download(id: string, fileId: string, name: string): Promise<void> {
    if (this.downloading.has(id)) return
    this.downloading.add(id)
    try {
      const target = await this.fetchTo(fileId, name, this.options.recvDir())
      this.options.db.setRelayFile(id, target, 'done')
    } catch {
      this.options.db.setRelayStatus(id, 'failed')
    } finally {
      this.downloading.delete(id)
      this.options.onChange()
    }
  }

  async saveFile(id: string): Promise<string> {
    const message = this.options.db.getMessage(id)
    if (!message) throw new Error('消息不存在')
    const dir = this.options.recvDir()
    fs.mkdirSync(dir, { recursive: true })
    const name = message.file_name || (message.local_path ? path.basename(message.local_path) : 'file')
    let target: string
    const cached = message.local_path && fs.existsSync(message.local_path) ? message.local_path : ''
    if (cached) {
      target = uniquePath(dir, sanitizeName(name))
      fs.copyFileSync(cached, target)
    } else {
      const fileId = this.options.db.getRelayFileId(id)
      if (!fileId) throw new Error('文件不可用')
      target = await this.fetchTo(fileId, name, dir)
    }
    this.options.db.setRelayFile(id, target, 'done')
    this.options.onChange()
    return target
  }

  async cacheFile(id: string): Promise<string> {
    const message = this.options.db.getMessage(id)
    if (!message) throw new Error('消息不存在')
    if (message.local_path && fs.existsSync(message.local_path)) return message.local_path
    const fileId = this.options.db.getRelayFileId(id)
    if (!fileId) throw new Error('文件不可用')
    const target = await this.fetchTo(fileId, message.file_name || 'file', this.options.cacheDir())
    this.options.db.setRelayFile(id, target, 'done')
    this.options.onChange()
    return target
  }

  private async fetchTo(fileId: string, name: string, dir: string): Promise<string> {
    const res = await fetch(`${this.httpBase()}/files/${encodeURIComponent(fileId)}`)
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const buffer = Buffer.from(await res.arrayBuffer())
    fs.mkdirSync(dir, { recursive: true })
    const target = uniquePath(dir, sanitizeName(name))
    fs.writeFileSync(target, buffer)
    return target
  }

  private async upload(sourcePath: string, name: string): Promise<string> {
    const mime = mimeOf(name)
    const url = new URL(`${this.httpBase()}/files`)
    url.searchParams.set('name', name)
    url.searchParams.set('mime', mime)
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': mime },
      body: fs.readFileSync(sourcePath)
    })
    if (!res.ok) {
      let detail = ''
      try {
        const payload = (await res.json()) as { error?: string }
        detail = payload?.error ?? ''
      } catch {
        // 非 JSON 响应
      }
      if (res.status === 413) throw new Error(detail || '文件过大')
      throw new Error(detail || `上传失败：HTTP ${res.status}`)
    }
    const data = (await res.json()) as { id?: string }
    if (!data?.id) throw new Error('上传失败')
    return data.id
  }

  private handleAck(id: string): void {
    if (!id) return
    const timer = this.pending.get(id)
    if (timer) {
      clearTimeout(timer)
      this.pending.delete(id)
    }
    this.options.db.setRelayStatus(id, 'done')
    this.options.onChange()
  }

  private handleRecalled(id: string): void {
    if (!id) return
    this.options.db.recallMessage(id)
    this.options.onChange()
  }

  private handleError(message: string): void {
    this.error = message
    if (this.errorTimer) clearTimeout(this.errorTimer)
    this.errorTimer = setTimeout(() => {
      this.errorTimer = null
      if (this.error === message) {
        this.error = ''
        this.emit()
      }
    }, 5000)
    this.emit()
    if (this.pendingJoin) {
      clearTimeout(this.pendingJoin.timer)
      const reject = this.pendingJoin.reject
      this.pendingJoin = null
      reject(new Error(message))
    }
  }

  private requestRoom(payload: Record<string, unknown>): Promise<string> {
    this.requireOpen()
    if (this.pendingJoin) return Promise.reject(new Error('正在处理上一个请求'))
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingJoin = null
        reject(new Error('服务器响应超时'))
      }, 8000)
      this.pendingJoin = { resolve, reject, timer }
      this.send(payload)
    })
  }

  private trackAck(id: string, onTimeout: () => void): void {
    const old = this.pending.get(id)
    if (old) clearTimeout(old)
    this.pending.set(
      id,
      setTimeout(() => {
        this.pending.delete(id)
        onTimeout()
        this.options.onChange()
      }, ACK_TIMEOUT)
    )
  }

  private failPending(): void {
    for (const [id, timer] of this.pending) {
      clearTimeout(timer)
      this.options.db.setRelayStatus(id, 'failed')
    }
    this.pending.clear()
    if (this.pendingJoin) {
      clearTimeout(this.pendingJoin.timer)
      this.pendingJoin.reject(new Error('与中继服务器的连接已断开'))
      this.pendingJoin = null
    }
  }

  private requireOpen(): void {
    if (!this.ws || !this.connected) throw new Error('公网聊天室未连接')
  }

  private send(payload: Record<string, unknown>): void {
    if (!this.ws || !this.connected) throw new Error('公网聊天室未连接')
    try {
      this.ws.send(JSON.stringify(payload))
    } catch (error) {
      throw error instanceof Error ? error : new Error('发送失败')
    }
  }

  private sendSafe(payload: Record<string, unknown>): void {
    try {
      this.send(payload)
    } catch {
      // 连接刚好断开，重连后会重新同步
    }
  }

  private roomLabel(roomId: string): string {
    if (roomId === PUBLIC_ROOM_ID) return PUBLIC_ROOM_NAME
    const room = this.rooms.find((item) => item.id === roomId)
    return room?.name || room?.code || '聊天室'
  }

  private toRoom(room: ServerRoom): RelayRoom {
    return {
      id: String(room.id ?? ''),
      code: String(room.code ?? ''),
      name: String(room.name ?? (room.id === PUBLIC_ROOM_ID ? PUBLIC_ROOM_NAME : '聊天室')),
      creator: String(room.creator ?? ''),
      memberCount: typeof room.memberCount === 'number' ? room.memberCount : 0,
      createdAt: typeof room.createdAt === 'number' ? room.createdAt : 0
    }
  }

  private sortRooms(): void {
    this.rooms.sort((a, b) => {
      if (a.id === PUBLIC_ROOM_ID) return -1
      if (b.id === PUBLIC_ROOM_ID) return 1
      return a.createdAt - b.createdAt
    })
  }

  private reconcileConversations(): void {
    const ids = new Set(this.rooms.map((room) => room.id))
    for (const conv of this.options.db.listConversations()) {
      if (!conv.id.startsWith('relay:')) continue
      if (ids.has(conv.id.slice('relay:'.length))) continue
      this.options.db.deleteConversation(conv.id)
    }
  }

  private emit(): void {
    this.options.onRelay(this.state())
  }
}
