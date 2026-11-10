import { execFile, spawn, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import type { DeviceInfo } from '../shared/types'
import { request, type Endpoint } from './localApi'

export type ProbeResult = {
  endpoint: Endpoint
  info: DeviceInfo
  foreign: boolean
}

type RawConfig = {
  api_ports?: number[]
  tls?: boolean | null
  recv_dir?: string
  name?: string
}

export class XlanService {
  private recvDir: string

  constructor(
    private readonly dir: string,
    recvDir: string
  ) {
    this.recvDir = recvDir
  }

  // Windows 用 xlan.exe，macOS / Linux 用按平台和架构区分的无后缀二进制。
  binName(): string {
    if (process.platform === 'win32') return 'xlan.exe'
    if (process.platform === 'darwin') {
      return process.arch === 'arm64' ? 'xlan-darwin-arm64' : 'xlan-darwin-x64'
    }
    return process.arch === 'arm64' ? 'xlan-linux-arm64' : 'xlan-linux-x64'
  }

  get exe(): string {
    return path.join(this.dir, this.binName())
  }

  setRecvDir(dir: string): void {
    this.recvDir = dir
  }

  get configuredRecvDir(): string {
    return this.readConfig().recv_dir || this.recvDir
  }

  private readConfig(): RawConfig {
    try {
      return JSON.parse(fs.readFileSync(path.join(this.dir, 'config.json'), 'utf8')) as RawConfig
    } catch {
      return {}
    }
  }

  private ports(): number[] {
    const ports = this.readConfig().api_ports
    return Array.isArray(ports) && ports.length > 0 ? ports : [43503, 43504, 43505]
  }

  private tlsOn(): boolean {
    return this.readConfig().tls !== false
  }

  private cert(): Buffer | undefined {
    try {
      return fs.readFileSync(path.join(this.dir, 'tls.crt'))
    } catch {
      return undefined
    }
  }

  private endpoint(port: number): Endpoint {
    const tls = this.tlsOn()
    return {
      base: `${tls ? 'https' : 'http'}://127.0.0.1:${port}`,
      tls,
      cert: tls ? this.cert() : undefined
    }
  }

  private isForeign(info: DeviceInfo): boolean {
    try {
      const own = fs.readFileSync(path.join(this.dir, 'id'), 'utf8').trim()
      return own !== '' && own !== info.id
    } catch {
      return false
    }
  }

  private runConfig(args: string[]): Promise<void> {
    return new Promise((resolve) => {
      execFile(this.exe, args, { cwd: this.dir, windowsHide: true }, () => resolve())
    })
  }

  private async ensureDeviceName(): Promise<void> {
    if (this.readConfig().name) return
    const host = os.hostname().trim() || '电脑'
    const name = `${host}#${Math.floor(1000 + Math.random() * 9000)}`
    await this.runConfig(['config', 'set', 'name', name])
  }

  private async ensureRecvDir(): Promise<void> {
    fs.mkdirSync(this.recvDir, { recursive: true })
    const configured = this.readConfig().recv_dir
    if (configured && path.resolve(this.dir, configured) === path.resolve(this.recvDir)) return
    await this.runConfig(['config', 'set', 'recv_dir', this.recvDir])
  }

  async probe(): Promise<ProbeResult | null> {
    for (const port of this.ports()) {
      const endpoint = this.endpoint(port)
      if (endpoint.tls && !endpoint.cert) continue
      try {
        const info = await request<DeviceInfo>(endpoint, 'GET', '/v1/info')
        return { endpoint, info, foreign: this.isForeign(info) }
      } catch {
        // 该端口上没有可用服务，继续试下一个
      }
    }
    return null
  }

  async start(): Promise<ProbeResult> {
    if (!fs.existsSync(this.exe)) throw new Error(`找不到 ${this.binName()}`)
    await this.ensureDeviceName()
    await this.ensureRecvDir()

    const existing = await this.probe()
    if (existing) return existing

    spawn(this.exe, ['serve', '-d'], {
      cwd: this.dir,
      windowsHide: true,
      stdio: 'ignore'
    }).unref()

    const deadline = Date.now() + 15_000
    while (Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 300))
      const result = await this.probe()
      if (result) return result
    }
    throw new Error('xlan 服务启动超时')
  }

  stop(): void {
    try {
      spawnSync(this.exe, ['stop'], {
        cwd: this.dir,
        windowsHide: true,
        stdio: 'ignore',
        timeout: 5000
      })
    } catch {
      // 停服务失败不阻塞退出
    }
  }
}
