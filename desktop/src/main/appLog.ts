import fs from 'node:fs'
import path from 'node:path'

const MAX_BYTES = 2 * 1024 * 1024

// 日志时间统一按中国时间记录，不跟随系统时区。
const stamp = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false
})

function now(): string {
  const ms = String(new Date().getMilliseconds()).padStart(3, '0')
  return `${stamp.format(new Date())}.${ms}`
}

// 主进程与 xlan 服务的输出统一落到 data/logs/app.log，日志窗口直接读这个文件。
export class AppLog {
  readonly file: string

  constructor(dir: string) {
    fs.mkdirSync(dir, { recursive: true })
    this.file = path.join(dir, 'app.log')
  }

  write(source: string, line: string): void {
    try {
      try {
        if (fs.statSync(this.file).size > MAX_BYTES) {
          fs.renameSync(this.file, `${this.file}.1`)
        }
      } catch {
        // 文件还不存在，直接写
      }
      fs.appendFileSync(this.file, `${now()} ${source} ${line}\n`)
    } catch {
      // 日志写不进去不影响主流程
    }
  }

  readTail(maxLines = 2000): string[] {
    try {
      const text = fs.readFileSync(this.file, 'utf8')
      const lines = text.split(/\r?\n/).filter((line) => line !== '')
      return lines.slice(-maxLines)
    } catch {
      return []
    }
  }
}
