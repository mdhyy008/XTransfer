import dgram from 'node:dgram'
import http from 'node:http'
import os from 'node:os'
import { randomBytes } from 'node:crypto'
import type { AddressInfo } from 'node:net'
import type { Item, WebChatStatus } from '../shared/types'
import type { Db } from './db'

const WEB_PORT = 43100
const PUBLIC_PORT = 43002
const MAX_BODY = 900
const GUEST_RE = /^web[0-9a-f]{10}$/
const ACK_WINDOW_MS = 260
// 网页每 1.5 秒轮询一次，5 秒没动静就当离线。
const GUEST_ONLINE_MS = 5000

type WebChatOptions = {
  db: Db
  selfId: () => string
  host: () => string
  peers: () => { addr?: string }[]
  onChange: () => void
}

type WebMessage = {
  id: string
  from_id: string
  from_name: string | null
  body: string
  created_at: number
}

function ipToInt(ip: string): number | null {
  const parts = ip.split('.')
  if (parts.length !== 4) return null
  let value = 0
  for (const part of parts) {
    const octet = Number(part)
    if (!Number.isInteger(octet) || octet < 0 || octet > 255) return null
    value = (value << 8) | octet
  }
  return value >>> 0
}

function broadcastOf(ip: string, mask: string | undefined): string | null {
  const base = ipToInt(ip)
  const netmask = mask ? ipToInt(mask) : null
  if (base === null || netmask === null) return null
  const value = (base | (~netmask >>> 0)) >>> 0
  return [24, 16, 8, 0].map((shift) => (value >>> shift) & 255).join('.')
}

function broadcastTargets(peers: { addr?: string }[]): string[] {
  const targets = new Set<string>()
  for (const list of Object.values(os.networkInterfaces())) {
    for (const address of list ?? []) {
      const family = String(address.family)
      if ((family !== 'IPv4' && family !== '4') || address.internal) continue
      if (/^169\.254\./.test(address.address)) continue
      const broadcast = broadcastOf(address.address, address.netmask)
      if (broadcast) targets.add(broadcast)
    }
  }
  // 有些网卡或无线 AP 不放行广播包，再按已发现的设备逐个单播一份。
  for (const peer of peers) {
    if (peer.addr && ipToInt(peer.addr) !== null) targets.add(peer.addr)
  }
  if (targets.size === 0) targets.add('255.255.255.255')
  return [...targets]
}

function listen(server: http.Server, port: number): Promise<number> {
  return new Promise((resolve, reject) => {
    const onError = (error: NodeJS.ErrnoException): void => {
      if (error.code === 'EADDRINUSE' && port !== 0) {
        listen(server, 0).then(resolve, reject)
        return
      }
      reject(error)
    }
    server.once('error', onError)
    server.listen(port, '0.0.0.0', () => {
      server.removeListener('error', onError)
      resolve((server.address() as AddressInfo).port)
    })
  })
}

export class WebChatService {
  private server: http.Server | null = null
  private port = 0
  private error = ''
  private guests = new Map<string, number>()

  constructor(private readonly options: WebChatOptions) {}

  status(): WebChatStatus {
    const running = this.server !== null
    const host = this.options.host() || '127.0.0.1'
    const now = Date.now()
    let online = 0
    for (const at of this.guests.values()) {
      if (now - at <= GUEST_ONLINE_MS) online += 1
    }
    return {
      running,
      url: running ? `http://${host}:${this.port}/` : '',
      port: this.port,
      online: running ? online : 0,
      error: this.error || undefined
    }
  }

  async start(): Promise<WebChatStatus> {
    if (this.server) return this.status()
    const server = http.createServer((req, res) => {
      try {
        this.handle(req, res)
      } catch {
        try {
          res.writeHead(500)
          res.end()
        } catch {
          // 连接已经断开
        }
      }
    })
    try {
      this.port = await listen(server, WEB_PORT)
      this.server = server
      this.error = ''
      server.on('error', (err) => {
        this.error = err.message
      })
    } catch (error) {
      this.server = null
      this.port = 0
      this.error = error instanceof Error ? error.message : '启动失败'
    }
    return this.status()
  }

  async stop(): Promise<WebChatStatus> {
    const server = this.server
    this.server = null
    this.port = 0
    this.guests.clear()
    if (server) {
      await new Promise<void>((resolve) => {
        server.close(() => resolve())
      })
    }
    return this.status()
  }

  private handle(req: http.IncomingMessage, res: http.ServerResponse): void {
    const url = new URL(req.url ?? '/', 'http://localhost')
    const send = (code: number, body: string, type = 'application/json; charset=utf-8'): void => {
      res.writeHead(code, {
        'content-type': type,
        'cache-control': 'no-store',
        'x-content-type-options': 'nosniff'
      })
      res.end(body)
    }
    if (req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
      send(200, PAGE_HTML, 'text/html; charset=utf-8')
      return
    }
    if (req.method === 'GET' && url.pathname === '/api/messages') {
      const me = url.searchParams.get('me')
      if (me && GUEST_RE.test(me)) {
        const now = Date.now()
        this.guests.set(me, now)
        for (const [id, at] of this.guests) {
          if (now - at > GUEST_ONLINE_MS) this.guests.delete(id)
        }
      }
      send(200, JSON.stringify({ messages: this.options.db.listPublicTexts(300) }))
      return
    }
    if (req.method === 'POST' && url.pathname === '/api/send') {
      const chunks: Buffer[] = []
      let size = 0
      let overflow = false
      req.on('data', (chunk: Buffer) => {
        size += chunk.length
        if (size > 16 * 1024) {
          overflow = true
          return
        }
        chunks.push(chunk)
      })
      req.on('end', () => {
        if (overflow) {
          send(413, JSON.stringify({ ok: false, error: '内容过长' }))
          return
        }
        let payload: unknown = null
        try {
          payload = JSON.parse(Buffer.concat(chunks).toString('utf8'))
        } catch {
          send(400, JSON.stringify({ ok: false, error: '请求无效' }))
          return
        }
        void this.send(payload)
          .then((result) => {
            send(result.ok ? 200 : 400, JSON.stringify(result))
          })
          .catch(() => {
            send(500, JSON.stringify({ ok: false, error: '发送失败' }))
          })
      })
      return
    }
    send(404, JSON.stringify({ ok: false }))
  }

  private async send(payload: unknown): Promise<{ ok: boolean; error?: string; from?: string }> {
    const raw = (payload ?? {}) as Record<string, unknown>
    const body = typeof raw.body === 'string' ? raw.body.trim() : ''
    if (!body) return { ok: false, error: '内容为空' }
    if (Buffer.byteLength(body, 'utf8') > MAX_BODY) return { ok: false, error: '内容过长' }
    const name =
      (typeof raw.name === 'string' ? raw.name.trim().slice(0, 15) : '') || '网页访客'
    const guest =
      typeof raw.from === 'string' && GUEST_RE.test(raw.from)
        ? raw.from
        : `web${randomBytes(5).toString('hex')}`
    const id = `w${Date.now().toString(16)}${randomBytes(3).toString('hex')}`
    const ts = Math.floor(Date.now() / 1000)

    try {
      await this.broadcast({ v: 1, t: 'msg', id, sid: guest, sname: name, channel: 'public', body, ts })
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : '发送失败' }
    }

    const item: Item = {
      id,
      kind: 'text',
      from_id: guest,
      from_name: name,
      to_id: '',
      channel: 'public',
      body,
      status: 'done',
      created_at: ts
    }
    this.options.db.ingestItem(item, this.options.selfId())
    this.options.onChange()
    return { ok: true, from: guest }
  }

  private broadcast(packet: Record<string, unknown>): Promise<void> {
    return new Promise((resolve, reject) => {
      const socket = dgram.createSocket('udp4')
      const targets = broadcastTargets(this.options.peers())
      const data = Buffer.from(JSON.stringify(packet))
      let sent = 0
      let failed = 0
      let done = false
      const finish = (error?: Error): void => {
        if (done) return
        done = true
        try {
          socket.close()
        } catch {
          // 已经关了
        }
        if (error) reject(error)
        else resolve()
      }
      socket.on('error', (error) => finish(error))
      socket.on('message', (data) => {
        try {
          const ack = JSON.parse(data.toString('utf8')) as { t?: string; id?: string }
          if (ack.t === 'ack' && ack.id === packet.id) {
            // 收到对方的回执就说明已经送达，不必再等剩下的窗口。
            finish()
          }
        } catch {
          // 非 JSON 包直接忽略
        }
      })
      socket.bind(0, () => {
        try {
          socket.setBroadcast(true)
        } catch {
          // 部分网卡不支持广播，下面发送时会报错。
        }
        const total = targets.length * 2
        for (let round = 0; round < 2; round += 1) {
          for (const target of targets) {
            socket.send(data, PUBLIC_PORT, target, (error) => {
              if (error) failed += 1
              else sent += 1
              if (sent + failed < total) return
              if (sent === 0) finish(new Error('未连接到局域网'))
            })
          }
        }
        setTimeout(() => {
          if (sent === 0) finish(new Error('未连接到局域网'))
          else finish()
        }, ACK_WINDOW_MS)
      })
    })
  }
}

const PAGE_HTML = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, maximum-scale=1.0, user-scalable=no">
<meta name="theme-color" content="#f6f4f9" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#141218" media="(prefers-color-scheme: dark)">
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Cdefs%3E%3ClinearGradient id='g' x1='0' y1='0' x2='1' y2='1'%3E%3Cstop offset='0' stop-color='%2363c4f0'/%3E%3Cstop offset='1' stop-color='%230070d3'/%3E%3C/linearGradient%3E%3C/defs%3E%3Crect width='64' height='64' rx='15' fill='url(%23g)'/%3E%3Cpath d='M18 20h28a5 5 0 0 1 5 5v14a5 5 0 0 1-5 5H33l-9 8v-8h-6a5 5 0 0 1-5-5V25a5 5 0 0 1 5-5z' fill='%23fff'/%3E%3Ccircle cx='24.5' cy='32' r='2.6' fill='%232b8fdc'/%3E%3Ccircle cx='32' cy='32' r='2.6' fill='%232b8fdc'/%3E%3Ccircle cx='39.5' cy='32' r='2.6' fill='%232b8fdc'/%3E%3C/svg%3E">
<title>X互传 · 局域网广播</title>
<style>
:root {
  color-scheme: light dark;
  --bg: #f6f4f9;
  --panel: rgba(255, 255, 255, 0.86);
  --card: #ffffff;
  --line: rgba(20, 18, 24, 0.08);
  --text: #1d1a23;
  --muted: #8b8695;
  --mine: #6d5bd0;
  --mine-text: #ffffff;
  --field: rgba(20, 18, 24, 0.05);
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #141218;
    --panel: rgba(30, 27, 36, 0.86);
    --card: #262230;
    --line: rgba(255, 255, 255, 0.08);
    --text: #f2eff7;
    --muted: #9b95a8;
    --mine: #7c68e0;
    --mine-text: #ffffff;
    --field: rgba(255, 255, 255, 0.08);
  }
}
* { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
html, body { height: 100%; -webkit-text-size-adjust: 100%; }
body {
  margin: 0;
  background: var(--bg);
  color: var(--text);
  font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif;
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100dvh;
  font-size: 15px;
}
header, main, footer { width: 100%; max-width: 760px; }
header {
  flex-shrink: 0;
  padding: calc(10px + env(safe-area-inset-top)) calc(14px + env(safe-area-inset-right)) 10px calc(14px + env(safe-area-inset-left));
  background: var(--panel);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  border-bottom: 1px solid var(--line);
}
.bar { display: flex; align-items: center; gap: 10px; }
.title { flex: 1; font-size: 17px; font-weight: 600; display: flex; align-items: center; gap: 7px; }
.dot { width: 7px; height: 7px; border-radius: 50%; background: var(--muted); opacity: 0.5; transition: background-color 200ms ease, opacity 200ms ease; }
.dot.on { background: #34c759; opacity: 1; }
#name {
  width: 148px;
  border: none;
  outline: none;
  background: var(--field);
  color: var(--text);
  border-radius: 12px;
  padding: 9px 12px;
  font: inherit;
  font-size: 16px;
  text-align: center;
}
#name::placeholder { color: var(--muted); }
main {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px calc(14px + env(safe-area-inset-right)) 4px calc(14px + env(safe-area-inset-left));
  display: flex;
  flex-direction: column;
  gap: 10px;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior: contain;
}
main:empty::after {
  content: "暂无消息";
  margin: auto;
  color: var(--muted);
  font-size: 13px;
}
.row { display: flex; flex-direction: column; align-items: flex-start; max-width: 84%; animation: pop 160ms ease-out; }
.row.mine { align-self: flex-end; align-items: flex-end; }
@keyframes pop {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: none; }
}
.who { font-size: 11.5px; color: var(--muted); margin: 0 0 3px 11px; }
.bubble {
  padding: 9px 12px;
  border-radius: 16px;
  border-top-left-radius: 6px;
  background: var(--card);
  box-shadow: 0 1px 2px rgba(20, 18, 24, 0.06);
  line-height: 1.45;
  white-space: pre-wrap;
  word-break: break-word;
  user-select: text;
}
.row.mine .bubble {
  background: var(--mine);
  color: var(--mine-text);
  border-top-left-radius: 16px;
  border-top-right-radius: 6px;
  box-shadow: none;
}
.time { align-self: center; font-size: 11.5px; color: var(--muted); margin: 6px 0 2px; }
footer {
  flex-shrink: 0;
  padding: 8px calc(12px + env(safe-area-inset-right)) calc(8px + env(safe-area-inset-bottom)) calc(12px + env(safe-area-inset-left));
  background: var(--panel);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  border-top: 1px solid var(--line);
}
.composer { display: flex; align-items: flex-end; gap: 8px; }
#text {
  flex: 1;
  min-height: 38px;
  max-height: 120px;
  resize: none;
  border: 1px solid var(--line);
  outline: none;
  background: var(--field);
  color: var(--text);
  border-radius: 18px;
  padding: 9px 13px;
  font: inherit;
  font-size: 16px;
  line-height: 1.35;
}
#text::placeholder { color: var(--muted); }
#send {
  flex-shrink: 0;
  border: none;
  height: 38px;
  padding: 0 17px;
  border-radius: 19px;
  background: var(--mine);
  color: #fff;
  font: inherit;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 160ms ease, transform 160ms ease;
}
#send:disabled { opacity: 0.45; }
#send:active { transform: scale(0.96); }
.tip { min-height: 16px; margin-top: 4px; text-align: center; font-size: 11.5px; color: #e0524d; }
</style>
</head>
<body>
<header>
  <div class="bar">
    <div class="title">局域网广播<span id="dot" class="dot"></span></div>
    <input id="name" maxlength="15" placeholder="昵称" autocomplete="off" spellcheck="false">
  </div>
</header>
<main id="list"></main>
<footer>
  <div class="composer">
    <textarea id="text" rows="1" placeholder="发消息"></textarea>
    <button id="send">发送</button>
  </div>
  <div id="tip" class="tip"></div>
</footer>
<script>
(function () {
  var MAX_BODY = 900
  var myId = localStorage.getItem('xlan.web.id') || ''
  if (!/^web[0-9a-f]{10}$/.test(myId)) {
    var bytes = new Uint8Array(5)
    crypto.getRandomValues(bytes)
    myId = 'web'
    for (var i = 0; i < bytes.length; i += 1) {
      myId += bytes[i].toString(16).padStart(2, '0')
    }
    localStorage.setItem('xlan.web.id', myId)
  }
  var nameInput = document.getElementById('name')
  function platformName() {
    var ua = navigator.userAgent || ''
    if (/Android/i.test(ua)) return 'Android'
    if (/iPhone|iPad|iPod/i.test(ua)) return 'iOS'
    if (/Windows/i.test(ua)) return 'Windows'
    if (/Macintosh|Mac OS X/i.test(ua)) return 'Mac'
    if (/Linux/i.test(ua)) return 'Linux'
    return '网页'
  }
  var defaultName = platformName() + '访客' + Math.floor(1000 + Math.random() * 9000)
  var savedName = localStorage.getItem('xlan.web.name') || ''
  if (!savedName) {
    savedName = defaultName
    localStorage.setItem('xlan.web.name', savedName)
  }
  nameInput.value = savedName
  nameInput.addEventListener('input', function () {
    var value = nameInput.value.slice(0, 15)
    if (value.trim()) localStorage.setItem('xlan.web.name', value)
  })
  nameInput.addEventListener('blur', function () {
    if (nameInput.value.trim()) return
    nameInput.value = defaultName
    localStorage.setItem('xlan.web.name', defaultName)
  })
  var list = document.getElementById('list')
  var text = document.getElementById('text')
  var sendBtn = document.getElementById('send')
  var tip = document.getElementById('tip')
  var dot = document.getElementById('dot')
  var lastJson = ''
  var stick = true

  list.addEventListener('scroll', function () {
    stick = list.scrollHeight - list.scrollTop - list.clientHeight < 60
  })

  var timeFmt = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hour12: false
  })
  var dateFmt = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false
  })
  var dayKeyFmt = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'
  })
  function timeLabel(ts) {
    var date = new Date((ts || 0) * 1000)
    var now = new Date()
    var time = timeFmt.format(date)
    var key = dayKeyFmt.format(date)
    if (key === dayKeyFmt.format(now)) return time
    if (key === dayKeyFmt.format(new Date(now.getTime() - 86400000))) return '昨天 ' + time
    return dateFmt.format(date)
  }

  function render(messages) {
    var json = JSON.stringify(messages)
    if (json === lastJson) return
    lastJson = json
    var fromBottom = list.scrollHeight - list.scrollTop
    list.textContent = ''
    var TIME_GAP = 5 * 60
    for (var i = 0; i < messages.length; i += 1) {
      var msg = messages[i]
      var prev = i > 0 ? messages[i - 1] : null
      if (!prev || (msg.created_at || 0) - (prev.created_at || 0) >= TIME_GAP) {
        var time = document.createElement('div')
        time.className = 'time'
        time.textContent = timeLabel(msg.created_at)
        list.appendChild(time)
      }
      var mine = msg.from_id === myId
      var row = document.createElement('div')
      row.className = mine ? 'row mine' : 'row'
      if (!mine) {
        var who = document.createElement('div')
        who.className = 'who'
        who.textContent = msg.from_name || '匿名'
        row.appendChild(who)
      }
      var bubble = document.createElement('div')
      bubble.className = 'bubble'
      bubble.textContent = msg.body || ''
      row.appendChild(bubble)
      list.appendChild(row)
    }
    if (stick) list.scrollTop = list.scrollHeight
    else list.scrollTop = Math.max(0, list.scrollHeight - fromBottom)
  }

  var fetching = false
  function refresh() {
    if (fetching) return
    fetching = true
    fetch('/api/messages?me=' + encodeURIComponent(myId), { cache: 'no-store' })
      .then(function (res) { return res.json() })
      .then(function (data) {
        dot.className = 'dot on'
        render(data.messages || [])
      })
      .catch(function () {
        dot.className = 'dot'
      })
      .then(function () { fetching = false })
  }

  var tipTimer = null
  function showTip(message) {
    tip.textContent = message
    clearTimeout(tipTimer)
    tipTimer = setTimeout(function () { tip.textContent = '' }, 2200)
  }

  function send() {
    var body = text.value.trim()
    if (!body || sendBtn.disabled) return
    if (new TextEncoder().encode(body).length > MAX_BODY) {
      showTip('内容过长')
      return
    }
    var name = nameInput.value.trim()
    if (!name) {
      name = defaultName
      nameInput.value = name
      localStorage.setItem('xlan.web.name', name)
    }
    sendBtn.disabled = true
    fetch('/api/send', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        from: myId,
        name: name,
        body: body
      })
    })
      .then(function (res) { return res.json() })
      .then(function (data) {
        if (!data.ok) {
          showTip(data.error || '发送失败')
          return
        }
        if (data.from && data.from !== myId) {
          myId = data.from
          localStorage.setItem('xlan.web.id', myId)
        }
        text.value = ''
        text.style.height = 'auto'
        stick = true
        refresh()
      })
      .catch(function () { showTip('发送失败') })
      .then(function () {
        sendBtn.disabled = false
      })
  }

  // 点发送不要让按钮抢走焦点，否则输入法会先收起再弹回来。
  sendBtn.addEventListener('pointerdown', function (event) { event.preventDefault() })
  sendBtn.addEventListener('mousedown', function (event) { event.preventDefault() })
  sendBtn.addEventListener('click', send)
  text.addEventListener('keydown', function (event) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      send()
    }
  })
  text.addEventListener('input', function () {
    text.style.height = 'auto'
    text.style.height = Math.min(text.scrollHeight, 120) + 'px'
  })

  refresh()
  setInterval(refresh, 1500)
})()
</script>
</body>
</html>`
