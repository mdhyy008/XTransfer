import { spawn, type ChildProcess } from 'node:child_process'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import type { BleDevice, BleState } from '../shared/types'

export type BleInbound = {
  id: string
  from: string
  fromName: string
  platform?: string
  body: string
  ts: number
}

type Callbacks = {
  onChange: (patch: Partial<BleState>) => void
  onMessage: (message: BleInbound) => void
}

type Pending = {
  resolve: () => void
  reject: (error: Error) => void
  timer: NodeJS.Timeout
}

const RESTART_DELAYS = [1000, 3000, 8000]
// 助手内部最坏要经历「连接 + 4s + 6s + 6s」才算失败，留足余量再判超时。
const SEND_TIMEOUT_MS = 35_000

// Electron 作为命名管道服务端，BleBridge.exe 连上来后用逐行 JSON 收发命令。
// 蓝牙相关的全部活都在助手进程里，主进程只负责转消息和存库。
export class BleService {
  private readonly exePath: string
  private readonly callbacks: Callbacks
  private state: BleState

  private server: net.Server | null = null
  private socket: net.Socket | null = null
  private child: ChildProcess | null = null
  private buffer = ''
  private pipeName = ''
  private selfId = ''
  private selfName = ''
  private shouldRun = false
  private pendingConnect = ''
  private restarts = 0
  private restartTimer: NodeJS.Timeout | null = null
  private readonly pending = new Map<string, Pending>()

  constructor(exePath: string, callbacks: Callbacks) {
    this.exePath = exePath
    this.callbacks = callbacks
    this.state = {
      supported: process.platform === 'win32' && fs.existsSync(exePath),
      on: false,
      scanning: false,
      advertising: false,
      devices: []
    }
  }

  get snapshot(): BleState {
    return { ...this.state, devices: [...this.state.devices] }
  }

  async start(selfId: string, selfName: string): Promise<BleState> {
    if (!this.state.supported) {
      this.patch({ on: false, error: '当前系统不支持蓝牙通道' })
      return this.snapshot
    }
    this.selfId = selfId
    this.selfName = selfName
    this.shouldRun = true
    this.restarts = 0
    this.patch({ on: true, error: undefined })
    if (this.socket) {
      this.write({ cmd: 'start', selfId, selfName })
      return this.snapshot
    }
    this.spawn().catch((error: unknown) => {
      this.patch({ error: error instanceof Error ? error.message : '蓝牙通道启动失败' })
    })
    return this.snapshot
  }

  stop(): BleState {
    this.shouldRun = false
    this.pendingConnect = ''
    if (this.restartTimer) {
      clearTimeout(this.restartTimer)
      this.restartTimer = null
    }
    this.flushPending('蓝牙已关闭')
    this.write({ cmd: 'shutdown' })
    const child = this.child
    if (child) {
      setTimeout(() => {
        if (this.child === child) child.kill()
      }, 1500)
    }
    this.patch({ on: false, scanning: false, advertising: false, devices: [], error: undefined })
    return this.snapshot
  }

  dispose(): void {
    this.shouldRun = false
    this.pendingConnect = ''
    this.flushPending('应用已退出')
    this.write({ cmd: 'shutdown' })
    this.child?.kill()
    this.child = null
    this.socket?.destroy()
    this.socket = null
    this.server?.close()
    this.server = null
  }

  connect(peerId: string): void {
    if (!this.state.on) return
    // 刚开启广播时管道还没连上，先记下来，等助手 ready 再发。
    if (!this.socket) {
      this.pendingConnect = peerId
      return
    }
    this.write({ cmd: 'connect', to: peerId })
  }

  send(to: string, id: string, body: string): Promise<void> {
    if (!this.state.on || !this.socket) {
      return Promise.reject(new Error('蓝牙未连接'))
    }
    return new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error('蓝牙发送超时'))
      }, SEND_TIMEOUT_MS)
      this.pending.set(id, { resolve, reject, timer })
      this.write({ cmd: 'send', to, id, body, ts: Math.floor(Date.now() / 1000) })
    })
  }

  private patch(next: Partial<BleState>): void {
    this.state = { ...this.state, ...next }
    this.callbacks.onChange(next)
  }

  private spawn(): Promise<void> {
    this.closeLink()
    this.pipeName = `xltransfer-ble-${process.pid}-${Date.now().toString(36)}`
    const pipePath = `\\\\.\\pipe\\${this.pipeName}`

    return new Promise<void>((resolve, reject) => {
      const server = net.createServer((socket) => {
        if (this.socket) {
          socket.destroy()
          return
        }
        this.socket = socket
        socket.setEncoding('utf8')
        socket.on('data', (chunk: string) => this.onData(chunk))
        socket.on('error', () => {})
        socket.on('close', () => {
          if (this.socket === socket) this.socket = null
        })
      })
      this.server = server
      server.once('error', reject)
      server.listen(pipePath, () => {
        server.removeListener('error', reject)
        const child = spawn(this.exePath, ['--pipe', this.pipeName], {
          cwd: path.dirname(this.exePath),
          windowsHide: true,
          stdio: ['ignore', 'pipe', 'pipe']
        })
        this.child = child
        child.stdout?.setEncoding('utf8')
        child.stderr?.setEncoding('utf8')
        child.stdout?.on('data', () => {})
        child.stderr?.on('data', () => {})
        child.on('error', reject)
        child.on('exit', () => this.onChildExit(child))
        resolve()
      })
    })
  }

  private onChildExit(child: ChildProcess): void {
    if (this.child !== child) return
    this.child = null
    this.closeLink()
    this.flushPending('蓝牙通道已退出')
    if (!this.shouldRun) {
      this.patch({ on: false, scanning: false, advertising: false, devices: [] })
      return
    }
    this.restarts += 1
    if (this.restarts > RESTART_DELAYS.length) {
      this.shouldRun = false
      this.patch({ on: false, scanning: false, advertising: false, error: '蓝牙通道已退出' })
      return
    }
    const delay = RESTART_DELAYS[this.restarts - 1]
    this.patch({ on: true, scanning: false, advertising: false, error: '蓝牙通道正在重启' })
    this.restartTimer = setTimeout(() => {
      this.restartTimer = null
      if (!this.shouldRun) return
      this.spawn().catch((error: unknown) => {
        this.patch({ error: error instanceof Error ? error.message : '蓝牙通道重启失败' })
      })
    }, delay)
  }

  private closeLink(): void {
    this.server?.close()
    this.server = null
    this.socket?.destroy()
    this.socket = null
    this.buffer = ''
  }

  private flushPending(reason: string): void {
    for (const [id, item] of this.pending) {
      clearTimeout(item.timer)
      item.reject(new Error(reason))
      this.pending.delete(id)
    }
  }

  private write(payload: Record<string, unknown>): void {
    if (!this.socket) return
    try {
      this.socket.write(`${JSON.stringify(payload)}\n`)
    } catch {
      // 管道断开时由 close/exit 分支统一收尾。
    }
  }

  private onData(chunk: string): void {
    this.buffer += chunk
    let index = this.buffer.indexOf('\n')
    while (index >= 0) {
      const line = this.buffer.slice(0, index).trim()
      this.buffer = this.buffer.slice(index + 1)
      if (line) this.onLine(line)
      index = this.buffer.indexOf('\n')
    }
  }

  private onLine(line: string): void {
    let data: Record<string, unknown>
    try {
      data = JSON.parse(line) as Record<string, unknown>
    } catch {
      return
    }
    const evt = typeof data.evt === 'string' ? data.evt : ''
    switch (evt) {
      case 'ready':
        this.restarts = 0
        this.write({ cmd: 'start', selfId: this.selfId, selfName: this.selfName })
        if (this.pendingConnect) {
          this.write({ cmd: 'connect', to: this.pendingConnect })
          this.pendingConnect = ''
        }
        break
      case 'state':
        this.patch({
          on: data.on === true,
          scanning: data.scanning === true,
          advertising: data.advertising === true,
          error: typeof data.error === 'string' && data.error ? data.error : undefined
        })
        break
      case 'devices':
        this.patch({ devices: parseDevices(data.devices) })
        break
      case 'message':
        this.handleMessage(data)
        break
      case 'sent': {
        const id = typeof data.id === 'string' ? data.id : ''
        const item = this.pending.get(id)
        if (item) {
          clearTimeout(item.timer)
          this.pending.delete(id)
          item.resolve()
        }
        break
      }
      case 'failed': {
        const id = typeof data.id === 'string' ? data.id : ''
        const item = this.pending.get(id)
        if (item) {
          clearTimeout(item.timer)
          this.pending.delete(id)
          item.reject(new Error(typeof data.reason === 'string' ? data.reason : '蓝牙发送失败'))
        }
        break
      }
      default:
        break
    }
  }

  private handleMessage(data: Record<string, unknown>): void {
    const id = typeof data.id === 'string' ? data.id : ''
    const from = typeof data.from === 'string' ? data.from : ''
    const body = typeof data.body === 'string' ? data.body : ''
    if (!id || !from || !body) return
    this.callbacks.onMessage({
      id,
      from,
      fromName: typeof data.fromName === 'string' ? data.fromName : from,
      platform: typeof data.platform === 'string' ? data.platform : undefined,
      body,
      ts: typeof data.ts === 'number' ? data.ts : 0
    })
  }
}

function parseDevices(raw: unknown): BleDevice[] {
  if (!Array.isArray(raw)) return []
  const devices: BleDevice[] = []
  for (const item of raw) {
    if (!item || typeof item !== 'object') continue
    const row = item as Record<string, unknown>
    const id = typeof row.id === 'string' ? row.id : ''
    if (!id) continue
    devices.push({
      id,
      name: typeof row.name === 'string' && row.name ? row.name : id,
      platform: typeof row.platform === 'string' ? row.platform : undefined,
      address: typeof row.address === 'string' ? row.address : undefined,
      rssi: typeof row.rssi === 'number' ? row.rssi : 0,
      lastSeen: Math.floor(Date.now() / 1000),
      connected: row.connected === true,
      connecting: row.connecting === true
    })
  }
  return devices
}
