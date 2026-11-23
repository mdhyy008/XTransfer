import fs from 'node:fs'
import path from 'node:path'
import type { AdbDownloadRecord } from '../shared/types'

const MAX = 50

function normalize(value: unknown): AdbDownloadRecord | null {
  if (!value || typeof value !== 'object') return null
  const raw = value as Partial<AdbDownloadRecord>
  if (typeof raw.id !== 'string' || !raw.id) return null
  return {
    id: raw.id,
    time: typeof raw.time === 'number' && Number.isFinite(raw.time) ? raw.time : 0,
    dir: typeof raw.dir === 'string' ? raw.dir : '',
    names: Array.isArray(raw.names)
      ? raw.names.filter((name): name is string => typeof name === 'string')
      : [],
    total: typeof raw.total === 'number' && Number.isFinite(raw.total) ? raw.total : 0,
    bytes: typeof raw.bytes === 'number' && Number.isFinite(raw.bytes) ? raw.bytes : 0,
    // 上次还在下载就退出了，没有完成记录，按中断处理。
    status:
      raw.status === 'done' ? 'done' : raw.status === 'canceled' ? 'canceled' : 'failed',
    error: typeof raw.error === 'string' && raw.error ? raw.error : undefined
  }
}

export class DownloadStore {
  private records: AdbDownloadRecord[] = []

  constructor(private readonly file: string) {
    try {
      const raw = JSON.parse(fs.readFileSync(file, 'utf8')) as unknown
      if (Array.isArray(raw)) {
        this.records = raw
          .map(normalize)
          .filter((item): item is AdbDownloadRecord => item !== null)
          .slice(0, MAX)
      }
    } catch {
      // 首次运行或文件损坏时从空记录开始
    }
  }

  list(): AdbDownloadRecord[] {
    return this.records
  }

  start(record: Omit<AdbDownloadRecord, 'status'>): AdbDownloadRecord {
    const next: AdbDownloadRecord = { ...record, status: 'running' }
    this.records = [next, ...this.records].slice(0, MAX)
    this.save()
    return next
  }

  remove(id: string): void {
    this.records = this.records.filter((item) => item.id !== id)
    this.save()
  }

  finish(id: string, patch: Partial<AdbDownloadRecord>): void {
    let changed = false
    this.records = this.records.map((item) => {
      if (item.id !== id) return item
      changed = true
      return { ...item, ...patch }
    })
    if (changed) this.save()
  }

  private save(): void {
    fs.mkdirSync(path.dirname(this.file), { recursive: true })
    fs.writeFileSync(this.file, JSON.stringify(this.records, null, 2), 'utf8')
  }
}
