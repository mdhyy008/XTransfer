import type { Message } from '../../shared/types'

// 底层网络错误对普通用户没有意义，原样保留在末尾追问一句中文解释。
const ERROR_HINTS: [RegExp, string][] = [
  [
    /connectex|connected host has failed to respond|did not properly respond after a period of time/i,
    '连不上对方设备：对方可能已经离线，或不在同一个局域网。'
  ],
  [/connection refused/i, '对方拒绝连接：对方设备上的 xlan 服务可能没有运行。'],
  [/i\/o timeout|context deadline exceeded|timeout/i, '连接超时：网络不通或对方设备没有响应。'],
  [/no route to host|network is unreachable/i, '网络不可达：请确认两台设备连接在同一个局域网。'],
  [/no such host|enotfound/i, '找不到对方设备：请确认设备在线后重试。'],
  [/certificate|x509/i, '证书校验失败：重启 xlan 服务后再试。'],
  [/eaddrinuse|address already in use/i, '端口被占用：请检查是否有其他程序占用了服务端口。']
]

export function describeError(text: string): string {
  if (!text) return text
  for (const [pattern, hint] of ERROR_HINTS) {
    if (pattern.test(text)) return text.includes(hint) ? text : `${text}\n${hint}`
  }
  return text
}

export function previewOf(message: Message | undefined): string {
  if (!message) return ''
  const prefix = message.direction === 'out' ? '我：' : ''
  if (message.kind === 'text') return prefix + (message.body ?? '')
  if (message.kind === 'folder') return `${prefix}[文件夹] ${message.file_name ?? ''}`
  return `${prefix}[文件] ${message.file_name ?? ''}`
}

export function lastMessageMap(messages: Message[]): Map<string, Message> {
  const map = new Map<string, Message>()
  for (const message of messages) {
    const old = map.get(message.conversation_id)
    if (!old || message.created_at >= old.created_at) map.set(message.conversation_id, message)
  }
  return map
}

const timeFmt = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false
})

const dateFmt = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false
})

const dayFmt = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  month: '2-digit',
  day: '2-digit'
})

const dayKeyFmt = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit'
})

function dayKey(sec: number): string {
  return dayKeyFmt.format(new Date(sec * 1000))
}

export function formatTime(sec: number, withDate = false): string {
  if (!sec) return ''
  return (withDate ? dateFmt : timeFmt).format(new Date(sec * 1000))
}

// 聊天里的时间整条消息：今天只给时分，昨天加「昨天」，更早带月日。
export function formatChatTime(sec: number): string {
  if (!sec) return ''
  const now = Date.now() / 1000
  const time = timeFmt.format(new Date(sec * 1000))
  if (dayKey(sec) === dayKey(now)) return time
  if (dayKey(sec) === dayKey(now - 86_400)) return `昨天 ${time}`
  return dateFmt.format(new Date(sec * 1000))
}

export function formatWhen(sec: number): string {
  if (!sec) return ''
  const now = Date.now() / 1000
  return now - sec < 24 * 3600 ? timeFmt.format(new Date(sec * 1000)) : dayFmt.format(new Date(sec * 1000))
}

export function formatSize(bytes?: number): string {
  if (!bytes || bytes <= 0) return ''
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value >= 10 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`
}
