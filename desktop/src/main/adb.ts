import { execFile, spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { Readable } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import type {
  AdbDevice,
  AdbPullProgress,
  AdbPushProgress,
  AdbStatus,
  PhoneEntry
} from '../shared/types'

const PLATFORM_TOOLS_URL = `https://dl.google.com/android/repository/platform-tools-latest-${
  process.platform === 'darwin' ? 'darwin' : process.platform === 'win32' ? 'windows' : 'linux'
}.zip`
const ADB_BIN = process.platform === 'win32' ? 'adb.exe' : 'adb'

type Result = { code: number; out: string; err: string }

type PullItem = {
  remote: string
  local: string
  name: string
  size: number
  kind: 'file' | 'folder'
}

type PushItem = {
  local: string
  remote: string
  name: string
  size: number
  kind: 'file' | 'folder'
}

// Windows 上进程刚被杀掉时文件句柄可能还没释放，删不掉就多试几次。
function removeQuietly(target: string): void {
  try {
    fs.rmSync(target, { force: true, maxRetries: 5, retryDelay: 100 })
  } catch {
    // 实在删不掉就留到下次覆盖
  }
}

function exec(file: string, args: string[], timeoutMs = 0): Promise<Result> {
  return new Promise((resolve, reject) => {
    execFile(
      file,
      args,
      { windowsHide: true, maxBuffer: 32 * 1024 * 1024, timeout: timeoutMs || undefined },
      (error, stdout, stderr) => {
        if (error && typeof (error as NodeJS.ErrnoException).code !== 'number') {
          reject(error)
          return
        }
        resolve({
          code: error ? Number((error as { code?: number }).code ?? 1) : 0,
          out: String(stdout ?? ''),
          err: String(stderr ?? '')
        })
      }
    )
  })
}

// 远端 sh 参数用单引号包住，内部单引号转义成 '\''，路径里有空格或特殊字符也不会被拆开。
function shq(value: string): string {
  return `'${value.replace(/'/g, "'\\''")}'`
}

function remoteJoin(dir: string, name: string): string {
  return dir === '/' || dir === '' ? `/${name}` : `${dir.replace(/\/+$/, '')}/${name}`
}

function checkName(name: string): string {
  const trimmed = name.trim()
  if (!trimmed || trimmed === '.' || trimmed === '..') throw new Error('名称不能为空')
  if (trimmed.includes('/')) throw new Error('名称不能包含 /')
  return trimmed
}

// 设备上列目录：优先用 ls -la 的权限位、大小和名字三列，忽略时间列格式差异。
const LS_LINE = /^([-dlbcps])([rwxsStTlL-]{9})\s+\d+\s+\S+\s+\S+\s+(\d+)\s+\S+\s+\S+\s+(.*)$/

export class Adb {
  private cachedExe: string | null = null
  private downloading: Promise<AdbStatus> | null = null

  constructor(private readonly dir: string) {}

  private get bundledExe(): string {
    return path.join(this.dir, 'platform-tools', ADB_BIN)
  }

  // 先看按需下载到 data/adb 的那份，再看系统 PATH，避免重复下载。
  private locate(): string | null {
    if (this.cachedExe && fs.existsSync(this.cachedExe)) return this.cachedExe
    const candidates: string[] = [this.bundledExe]
    for (const entry of (process.env.PATH ?? '').split(path.delimiter)) {
      if (!entry) continue
      candidates.push(path.join(entry, process.platform === 'win32' ? 'adb.exe' : 'adb'))
    }
    for (const candidate of candidates) {
      try {
        if (fs.statSync(candidate).isFile()) {
          this.cachedExe = candidate
          return candidate
        }
      } catch {
        // 继续找下一个
      }
    }
    return null
  }

  async status(): Promise<AdbStatus> {
    const exe = this.locate()
    if (!exe) return { installed: false, path: '' }
    try {
      const result = await exec(exe, ['version'], 10_000)
      const version = result.out.split(/\r?\n/)[0]?.trim() ?? ''
      return { installed: true, path: exe, version }
    } catch {
      return { installed: false, path: '' }
    }
  }

  private exe(): string {
    const exe = this.locate()
    if (!exe) throw new Error('还没有下载 ADB')
    return exe
  }

  private async run(args: string[], timeoutMs = 30_000): Promise<Result> {
    const result = await exec(this.exe(), args, timeoutMs)
    if (result.code !== 0) {
      const detail = (result.err || result.out).trim()
      throw new Error(detail || `adb 退出码 ${result.code}`)
    }
    return result
  }

  // 下载 platform-tools 解压到 data/adb；重复点击时复用同一个下载任务。
  download(onProgress?: (received: number, total: number) => void): Promise<AdbStatus> {
    if (!this.downloading) {
      this.downloading = this.doDownload(onProgress).finally(() => {
        this.downloading = null
      })
    }
    return this.downloading
  }

  private async doDownload(onProgress?: (received: number, total: number) => void): Promise<AdbStatus> {
    fs.mkdirSync(this.dir, { recursive: true })
    const zip = path.join(this.dir, 'platform-tools.zip')
    const response = await fetch(PLATFORM_TOOLS_URL, { redirect: 'follow' })
    if (!response.ok || !response.body) {
      throw new Error(`下载失败：HTTP ${response.status}`)
    }
    const total = Number(response.headers.get('content-length') ?? 0)
    const body = Readable.fromWeb(
      response.body as unknown as import('node:stream/web').ReadableStream
    )
    let received = 0
    body.on('data', (chunk: Buffer) => {
      received += chunk.length
      onProgress?.(received, total)
    })
    try {
      await pipeline(body, fs.createWriteStream(zip))
      // 新版覆盖旧版前先停掉正在运行的 adb 服务，不然 adb.exe 被占用没法替换。
      if (fs.existsSync(this.bundledExe)) {
        await exec(this.bundledExe, ['kill-server'], 10_000).catch(() => {})
      }
      fs.rmSync(path.join(this.dir, 'platform-tools'), { recursive: true, force: true })
      await this.extract(zip)
    } finally {
      fs.rmSync(zip, { force: true })
    }
    if (!fs.existsSync(this.bundledExe)) throw new Error('解压后找不到 adb')
    if (process.platform !== 'win32') fs.chmodSync(this.bundledExe, 0o755)
    this.cachedExe = this.bundledExe
    const status = await this.status()
    if (!status.installed) throw new Error('adb 无法运行')
    return status
  }

  private async extract(zip: string): Promise<void> {
    try {
      await exec('tar', ['-xf', zip, '-C', this.dir], 120_000)
      return
    } catch {
      // 老系统没有 tar 时退回 PowerShell 解压。
    }
    const quote = (value: string): string => value.replace(/'/g, "''")
    await exec(
      'powershell',
      [
        '-NoProfile',
        '-NonInteractive',
        '-Command',
        `Expand-Archive -LiteralPath '${quote(zip)}' -DestinationPath '${quote(this.dir)}' -Force`
      ],
      180_000
    )
  }

  async devices(): Promise<AdbDevice[]> {
    const result = await this.run(['devices', '-l'])
    const devices: AdbDevice[] = []
    for (const line of result.out.split(/\r?\n/)) {
      if (!line.trim() || line.startsWith('List of devices')) continue
      const parts = line.trim().split(/\s+/)
      const serial = parts[0]
      const state = parts[1]
      if (!serial || !state) continue
      const model = parts
        .find((part) => part.startsWith('model:'))
        ?.slice('model:'.length)
        .replace(/_/g, ' ')
      devices.push({ serial, state, model })
    }
    return devices
  }

  async list(serial: string, dir: string): Promise<PhoneEntry[]> {
    const target = dir && dir !== '' ? dir : '/storage/emulated/0'
    const result = await this.run(['-s', serial, 'shell', `ls -la ${shq(target)}`], 20_000)
    const entries: PhoneEntry[] = []
    for (const line of result.out.split(/\r?\n/)) {
      const match = LS_LINE.exec(line)
      if (!match) continue
      const kind = match[1] === 'd' ? 'folder' : 'file'
      const size = Number(match[3])
      const rawName = match[4]
      const name = (match[1] === 'l' ? rawName.split(' -> ')[0] : rawName).replace(/\r$/, '')
      if (!name || name === '.' || name === '..') continue
      entries.push({ name, path: remoteJoin(target, name), kind, size })
    }
    entries.sort((a, b) => {
      if (a.kind !== b.kind) return a.kind === 'folder' ? -1 : 1
      return a.name.localeCompare(b.name, 'zh')
    })
    return entries
  }

  // Windows 上的 adb pull 用远端原名创建本地文件，中文名会报 Illegal byte sequence，
  // 所以不用 pull：逐个远端条目 exec-out 出内容，本地路径由 Node 自己建，中文名不受影响。
  async pull(
    serial: string,
    paths: string[],
    localDir: string,
    onProgress?: (progress: AdbPullProgress) => void,
    signal?: AbortSignal
  ): Promise<number> {
    fs.mkdirSync(localDir, { recursive: true })
    const items: PullItem[] = []
    // 这次任务新建的文件和文件夹，取消时按倒序删掉，不留半截。
    const created: string[] = []
    try {
      for (const raw of paths) {
        const remote = raw.replace(/\/+$/, '') || '/'
        const name = path.posix.basename(remote) || 'root'
        const local = path.join(localDir, name)
        try {
          await this.collect(serial, remote, local, items, signal)
        } catch (error) {
          const reason = error instanceof Error ? error.message : String(error)
          throw new Error(`${name} 拉取失败：${reason}`)
        }
        if (signal?.aborted) throw new Error('已取消')
      }

      const files = items.filter((item) => item.kind === 'file')
      const totalBytes = files.reduce((sum, item) => sum + item.size, 0)
      let done = 0
      let doneBytes = 0
      const report = (item: PullItem | null, received: number, running: boolean): void => {
        onProgress?.({
          running,
          done,
          total: files.length,
          name: item?.name ?? '',
          received,
          size: item?.size ?? 0,
          doneBytes,
          totalBytes
        })
      }

      report(null, 0, true)
      for (const item of items) {
        if (signal?.aborted) throw new Error('已取消')
        if (item.kind === 'folder') {
          if (!fs.existsSync(item.local)) created.push(item.local)
          fs.mkdirSync(item.local, { recursive: true })
          continue
        }
        if (!fs.existsSync(item.local)) created.push(item.local)
        report(item, 0, true)
        let lastAt = 0
        try {
          await this.pullFile(
            serial,
            item.remote,
            item.local,
            (received) => {
              const now = Date.now()
              if (now - lastAt < 100) return
              lastAt = now
              report(item, received, true)
            },
            signal
          )
        } catch (error) {
          const reason = error instanceof Error ? error.message : String(error)
          throw new Error(`${item.name} 拉取失败：${reason}`)
        }
        done += 1
        doneBytes += item.size
        report(item, item.size, true)
      }
      report(null, 0, false)
      return paths.length
    } catch (error) {
      if (signal?.aborted) {
        for (const target of created.reverse()) removeQuietly(target)
      }
      throw error
    }
  }

  private async collect(
    serial: string,
    remote: string,
    local: string,
    out: PullItem[],
    signal?: AbortSignal
  ): Promise<void> {
    if (signal?.aborted) throw new Error('已取消')
    if (await this.isDir(serial, remote)) {
      out.push({ remote, local, name: path.basename(local), size: 0, kind: 'folder' })
      const entries = await this.list(serial, remote)
      for (const entry of entries) {
        const child = path.join(local, entry.name)
        if (entry.kind === 'folder') await this.collect(serial, entry.path, child, out, signal)
        else
          out.push({ remote: entry.path, local: child, name: entry.name, size: entry.size, kind: 'file' })
      }
      return
    }
    const size = await this.sizeOf(serial, remote)
    out.push({ remote, local, name: path.basename(local), size, kind: 'file' })
  }

  private async isDir(serial: string, remote: string): Promise<boolean> {
    const result = await this.run(
      ['-s', serial, 'shell', `[ -d ${shq(remote)} ] && echo 1 || echo 0`],
      20_000
    )
    return result.out.trim() === '1'
  }

  private async sizeOf(serial: string, remote: string): Promise<number> {
    const result = await this.run(['-s', serial, 'shell', `ls -la ${shq(remote)}`], 20_000)
    for (const line of result.out.split(/\r?\n/)) {
      const match = LS_LINE.exec(line)
      if (match && match[1] !== 'd') return Number(match[3])
    }
    return 0
  }

  private pullFile(
    serial: string,
    remote: string,
    localPath: string,
    onBytes?: (received: number) => void,
    signal?: AbortSignal
  ): Promise<void> {
    fs.mkdirSync(path.dirname(localPath), { recursive: true })
    return this.streamToFile(
      ['-s', serial, 'exec-out', `cat ${shq(remote)}`],
      localPath,
      onBytes,
      signal
    )
  }

  private streamToFile(
    args: string[],
    localPath: string,
    onBytes?: (received: number) => void,
    signal?: AbortSignal
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      if (signal?.aborted) {
        reject(new Error('已取消'))
        return
      }
      const child = spawn(this.exe(), args, { windowsHide: true })
      const out = fs.createWriteStream(localPath)
      let err = ''
      let received = 0
      let settled = false
      const abort = (): void => {
        child.kill()
      }
      const cleanup = (): void => {
        signal?.removeEventListener('abort', abort)
      }
      signal?.addEventListener('abort', abort, { once: true })
      const fail = (error: Error): void => {
        if (settled) return
        settled = true
        cleanup()
        out.destroy()
        removeQuietly(localPath)
        reject(error)
      }
      child.stderr.on('data', (chunk: Buffer) => {
        err += chunk.toString()
      })
      child.stdout.on('data', (chunk: Buffer) => {
        received += chunk.length
        onBytes?.(received)
      })
      child.on('error', fail)
      out.on('error', (error) => {
        child.kill()
        fail(error)
      })
      child.stdout.pipe(out)
      child.on('close', (code) => {
        // 进程退出时 stdout 已结束，写流还要把缓冲落盘，等它真正关闭再判定成败。
        const finalize = (): void => {
          if (settled) return
          settled = true
          cleanup()
          if (signal?.aborted) {
            removeQuietly(localPath)
            reject(new Error('已取消'))
            return
          }
          if (code === 0) {
            resolve()
            return
          }
          removeQuietly(localPath)
          reject(new Error(err.trim() || `adb 退出码 ${code}`))
        }
        if (out.closed) finalize()
        else out.once('close', finalize)
      })
    })
  }

  // 上传走 exec-in 把本地文件流式灌给远端 cat，逐块统计字节上报进度；
  // 目录先递归摊平成文件清单，再按清单逐个建目录、传文件。
  async push(
    serial: string,
    localPaths: string[],
    remoteDir: string,
    onProgress?: (progress: AdbPushProgress) => void
  ): Promise<number> {
    const base = remoteDir.replace(/\/+$/, '')
    const items: PushItem[] = []
    for (const local of localPaths) {
      const name = path.basename(local)
      try {
        this.collectLocal(local, remoteJoin(base || '/', name), items)
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error)
        throw new Error(`${name} 上传失败：${reason}`)
      }
    }

    const files = items.filter((item) => item.kind === 'file')
    const totalBytes = files.reduce((sum, item) => sum + item.size, 0)
    let done = 0
    let doneBytes = 0
    const report = (item: PushItem | null, received: number, running: boolean): void => {
      onProgress?.({
        running,
        done,
        total: files.length,
        name: item?.name ?? '',
        received,
        size: item?.size ?? 0,
        doneBytes,
        totalBytes
      })
    }

    report(null, 0, true)
    for (const item of items) {
      if (item.kind === 'folder') {
        await this.run(['-s', serial, 'shell', `mkdir -p ${shq(item.remote)}`])
        continue
      }
      report(item, 0, true)
      let lastAt = 0
      try {
        await this.pushFile(serial, item.local, item.remote, (received) => {
          const now = Date.now()
          if (now - lastAt < 100) return
          lastAt = now
          report(item, received, true)
        })
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error)
        // 没传完的远端半截文件删掉，免得留下损坏的文件。
        await this.run(['-s', serial, 'shell', `rm -f ${shq(item.remote)}`]).catch(() => {})
        throw new Error(`${item.name} 上传失败：${reason}`)
      }
      done += 1
      doneBytes += item.size
      report(item, item.size, true)
    }
    report(null, 0, false)
    return localPaths.length
  }

  private collectLocal(localPath: string, remotePath: string, out: PushItem[]): void {
    const stat = fs.statSync(localPath)
    if (stat.isDirectory()) {
      out.push({
        local: localPath,
        remote: remotePath,
        name: path.basename(localPath),
        size: 0,
        kind: 'folder'
      })
      for (const entry of fs.readdirSync(localPath)) {
        this.collectLocal(path.join(localPath, entry), remoteJoin(remotePath, entry), out)
      }
      return
    }
    out.push({
      local: localPath,
      remote: remotePath,
      name: path.basename(localPath),
      size: stat.size,
      kind: 'file'
    })
  }

  private pushFile(
    serial: string,
    localPath: string,
    remotePath: string,
    onBytes?: (received: number) => void
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      const child = spawn(
        this.exe(),
        ['-s', serial, 'exec-in', 'sh', '-c', `cat > ${shq(remotePath)}`],
        { windowsHide: true }
      )
      const input = fs.createReadStream(localPath)
      let err = ''
      let received = 0
      let settled = false
      const fail = (error: Error): void => {
        if (settled) return
        settled = true
        input.destroy()
        child.kill()
        reject(error)
      }
      child.stderr.on('data', (chunk: Buffer) => {
        err += chunk.toString()
      })
      child.stdin.on('error', () => {})
      child.on('error', fail)
      input.on('error', fail)
      input.on('data', (chunk: string | Buffer) => {
        received += chunk.length
        onBytes?.(received)
      })
      input.pipe(child.stdin)
      child.on('close', (code) => {
        if (settled) return
        settled = true
        if (code === 0) resolve()
        else reject(new Error(err.trim() || `adb 退出码 ${code}`))
      })
    })
  }

  async rename(serial: string, remote: string, name: string): Promise<string> {
    const clean = checkName(name)
    const parent = path.posix.dirname(remote)
    const next = remoteJoin(parent === '.' ? '/' : parent, clean)
    await this.run(['-s', serial, 'shell', `mv ${shq(remote)} ${shq(next)}`])
    return next
  }

  async remove(serial: string, paths: string[]): Promise<void> {
    if (paths.length === 0) return
    const list = paths.map(shq).join(' ')
    await this.run(['-s', serial, 'shell', `rm -rf ${list}`])
  }

  async mkdir(serial: string, dir: string, name: string): Promise<string> {
    const clean = checkName(name)
    const next = remoteJoin(dir, clean)
    await this.run(['-s', serial, 'shell', `mkdir -p ${shq(next)}`])
    return next
  }
}
