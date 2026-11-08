import { spawn, spawnSync, type ChildProcessWithoutNullStreams } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { randomUUID } from 'node:crypto'
import * as pty from 'node-pty'
import type { MessageStatus } from '../shared/types'
import type { Db } from './db'

type JobFile = {
  messageId: string
  name: string
  sourcePath: string
  kind: 'file' | 'folder'
  size: number
}

type Job = {
  id: string
  peerId: string
  file: JobFile
  term: pty.IPty | null
  child: ChildProcessWithoutNullStreams | null
  cancelled: boolean
  finished: boolean
  lastError: string
}

const ANSI_RE = /\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07/g
const PROGRESS_RE =
  /^\s*发送\s+(.+?)\s+(\d+(?:\.\d+)?)%\s+([\d.]+\s*[KMGTPE]?B)\s*\/\s*([\d.]+\s*[KMGTPE]?B)\s+([\d.]+\s*[KMGTPE]?B)\/s\s*$/

const TERMINAL: MessageStatus[] = [
  'done',
  'duplicate',
  'failed',
  'canceled',
  'peer_canceled',
  'rejected'
]
// 同一批最多同时跑三个文件，其余排队等空位。每个文件一个独立进程，
// 取消时只影响它自己。
const MAX_ACTIVE = 3

function stripAnsi(line: string): string {
  return line.replace(ANSI_RE, '')
}

function parseHuman(text: string): number {
  const m = /^([\d.]+)\s*([KMGTPE]?)B$/.exec(text.trim())
  if (!m) return 0
  const unit = m[2] ? ' KMGTPE'.indexOf(m[2]) : 0
  return Math.round(parseFloat(m[1]) * Math.pow(1024, Math.max(0, unit)))
}

function isTerminal(status: MessageStatus): boolean {
  return TERMINAL.includes(status)
}

export class FileSender {
  private readonly jobs = new Map<string, Job>()
  private readonly pending: Job[] = []
  private active = 0

  constructor(
    private readonly exe: string,
    private readonly cwd: string,
    private readonly db: Db,
    private readonly emit: (messageIds?: string[]) => void
  ) {}

  send(peerId: string, peerName: string, paths: string[], selfId: string, selfName: string): string | null {
    const jobs: Job[] = []
    for (const sourcePath of paths) {
      const stat = fs.statSync(sourcePath, { throwIfNoEntry: false })
      if (!stat) continue
      const kind = stat.isDirectory() ? 'folder' : 'file'
      const messageId = randomUUID()
      this.db.addOutboundFile({
        convId: peerId,
        peerName,
        id: messageId,
        sourcePath,
        fileName: path.basename(sourcePath),
        kind,
        size: stat.size,
        selfId,
        selfName
      })
      jobs.push(
        this.createJob(peerId, {
          messageId,
          name: path.basename(sourcePath),
          sourcePath,
          kind,
          size: stat.size
        })
      )
    }
    if (jobs.length === 0) return null
    for (const job of jobs) this.pending.push(job)
    this.emit()
    this.pump()
    return jobs[0].id
  }

  retry(peerId: string, messageId: string): boolean {
    const message = this.db.getMessage(messageId)
    if (!message || !message.local_path) return false
    if (this.jobFor(messageId)) return false
    this.db.resetForRetry(messageId)
    const job = this.createJob(peerId, {
      messageId,
      name: message.file_name || path.basename(message.local_path),
      sourcePath: message.local_path,
      kind: message.kind === 'folder' ? 'folder' : 'file',
      size: message.file_size ?? 0
    })
    this.pending.push(job)
    this.emit()
    this.pump()
    return true
  }

  cancelByMessage(messageId: string): void {
    const job = this.jobFor(messageId)
    if (job) {
      this.cancelJob(job)
      return
    }
    // 进程已经退出、任务已摘掉的，也要把状态收口，不能让按钮点了没反应。
    const message = this.db.getMessage(messageId)
    if (message && !isTerminal(message.status)) {
      this.db.setMessageStatus(messageId, 'canceled')
      this.emit([messageId])
    }
  }

  cancelPeer(peerId: string): void {
    for (const job of [...this.jobs.values()]) {
      if (job.peerId === peerId) this.cancelJob(job)
    }
  }

  dispose(): void {
    this.pending.length = 0
    for (const job of this.jobs.values()) {
      job.cancelled = true
      const pid = job.term?.pid
      if (pid) {
        spawnSync('taskkill', ['/T', '/F', '/PID', String(pid)], {
          windowsHide: true,
          stdio: 'ignore'
        })
      }
      job.term?.kill()
      job.child?.kill()
    }
    this.jobs.clear()
  }

  private jobFor(messageId: string): Job | undefined {
    for (const job of this.jobs.values()) {
      if (job.file.messageId === messageId) return job
    }
    return undefined
  }

  private cancelJob(job: Job): void {
    if (job.cancelled || job.finished) return
    job.cancelled = true
    const queued = this.pending.indexOf(job)
    if (queued >= 0) {
      this.pending.splice(queued, 1)
      this.jobs.delete(job.id)
    }
    // 先让 CLI 自己收尾：它会通知对端「对方已取消」。
    // 给它一点时间把请求发出去，再强杀兜底。
    const pid = job.term?.pid
    if (job.term) {
      try {
        job.term.write('\x03')
      } catch {
        // pty 已经退出，直接走下面的强杀。
      }
      setTimeout(() => {
        if (job.finished) return
        if (pid) {
          spawnSync('taskkill', ['/T', '/F', '/PID', String(pid)], {
            windowsHide: true,
            stdio: 'ignore'
          })
        }
        job.term?.kill()
      }, 1200)
    } else {
      job.child?.kill()
    }
    const message = this.db.getMessage(job.file.messageId)
    if (message && !isTerminal(message.status)) {
      this.db.setMessageStatus(job.file.messageId, 'canceled')
    }
    this.emit([job.file.messageId])
  }

  private createJob(peerId: string, file: JobFile): Job {
    const job: Job = {
      id: randomUUID(),
      peerId,
      file,
      term: null,
      child: null,
      cancelled: false,
      finished: false,
      lastError: ''
    }
    this.jobs.set(job.id, job)
    return job
  }

  private run(job: Job): void {
    const args = ['send', job.file.sourcePath, '--to', job.peerId, '--json']
    const env: Record<string, string> = {}
    for (const [key, value] of Object.entries(process.env)) {
      if (value !== undefined) env[key] = value
    }

    try {
      const term = pty.spawn(this.exe, args, {
        name: 'xterm-256color',
        cols: 500,
        rows: 40,
        cwd: this.cwd,
        env
      })
      job.term = term
      this.attachStream(
        job,
        (handler) => term.onData(handler),
        (handler) => term.onExit(({ exitCode }) => handler(exitCode))
      )
    } catch {
      const child = spawn(this.exe, args, { cwd: this.cwd, windowsHide: true })
      job.child = child
      this.attachStream(
        job,
        (handler) => child.stdout.on('data', (chunk: Buffer) => handler(chunk.toString('utf8'))),
        (handler) => child.on('exit', (code) => handler(code ?? 1))
      )
    }
  }

  private attachStream(
    job: Job,
    subscribe: (handler: (chunk: string) => void) => void,
    onExit: (handler: (code: number) => void) => void
  ): void {
    let buffer = ''
    const feed = (chunk: string): void => {
      buffer += chunk
      for (;;) {
        const match = /[\r\n]/.exec(buffer)
        if (!match) break
        const line = buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + 1)
        this.handleLine(job, stripAnsi(line))
      }
    }
    subscribe(feed)
    onExit((code) => {
      if (buffer) this.handleLine(job, stripAnsi(buffer))
      this.finish(job, code)
    })
  }

  private handleLine(job: Job, raw: string): void {
    // 已经在取消途中的进程，晚到的进度和结果都不再采纳，
    // 免得把界面上的「已取消」又改回传输中或失败。
    if (job.cancelled) return
    const line = raw.trim()
    if (!line) return

    if (line.startsWith('{')) {
      let data: Record<string, unknown>
      try {
        data = JSON.parse(line) as Record<string, unknown>
      } catch {
        return
      }
      const event = typeof data.event === 'string' ? data.event : ''
      if (event === 'progress' || event === 'wait' || event === 'queue') {
        if (event === 'wait') {
          this.db.setMessageStatus(job.file.messageId, 'sending')
        } else if (event === 'queue') {
          this.db.setMessageStatus(job.file.messageId, 'queued')
        } else {
          const received = Number(data.received) || 0
          const total = Number(data.total) || job.file.size
          const speed = Number(data.speed) || 0
          this.db.setProgress(job.file.messageId, received, total > 0 ? total : job.file.size, speed)
        }
        this.emit([job.file.messageId])
        return
      }
      if (!('ok' in data)) return
      this.handleResult(job, data)
      return
    }

    const match = PROGRESS_RE.exec(line)
    if (!match) return
    const received = parseHuman(match[3])
    const total = parseHuman(match[4])
    const speed = parseHuman(match[5])
    this.db.setProgress(
      job.file.messageId,
      received,
      total > 0 ? total : job.file.size,
      speed
    )
    this.emit([job.file.messageId])
  }

  private handleResult(job: Job, data: Record<string, unknown>): void {
    if (data.ok) {
      const size = job.file.size || Number(data.size) || 0
      if (size > 0) this.db.setProgress(job.file.messageId, size, size, 0)
      // 对端已有同名同内容的文件，CLI 不会再传，界面要说明这一点。
      this.db.setMessageStatus(job.file.messageId, data.duplicate ? 'duplicate' : 'done')
    } else {
      job.lastError = String(data.code || 'ERROR')
      this.db.setMessageStatus(job.file.messageId, 'failed', job.lastError)
    }
    this.emit([job.file.messageId])
  }

  private finish(job: Job, exitCode: number): void {
    if (job.finished) return
    job.finished = true
    job.term = null
    job.child = null

    const message = this.db.getMessage(job.file.messageId)
    if (message && !isTerminal(message.status)) {
      if (job.cancelled) {
        this.db.setMessageStatus(job.file.messageId, 'canceled')
      } else if (exitCode === 0) {
        this.db.setMessageStatus(job.file.messageId, 'done')
      } else {
        this.db.setMessageStatus(
          job.file.messageId,
          'failed',
          job.lastError || `EXIT_${exitCode}`
        )
      }
    }

    this.jobs.delete(job.id)
    this.active = Math.max(0, this.active - 1)
    this.emit([job.file.messageId])
    this.pump()
  }

  // 有空位就启动下一个排队任务。
  private pump(): void {
    while (this.active < MAX_ACTIVE) {
      const job = this.pending.shift()
      if (!job) return
      this.active += 1
      this.run(job)
    }
  }
}
