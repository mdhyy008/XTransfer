import {
  app,
  BrowserWindow,
  clipboard,
  dialog,
  ipcMain,
  Menu,
  nativeImage,
  nativeTheme,
  powerMonitor,
  screen,
  shell,
  Tray
} from 'electron'
import { execFile, spawn } from 'node:child_process'
import fs from 'node:fs'
import http from 'node:http'
import https from 'node:https'
import os from 'node:os'
import path from 'node:path'
import { createHash, randomUUID } from 'node:crypto'
import type { ClientRequest } from 'node:http'
import type { AddressInfo } from 'node:net'
import type {
  DecisionReq,
  DeviceInfo,
  DoctorResult,
  FileEntry,
  Item,
  LanInfo,
  Message,
  MessageStatus,
  Peer,
  RetryFileReq,
  SendFilesReq,
  SendTextReq,
  SendTextResp,
  Settings,
  ShareStatus,
  Snapshot
} from '../shared/types'
import { PUBLIC_CONVERSATION_ID } from '../shared/types'
import { AppState, ONLINE_WINDOW_SEC } from './appState'
import { BleService } from './bleService'
import { Db } from './db'
import { FileSender } from './fileSender'
import { hotspotStart, hotspotStatus, hotspotStop } from './hotspot'
import { openEventStream, request, type Endpoint } from './localApi'
import { SettingsStore } from './settings'
import { WebChatService } from './webChat'
import { XlanService } from './xlanService'

const APP_DIR = app.isPackaged ? path.dirname(app.getPath('exe')) : process.cwd()
// 便携版会自解压到系统临时目录；数据与 xlan 运行目录放回 exe 所在位置，避免写系统盘且退出不丢。
const DATA_ROOT =
  app.isPackaged && process.env.PORTABLE_EXECUTABLE_DIR
    ? process.env.PORTABLE_EXECUTABLE_DIR
    : APP_DIR
const DATA_DIR = path.join(DATA_ROOT, 'data')
const TMP_DIR = path.join(DATA_DIR, 'tmp')
const THUMB_DIR = path.join(DATA_DIR, 'thumbs')

const thumbCache = new Map<string, string>()
function rememberThumb(key: string, url: string): void {
  if (thumbCache.size >= 400) {
    const oldest = thumbCache.keys().next().value
    if (oldest !== undefined) thumbCache.delete(oldest)
  }
  thumbCache.set(key, url)
}
const XLAN_DIR = app.isPackaged ? path.join(DATA_ROOT, 'xlan') : path.join(APP_DIR, 'resources', 'xlan')
const BLE_DIR = app.isPackaged ? path.join(APP_DIR, 'ble') : path.join(APP_DIR, 'resources', 'ble')

if (app.isPackaged && XLAN_DIR !== path.join(APP_DIR, 'xlan')) {
  try {
    fs.mkdirSync(XLAN_DIR, { recursive: true })
    const src = path.join(APP_DIR, 'xlan', 'xlan.exe')
    const dst = path.join(XLAN_DIR, 'xlan.exe')
    if (
      fs.existsSync(src) &&
      (!fs.existsSync(dst) || fs.statSync(dst).size !== fs.statSync(src).size)
    ) {
      fs.copyFileSync(src, dst)
    }
  } catch {
    // 复制失败时 xlan 启动会给出明确错误
  }
}
const RECV_DIR = path.join(app.getPath('downloads'), 'X互传')

const VIRTUAL_IFACE = /(vmware|virtualbox|vethernet|hyper-v|loopback|bluetooth|tailscale|zerotier|wsl|docker|vpn|tap-|tun)/i

const SPEEDTEST_CHUNK = 4 * 1024 * 1024
const SPEEDTEST_SIZE = SPEEDTEST_CHUNK * 2
const SPEEDTEST_PREFIX = 'xlan-speedtest-'

function maskToPrefix(mask: string | undefined): number {
  if (!mask) return 0
  let bits = 0
  for (const part of mask.split('.')) {
    let value = Number(part)
    if (!Number.isFinite(value)) continue
    while (value > 0) {
      bits += value & 1
      value >>= 1
    }
  }
  return bits
}

function currentLan(): LanInfo | null {
  let best: { name: string; cidr: string; rank: number } | null = null
  for (const [name, addresses] of Object.entries(os.networkInterfaces())) {
    for (const address of addresses ?? []) {
      const family = String(address.family)
      if ((family !== 'IPv4' && family !== '4') || address.internal) continue
      // 169.254/16 是没拿到 DHCP 时自己编的链路本地地址，接不通别人，不算局域网。
      if (/^169\.254\./.test(address.address)) continue
      const prefix = maskToPrefix(address.netmask)
      const candidate = {
        name,
        cidr: `${address.address}/${prefix}`,
        rank: VIRTUAL_IFACE.test(name) ? 2 : 0
      }
      if (!best || candidate.rank < best.rank) best = candidate
    }
  }
  return best ? { name: best.name, cidr: best.cidr } : null
}

type ShareFile = { name: string; path: string; size: number }

let shareServer: http.Server | null = null
let sharePort = 0
let shareFiles: ShareFile[] = []

function collectShareFiles(targets: string[], out: ShareFile[], depth = 0): void {
  if (depth > 8) return
  for (const target of targets) {
    let stat: fs.Stats
    try {
      stat = fs.statSync(target)
    } catch {
      continue
    }
    if (stat.isDirectory()) {
      let names: string[] = []
      try {
        names = fs.readdirSync(target)
      } catch {
        continue
      }
      collectShareFiles(
        names
          .filter((name) => !name.startsWith('.'))
          .map((name) => path.join(target, name)),
        out,
        depth + 1
      )
    } else if (stat.isFile()) {
      out.push({ name: path.basename(target), path: target, size: stat.size })
    }
  }
}

function shareHost(): string {
  const lan = currentLan()
  return lan ? lan.cidr.split('/')[0] : ''
}

function shareSnapshot(): ShareStatus {
  return {
    running: shareServer !== null,
    url: shareServer ? `http://${shareHost()}:${sharePort}/` : '',
    count: shareServer ? shareFiles.length : 0,
    bytes: shareServer ? shareFiles.reduce((sum, file) => sum + file.size, 0) : 0
  }
}

function readableSize(bytes: number): string {
  if (bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

const SHARE_MIME: Record<string, string> = {
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  png: 'image/png',
  gif: 'image/gif',
  webp: 'image/webp',
  bmp: 'image/bmp',
  svg: 'image/svg+xml',
  mp4: 'video/mp4',
  m4v: 'video/mp4',
  webm: 'video/webm',
  mov: 'video/quicktime',
  mkv: 'video/x-matroska',
  mp3: 'audio/mpeg',
  wav: 'audio/wav',
  m4a: 'audio/mp4',
  flac: 'audio/flac',
  ogg: 'audio/ogg',
  pdf: 'application/pdf',
  zip: 'application/zip',
  rar: 'application/vnd.rar',
  '7z': 'application/x-7z-compressed',
  txt: 'text/plain; charset=utf-8',
  md: 'text/plain; charset=utf-8',
  json: 'application/json',
  apk: 'application/vnd.android.package-archive'
}

function shareMime(name: string): string {
  const ext = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : ''
  return SHARE_MIME[ext] ?? 'application/octet-stream'
}

function shareExt(name: string): string {
  return name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : ''
}

function shareView(name: string): 'image' | 'video' | 'audio' | 'pdf' | 'text' | null {
  const ext = shareExt(name)
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg'].includes(ext)) return 'image'
  if (['mp4', 'm4v', 'webm', 'mov', 'mkv'].includes(ext)) return 'video'
  if (['mp3', 'wav', 'm4a', 'flac', 'ogg', 'oga', 'opus'].includes(ext)) return 'audio'
  if (ext === 'pdf') return 'pdf'
  if (
    [
      'txt', 'md', 'markdown', 'log', 'json', 'csv', 'tsv', 'xml', 'yaml', 'yml', 'ini',
      'conf', 'toml', 'js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx', 'html', 'htm', 'css', 'scss',
      'less', 'py', 'java', 'kt', 'kts', 'go', 'c', 'cpp', 'cc', 'h', 'hpp', 'cs', 'rs',
      'rb', 'php', 'swift', 'lua', 'sh', 'bash', 'zsh', 'bat', 'cmd', 'ps1', 'sql',
      'gradle', 'properties', 'env'
    ].includes(ext)
  ) {
    return 'text'
  }
  return null
}

const SHARE_ICON_EYE =
  '<svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2.6 12S6.1 5.6 12 5.6 21.4 12 21.4 12 17.9 18.4 12 18.4 2.6 12 2.6 12Z"/><circle cx="12" cy="12" r="2.9"/></svg>'
const SHARE_ICON_DOWNLOAD =
  '<svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3.8v11.4"/><path d="m7.4 10.6 4.6 4.6 4.6-4.6"/><path d="M4.8 19.6h14.4"/></svg>'
const SHARE_ICON_ZIP =
  '<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M6.2 3.2h11.6v17.6H6.2z"/><path d="M10.4 3.2v1.8M13.6 5v1.8M10.4 6.8v1.8M13.6 8.6v1.8M10.4 10.4v1.8M13.6 12.2v1.8"/><rect x="9.8" y="15.6" width="4.4" height="3.4" rx="1"/></svg>'

let shareBootstrap: string | null = null

// Bootstrap 随安装包走，分发页在没有外网的内网里也能正常显示。
function shareBootstrapCss(): string {
  if (shareBootstrap !== null) return shareBootstrap
  try {
    shareBootstrap = fs
      .readFileSync(assetPath('bootstrap.min.css'), 'utf8')
      .replace(/^@charset "UTF-8";/, '')
  } catch {
    shareBootstrap = ''
  }
  return shareBootstrap
}

const SHARE_THEME_SCRIPT =
  "<script>document.documentElement.setAttribute('data-bs-theme',window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light')</script>"

function shareStyle(): string {
  return `
body{font-family:-apple-system,"MiSans","Microsoft YaHei",system-ui,sans-serif;font-size:15px}
.share-wrap{max-width:780px}
.share-thumb{position:relative;flex:0 0 auto;width:46px;height:46px;border-radius:12px;overflow:hidden;display:grid;place-items:center;background:var(--bs-tertiary-bg)}
.share-thumb img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}
.share-badge{font-size:10px;font-weight:600;letter-spacing:.02em;color:var(--bs-primary)}
.share-act{width:38px;height:38px;display:grid;place-items:center;padding:0}
.min-w-0{min-width:0}
.share-pre{margin:0;padding:16px;border-radius:12px;font:13px/1.55 ui-monospace,Menlo,Consolas,monospace;white-space:pre-wrap;word-break:break-word;max-height:78vh;overflow:auto}
@media (max-width:575.98px){.share-zip{width:100%}}`
}

function shareHtml(): string {
  const total = shareFiles.reduce((sum, file) => sum + file.size, 0)
  const rows = shareFiles
    .map((file, index) => {
      const view = shareView(file.name)
      const href = view ? `/v/${index}` : `/f/${index}`
      const badge = escapeHtml(shareExt(file.name).toUpperCase() || '文件')
      const thumb =
        view === 'image'
          ? `<span class="share-thumb"><span class="share-badge">${badge}</span><img src="/t/${index}" alt="" loading="lazy" onerror="this.remove()"></span>`
          : `<span class="share-thumb"><span class="share-badge">${badge}</span></span>`
      const preview = view
        ? `<a class="btn btn-sm btn-light border-0 share-act" href="/v/${index}" title="预览">${SHARE_ICON_EYE}</a>`
        : ''
      return `<li class="list-group-item d-flex align-items-center gap-2 py-2 px-2 px-sm-3"><a class="d-flex align-items-center gap-3 flex-grow-1 min-w-0 text-reset text-decoration-none" href="${href}">${thumb}<span class="d-block min-w-0"><span class="d-block text-truncate">${escapeHtml(file.name)}</span><span class="d-block small text-body-secondary">${readableSize(file.size)}</span></span></a><span class="d-flex flex-shrink-0 gap-1">${preview}<a class="btn btn-sm btn-light border-0 share-act" href="/f/${index}" title="下载">${SHARE_ICON_DOWNLOAD}</a></span></li>`
    })
    .join('')
  const packButton =
    shareFiles.length === 0
      ? ''
      : `<a class="btn btn-primary rounded-pill share-zip flex-shrink-0 d-inline-flex align-items-center justify-content-center gap-2" href="/z">${SHARE_ICON_ZIP}打包下载</a>`
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>X互传 · 内网分发</title>
${SHARE_THEME_SCRIPT}
<style>${shareBootstrapCss()}</style>
<style>${shareStyle()}</style></head><body class="bg-body-tertiary">
<div class="container py-4 share-wrap">
<div class="d-flex flex-column flex-sm-row align-items-sm-center justify-content-sm-between gap-3 mb-4">
<div class="min-w-0"><h1 class="h4 mb-1">X互传</h1><p class="small text-body-secondary mb-0">${shareFiles.length} 个文件 · ${readableSize(total)}</p></div>
${packButton}
</div>
${shareFiles.length === 0 ? '<div class="text-center text-body-secondary py-5">暂无可分发的文件</div>' : `<ul class="list-group list-group-flush rounded-4 overflow-hidden shadow-sm">${rows}</ul>`}
</div>
</body></html>`
}

const shareThumbs = new Map<string, Buffer>()

function shareThumb(file: ShareFile): Buffer | null {
  let stat: fs.Stats
  try {
    stat = fs.statSync(file.path)
  } catch {
    return null
  }
  const key = `${file.path}|${stat.size}|${stat.mtimeMs}`
  const hit = shareThumbs.get(key)
  if (hit) return hit
  const image = nativeImage.createFromPath(file.path)
  if (image.isEmpty()) return null
  const thumb = image.getSize().width > 240 ? image.resize({ width: 240, quality: 'good' }) : image
  const data = thumb.toPNG()
  if (shareThumbs.size > 64) shareThumbs.clear()
  shareThumbs.set(key, data)
  return data
}

const ZIP_CRC_TABLE = (() => {
  const table = new Uint32Array(256)
  for (let i = 0; i < 256; i++) {
    let value = i
    for (let bit = 0; bit < 8; bit++) value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1
    table[i] = value >>> 0
  }
  return table
})()

function zipCrcUpdate(chunk: Buffer, crc: number): number {
  let value = crc ^ 0xffffffff
  for (let i = 0; i < chunk.length; i++) {
    value = ZIP_CRC_TABLE[(value ^ chunk[i]) & 0xff] ^ (value >>> 8)
  }
  return (value ^ 0xffffffff) >>> 0
}

function zipCrcOfFile(filePath: string): Promise<number | null> {
  return new Promise((resolve) => {
    let crc = 0
    const stream = fs.createReadStream(filePath)
    stream.on('data', (chunk) => {
      crc = zipCrcUpdate(chunk as Buffer, crc)
    })
    stream.on('end', () => resolve(crc))
    stream.on('error', () => resolve(null))
  })
}

function zipDosTime(date: Date): { time: number; day: number } {
  return {
    time:
      ((date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1)) & 0xffff,
    day:
      (((date.getFullYear() - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate()) &
      0xffff
  }
}

function zipUniqueName(name: string, used: Set<string>): string {
  const dot = name.lastIndexOf('.')
  const base = dot > 0 ? name.slice(0, dot) : name
  const ext = dot > 0 ? name.slice(dot) : ''
  let candidate = name
  let n = 1
  while (used.has(candidate.toLowerCase())) {
    n += 1
    candidate = `${base} (${n})${ext}`
  }
  used.add(candidate.toLowerCase())
  return candidate
}

function zipLocalHeader(
  name: Buffer,
  crc: number,
  size: number,
  stamp: { time: number; day: number }
): Buffer {
  const head = Buffer.alloc(30)
  head.writeUInt32LE(0x04034b50, 0)
  head.writeUInt16LE(20, 4)
  head.writeUInt16LE(0x0800, 6)
  head.writeUInt16LE(0, 8)
  head.writeUInt16LE(stamp.time, 10)
  head.writeUInt16LE(stamp.day, 12)
  head.writeUInt32LE(crc, 14)
  head.writeUInt32LE(size, 18)
  head.writeUInt32LE(size, 22)
  head.writeUInt16LE(name.length, 26)
  head.writeUInt16LE(0, 28)
  return Buffer.concat([head, name])
}

function zipCentralEntry(
  name: Buffer,
  crc: number,
  size: number,
  offset: number,
  stamp: { time: number; day: number }
): Buffer {
  const head = Buffer.alloc(46)
  head.writeUInt32LE(0x02014b50, 0)
  head.writeUInt16LE(20, 4)
  head.writeUInt16LE(20, 6)
  head.writeUInt16LE(0x0800, 8)
  head.writeUInt16LE(0, 10)
  head.writeUInt16LE(stamp.time, 12)
  head.writeUInt16LE(stamp.day, 14)
  head.writeUInt32LE(crc, 16)
  head.writeUInt32LE(size, 20)
  head.writeUInt32LE(size, 24)
  head.writeUInt16LE(name.length, 28)
  head.writeUInt16LE(0, 30)
  head.writeUInt16LE(0, 32)
  head.writeUInt16LE(0, 34)
  head.writeUInt16LE(0, 36)
  head.writeUInt32LE(0, 38)
  head.writeUInt32LE(offset, 42)
  return Buffer.concat([head, name])
}

function zipEnd(entries: number, size: number, offset: number): Buffer {
  const head = Buffer.alloc(22)
  head.writeUInt32LE(0x06054b50, 0)
  head.writeUInt16LE(0, 4)
  head.writeUInt16LE(0, 6)
  head.writeUInt16LE(entries, 8)
  head.writeUInt16LE(entries, 10)
  head.writeUInt32LE(size, 12)
  head.writeUInt32LE(offset, 16)
  head.writeUInt16LE(0, 20)
  return head
}

function pumpToResponse(filePath: string, res: http.ServerResponse): Promise<void> {
  return new Promise((resolve) => {
    const stream = fs.createReadStream(filePath)
    const onClose = (): void => {
      stream.destroy()
    }
    const finish = (): void => {
      res.removeListener('close', onClose)
      resolve()
    }
    stream.on('error', () => {
      stream.destroy()
      finish()
    })
    stream.on('end', finish)
    res.on('close', onClose)
    stream.pipe(res, { end: false })
  })
}

async function sendShareZip(res: http.ServerResponse): Promise<void> {
  const used = new Set<string>()
  const items: {
    name: Buffer
    path: string
    size: number
    crc: number
    stamp: { time: number; day: number }
  }[] = []
  for (const file of shareFiles) {
    let stat: fs.Stats
    try {
      stat = await fs.promises.stat(file.path)
    } catch {
      continue
    }
    if (!stat.isFile()) continue
    const crc = await zipCrcOfFile(file.path)
    if (crc === null) continue
    const raw = zipUniqueName(file.name.replace(/[\\/]+/g, '_'), used)
    items.push({
      name: Buffer.from(raw, 'utf8'),
      path: file.path,
      size: stat.size,
      crc,
      stamp: zipDosTime(stat.mtime)
    })
  }
  let total = 22
  for (const item of items) {
    total += 30 + item.name.length + item.size + 46 + item.name.length
  }
  if (items.some((item) => item.size > 0xfffffffe) || total > 0xfffffffe) {
    res.writeHead(413, { 'Content-Type': 'text/plain; charset=utf-8' })
    res.end('文件过大')
    return
  }
  res.writeHead(200, {
    'Content-Type': 'application/zip',
    'Content-Disposition': `attachment; filename="xshare.zip"; filename*=UTF-8''${encodeURIComponent('X互传.zip')}`,
    'Content-Length': total,
    'Cache-Control': 'no-store'
  })
  const central: Buffer[] = []
  let offset = 0
  for (const item of items) {
    res.write(zipLocalHeader(item.name, item.crc, item.size, item.stamp))
    await pumpToResponse(item.path, res)
    central.push(zipCentralEntry(item.name, item.crc, item.size, offset, item.stamp))
    offset += 30 + item.name.length + item.size
  }
  const directory = Buffer.concat(central)
  res.write(directory)
  res.end(zipEnd(items.length, directory.length, offset))
}

function sharePreviewHtml(index: number, file: ShareFile, view: string): string {
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(file.name)}</title>
${SHARE_THEME_SCRIPT}
<style>${shareBootstrapCss()}</style>
<style>${shareStyle()}</style></head><body class="bg-body-tertiary">
<div class="container py-4 share-wrap">
<div class="mb-3"><h1 class="h5 mb-1 text-break">${escapeHtml(file.name)}</h1><p class="small text-body-secondary mb-0">${readableSize(file.size)}</p></div>
<div id="view" class="bg-body rounded-4 shadow-sm p-2" data-kind="${view}" data-index="${index}" data-size="${file.size}">
<div class="text-center text-body-secondary py-5">载入中</div>
</div>
</div>
<script>
(function(){
  var box=document.getElementById('view');
  var kind=box.dataset.kind,index=Number(box.dataset.index),size=Number(box.dataset.size);
  var raw='/r/'+index;
  if(kind==='image'){box.innerHTML='<img class="img-fluid w-100 rounded-3 d-block" alt="" src="'+raw+'">'}
  else if(kind==='video'){box.innerHTML='<video class="w-100 rounded-3 d-block bg-black" controls playsinline autoplay src="'+raw+'"></video>'}
  else if(kind==='audio'){box.innerHTML='<audio class="w-100 my-3" controls autoplay src="'+raw+'"></audio>'}
  else if(kind==='pdf'){box.innerHTML='<iframe class="w-100 rounded-3 border-0" style="height:78vh" src="'+raw+'"></iframe>'}
  else if(kind==='text'){
    if(size>2097152){box.innerHTML='<div class="text-center text-body-secondary py-5">文件较大</div>'}
    else{fetch(raw).then(function(r){return r.text()}).then(function(t){
      var pre=document.createElement('pre');pre.className='share-pre bg-body-tertiary';pre.textContent=t;box.innerHTML='';box.appendChild(pre);
    }).catch(function(){box.innerHTML='<div class="text-center text-body-secondary py-5">载入失败</div>'})}
  }
})();
</script>
</body></html>`
}

function sendShareFile(
  req: http.IncomingMessage,
  res: http.ServerResponse,
  file: ShareFile,
  inline: boolean
): void {
  let stat: fs.Stats
  try {
    stat = fs.statSync(file.path)
  } catch {
    res.writeHead(404)
    res.end('Not Found')
    return
  }
  const type = shareMime(file.name)
  const headers: Record<string, string | number> = {
    'Content-Type': type,
    'Content-Disposition': `${inline ? 'inline' : 'attachment'}; filename*=UTF-8''${encodeURIComponent(file.name)}`,
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'no-store'
  }
  const range = req.headers.range
  const parsed = range ? /^bytes=(\d*)-(\d*)$/.exec(range) : null
  if (parsed) {
    const start = parsed[1] ? Number(parsed[1]) : 0
    const end = parsed[2] ? Number(parsed[2]) : stat.size - 1
    if (start >= 0 && start < stat.size && end >= start) {
      const last = Math.min(end, stat.size - 1)
      res.writeHead(206, {
        ...headers,
        'Content-Range': `bytes ${start}-${last}/${stat.size}`,
        'Content-Length': last - start + 1
      })
      fs.createReadStream(file.path, { start, end: last }).pipe(res)
      return
    }
  }
  res.writeHead(200, { ...headers, 'Content-Length': stat.size })
  fs.createReadStream(file.path).pipe(res)
}

function serveShare(req: http.IncomingMessage, res: http.ServerResponse): void {
  const target = decodeURIComponent((req.url ?? '/').split('?')[0])
  if (target === '/' || target === '/index.html') {
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store'
    })
    res.end(shareHtml())
    return
  }
  if (target === '/z') {
    void sendShareZip(res).catch(() => {
      try {
        res.destroy()
      } catch {
        // 连接已经断开
      }
    })
    return
  }
  const thumbMatch = /^\/t\/(\d+)$/.exec(target)
  if (thumbMatch) {
    const file = shareFiles[Number(thumbMatch[1])]
    const data = file && shareView(file.name) === 'image' ? shareThumb(file) : null
    if (!data) {
      res.writeHead(404)
      res.end('Not Found')
      return
    }
    res.writeHead(200, {
      'Content-Type': 'image/png',
      'Content-Length': data.length,
      'Cache-Control': 'max-age=600'
    })
    res.end(data)
    return
  }
  const viewMatch = /^\/v\/(\d+)$/.exec(target)
  if (viewMatch) {
    const index = Number(viewMatch[1])
    const file = shareFiles[index]
    const view = file ? shareView(file.name) : null
    if (!file || !view) {
      res.writeHead(404)
      res.end('Not Found')
      return
    }
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store'
    })
    res.end(sharePreviewHtml(index, file, view))
    return
  }
  const inline = /^\/r\/(\d+)$/.exec(target)
  const download = /^\/f\/(\d+)$/.exec(target)
  const match = inline ?? download
  const file = match ? shareFiles[Number(match[1])] : undefined
  if (!file) {
    res.writeHead(404)
    res.end('Not Found')
    return
  }
  sendShareFile(req, res, file, inline !== null)
}

let lanTimer: NodeJS.Timeout | null = null
let lanKey = ''

function startLanWatch(): void {
  if (lanTimer) return
  const tick = (): void => {
    const info = currentLan()
    const key = info ? `${info.name}|${info.cidr}` : ''
    if (key === lanKey) return
    lanKey = key
    win?.webContents.send('lan', info)
  }
  tick()
  lanTimer = setInterval(tick, 2000)
}

function dirSize(dir: string): number {
  let total = 0
  let entries: fs.Dirent[]
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true })
  } catch {
    return 0
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name)
    try {
      total += entry.isDirectory() ? dirSize(full) : fs.statSync(full).size
    } catch {
      // 文件可能刚好被删掉
    }
  }
  return total
}

// 异步版本给渲染进程算文件夹体积用，遍历大目录时不卡主进程。
async function dirSizeAsync(dir: string): Promise<number> {
  let total = 0
  let entries: fs.Dirent[]
  try {
    entries = await fs.promises.readdir(dir, { withFileTypes: true })
  } catch {
    return 0
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      total += await dirSizeAsync(full)
      continue
    }
    try {
      total += (await fs.promises.stat(full)).size
    } catch {
      // 文件可能刚好被删掉
    }
  }
  return total
}

// 清理目录，但保留 keep 里的文件（暂存桶里还没发出去的截图就在这里面）。
function purgeDirExcept(dir: string, keep: Set<string>): number {
  let freed = 0
  let entries: fs.Dirent[]
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true })
  } catch {
    return 0
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name)
    if (keep.has(full)) continue
    if (entry.isDirectory() && [...keep].some((item) => item.startsWith(full + path.sep))) {
      freed += purgeDirExcept(full, keep)
      continue
    }
    const size = dirSize(full)
    try {
      fs.rmSync(full, { recursive: true, force: true })
      freed += size
    } catch {
      // 文件可能刚好被删掉
    }
  }
  return freed
}

function clearDir(dir: string): number {
  let freed = 0
  let entries: fs.Dirent[]
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true })
  } catch {
    return 0
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name)
    const size = dirSize(full)
    try {
      fs.rmSync(full, { recursive: true, force: true })
      freed += size
    } catch {
      // 还被占用的文件留着，下次再清
    }
  }
  return freed
}

function partsDir(): string {
  return path.join(settings.recvDir || RECV_DIR, '.parts')
}

function cacheSize(): number {
  return dirSize(TMP_DIR) + dirSize(THUMB_DIR) + dirSize(partsDir())
}

for (const dir of [DATA_DIR, TMP_DIR, THUMB_DIR, path.join(DATA_DIR, 'logs'), path.join(DATA_DIR, 'crashes')]) {
  fs.mkdirSync(dir, { recursive: true })
}
app.setPath('userData', DATA_DIR)
app.setPath('sessionData', path.join(DATA_DIR, 'session'))
app.setPath('logs', path.join(DATA_DIR, 'logs'))
app.setPath('crashDumps', path.join(DATA_DIR, 'crashes'))
process.env.TMP = TMP_DIR
process.env.TEMP = TMP_DIR
process.env.TMPDIR = TMP_DIR

const settings = new SettingsStore(path.join(DATA_DIR, 'settings.json'))
const db = new Db(DATA_DIR)
const state = new AppState(db)
const service = new XlanService(XLAN_DIR, settings.recvDir || RECV_DIR)
const sender = new FileSender(service.exe, XLAN_DIR, db, (messageIds) => {
  if (messageIds && messageIds.length > 0) queueMessageUpdate(messageIds)
  else state.emit()
})

const BLE_PREFIX = 'ble:'
const BLE_FILE_LIMIT = 5 * 1024 * 1024
const BLE_IMAGE_COMPRESS_LIMIT = 2 * 1024 * 1024
const BLE_OUT_DIR = path.join(TMP_DIR, 'ble-out')
const ble = new BleService(path.join(BLE_DIR, 'BleBridge.exe'), {
  onChange: (patch) => state.setBle(patch),
  onMessage: (message) => {
    const blocked = settings.value.blocked ?? []
    if (blocked.includes(message.from) || blocked.includes(`${BLE_PREFIX}${message.from}`)) return
    const inserted = db.addInboundBluetooth({
      id: message.id,
      fromId: message.from,
      fromName: message.fromName,
      platform: message.platform,
      body: message.body,
      ts: message.ts
    })
    if (!inserted) return
    state.emit()
    notifyItem({
      id: message.id,
      kind: 'text',
      from_id: message.from,
      from_name: message.fromName,
      platform: message.platform,
      to_id: state.info?.id ?? '',
      channel: 'ble',
      body: message.body,
      status: 'done',
      created_at: message.ts
    })
  },
  onFileOffer: (offer) => {
    const blocked = settings.value.blocked ?? []
    if (blocked.includes(offer.from) || blocked.includes(`${BLE_PREFIX}${offer.from}`)) {
      return { reason: '对方未接收' }
    }
    if (!offer.size || offer.size > BLE_FILE_LIMIT) return { reason: '文件过大' }
    const dir = settings.recvDir || RECV_DIR
    try {
      fs.mkdirSync(dir, { recursive: true })
    } catch {
      return { reason: '无法保存' }
    }
    const inserted = db.addInboundBluetoothFile({
      id: offer.id,
      fromId: offer.from,
      fromName: offer.fromName,
      platform: offer.platform,
      name: offer.name,
      size: offer.size,
      ts: offer.ts
    })
    if (inserted) state.emit()
    return { dir }
  },
  onFileProgress: (id, received, total, direction) => {
    const at = Date.now()
    const prev = progressSamples.get(id)
    const elapsed = prev ? Math.max(0.05, (at - prev.at) / 1000) : 0
    const speed = prev ? Math.max(0, (received - prev.received) / elapsed) : 0
    progressSamples.set(id, { received, at })
    if (direction === 'in') inboundSeen.set(id, at)
    db.setProgress(id, received, total, speed)
    queueMessageUpdate([id])
  },
  onFileDone: (id, name, filePath, bytes) => {
    db.finishBluetoothFile({
      id,
      path: filePath || undefined,
      name: name || undefined,
      size: bytes || undefined
    })
    const message = db.getMessage(id)
    state.emit()
    queueMessageUpdate([id])
    if (!message) return
    notifyItem({
      id,
      kind: 'file',
      from_id: message.from_id,
      from_name: message.from_name,
      to_id: state.info?.id ?? '',
      channel: 'ble',
      name: message.file_name,
      size: message.file_size,
      path: message.local_path,
      status: 'done',
      created_at: message.created_at
    })
  },
  onFileFailed: (id, reason) => {
    const message = db.getMessage(id)
    if (!message) return
    if (message.status === 'canceled' || message.status === 'rejected') return
    db.setMessageStatus(id, 'failed', reason)
    state.emit()
    queueMessageUpdate([id])
  }
})

function isBleImage(name: string): boolean {
  return /\.(jpe?g|png|webp|bmp|heic|heif)$/i.test(name)
}

// 蓝牙带宽只有几十 KB/s：图片先压一遍，超限的文件直接拒绝。
function prepareBleFile(sourcePath: string): {
  path: string
  name: string
  size: number
  temp: boolean
} {
  const stat = fs.statSync(sourcePath)
  if (!stat.isFile()) throw new Error('蓝牙不支持发送文件夹')
  const original = path.basename(sourcePath)
  if (!isBleImage(original)) {
    if (stat.size > BLE_FILE_LIMIT) throw new Error('单个文件不能超过 5 MB')
    return { path: sourcePath, name: original, size: stat.size, temp: false }
  }
  // 2 MB 以内的图片原样发送，只有大图才值得压一轮。
  if (stat.size <= BLE_IMAGE_COMPRESS_LIMIT) {
    return { path: sourcePath, name: original, size: stat.size, temp: false }
  }
  const source = fs.readFileSync(sourcePath)
  try {
    const image = nativeImage.createFromPath(sourcePath)
    if (!image.isEmpty()) {
      const { width, height } = image.getSize()
      const scale = Math.min(1, 1600 / Math.max(width, height))
      const target =
        scale < 1
          ? image.resize({
              width: Math.max(1, Math.round(width * scale)),
              height: Math.max(1, Math.round(height * scale)),
              quality: 'good'
            })
          : image
      const jpeg = target.toJPEG(80)
      if (jpeg.length > 0 && jpeg.length < source.length) {
        fs.mkdirSync(BLE_OUT_DIR, { recursive: true })
        const out = path.join(BLE_OUT_DIR, `${randomUUID()}.jpg`)
        fs.writeFileSync(out, jpeg)
        const name = `${original.replace(/\.[^.]+$/, '')}.jpg`
        return { path: out, name, size: jpeg.length, temp: true }
      }
    }
  } catch {
    // 压不动就按原图发。
  }
  if (source.length > BLE_FILE_LIMIT) throw new Error('单个文件不能超过 5 MB')
  return { path: sourcePath, name: original, size: source.length, temp: false }
}

async function sendBleFiles(to: string, paths: string[]): Promise<string> {
  const peerId = to.slice(BLE_PREFIX.length)
  const device = state.bluetooth.devices.find((item) => item.id === peerId)
  const peerName = device?.name || peerId
  if (!ble.snapshot.on) throw new Error('蓝牙未开启')
  const first = paths.length > 0 ? randomUUID() : ''
  let count = 0
  for (const sourcePath of paths) {
    if (!sourcePath) continue
    const id = count === 0 ? first : randomUUID()
    const prepared = prepareBleFile(sourcePath)
    db.addOutboundBluetoothFile({
      convId: to,
      peerName,
      id,
      sourcePath,
      fileName: prepared.name,
      size: prepared.size,
      selfId: state.info?.id ?? '',
      selfName: state.info?.name ?? ''
    })
    state.emit()
    try {
      await ble.sendFile(peerId, id, prepared.name, prepared.path)
      db.finishBluetoothFile({ id, name: prepared.name, size: prepared.size })
      queueMessageUpdate([id])
    } catch (error) {
      db.setMessageStatus(id, 'failed', error instanceof Error ? error.message : '发送失败')
      queueMessageUpdate([id])
      throw error
    } finally {
      if (prepared.temp) fs.unlink(prepared.path, () => {})
    }
    count += 1
  }
  if (count === 0) throw new Error('没有可发送的项目')
  return first
}

async function retryBleFile(to: string, id: string): Promise<void> {
  const message = db.getMessage(id)
  if (!message || message.direction !== 'out' || message.kind !== 'file') return
  const sourcePath = message.local_path ?? ''
  if (!sourcePath || !fs.existsSync(sourcePath)) throw new Error('文件已不存在')
  const peerId = to.slice(BLE_PREFIX.length)
  const prepared = prepareBleFile(sourcePath)
  db.setMessageStatus(id, 'sending')
  state.emit()
  try {
    await ble.sendFile(peerId, id, prepared.name, prepared.path)
    db.finishBluetoothFile({ id, name: prepared.name, size: prepared.size })
    queueMessageUpdate([id])
  } finally {
    if (prepared.temp) fs.unlink(prepared.path, () => {})
  }
}

const webChat = new WebChatService({
  db,
  selfId: () => state.info?.id ?? '',
  host: () => shareHost(),
  onChange: () => state.emit()
})

let win: BrowserWindow | null = null
let tray: Tray | null = null
let endpoint: Endpoint | null = null
let eventReq: ClientRequest | null = null
let reconnectTimer: NodeJS.Timeout | null = null
let pollTimer: NodeJS.Timeout | null = null
let stopping = false
let quitting = false
// 只关本应用开的热点：用户从系统设置开的，退出时不能动。
let hotspotOwned = false
let hotspotClosing = false

// 开机自启时带这个参数启动：主界面先不弹，只留托盘图标。
const AUTOSTART_ARGS = ['--hidden']
let startupHidden = process.argv.includes('--hidden')
let lastItemTs = 0
let lastEventTs = 0

const progressSamples = new Map<string, { received: number; at: number }>()

// 入站传输最后一次有新进度的时刻；超过阈值没有任何字节就主动取消，进度条不会一直卡着。
const inboundSeen = new Map<string, number>()
const INBOUND_IDLE_MS = 30_000
const SENDING_STATUSES = new Set<MessageStatus>(['queued', 'sending', 'accepted', 'active'])

let pendingSnapshot: Snapshot | null = null
let broadcastTimer: NodeJS.Timeout | null = null
state.onChange = (snapshot) => {
  pendingSnapshot = snapshot
  if (broadcastTimer) return
  broadcastTimer = setTimeout(() => {
    broadcastTimer = null
    const payload = pendingSnapshot
    pendingSnapshot = null
    if (!payload) return
    syncNotice(payload)
    for (const window of BrowserWindow.getAllWindows()) {
      window.webContents.send('state', payload)
    }
  }, 50)
}

// 传输进度只推变更的那几条消息，不再整包重发所有历史。
let pendingMessageIds = new Set<string>()
let messageTimer: NodeJS.Timeout | null = null

function queueMessageUpdate(ids: string[]): void {
  for (const id of ids) pendingMessageIds.add(id)
  if (messageTimer) return
  messageTimer = setTimeout(() => {
    messageTimer = null
    const messages = [...pendingMessageIds]
      .map((id) => db.getMessage(id))
      .filter((message): message is Message => message !== undefined)
    pendingMessageIds = new Set()
    if (messages.length === 0) return
    for (const window of BrowserWindow.getAllWindows()) {
      window.webContents.send('messages', messages)
    }
  }, 100)
}

function inspectEntries(paths: string[]): FileEntry[] {
  const entries: FileEntry[] = []
  for (const target of paths) {
    if (!target) continue
    const name = path.basename(target) || target
    try {
      const stat = fs.statSync(target)
      entries.push({
        path: target,
        name,
        kind: stat.isDirectory() ? 'folder' : 'file',
        size: stat.isDirectory() ? 0 : stat.size,
        exists: true
      })
    } catch {
      entries.push({ path: target, name, kind: 'file', size: 0, exists: false })
    }
  }
  return entries
}

const SCREENSHOT_DIR = path.join(TMP_DIR, 'screenshots')
let capturingShot = false

// WinForms 小工具负责整屏冻结遮罩和框选：它能压住任务栏，Electron 的窗口压不住。
const SHOT_HELPER = app.isPackaged
  ? path.join(APP_DIR, 'screenshot', 'XShot.exe')
  : path.join(APP_DIR, 'resources', 'screenshot', 'XShot.exe')

function captureRegion(): Promise<Electron.NativeImage | null> {
  return new Promise((resolve) => {
    if (!fs.existsSync(SHOT_HELPER)) {
      resolve(null)
      return
    }
    fs.mkdirSync(TMP_DIR, { recursive: true })
    const out = path.join(TMP_DIR, `screenshot-${Date.now()}.png`)
    // 主窗口刚隐藏，等一帧让桌面合成完，避免把刚消失的窗口截进去。
    setTimeout(() => {
      let settled = false
      const finish = (value: Electron.NativeImage | null): void => {
        if (settled) return
        settled = true
        fs.rmSync(out, { force: true })
        resolve(value)
      }
      const child = spawn(SHOT_HELPER, ['--out', out], { windowsHide: true })
      child.on('error', () => finish(null))
      child.on('exit', (code) => {
        if (code !== 0 || !fs.existsSync(out)) {
          finish(null)
          return
        }
        const image = nativeImage.createFromPath(out)
        finish(image.isEmpty() ? null : image)
      })
    }, 40)
  })
}

function apiGet<T>(pathname: string): Promise<T> {
  if (!endpoint) return Promise.reject(new Error('服务未就绪'))
  return request<T>(endpoint, 'GET', pathname)
}

function apiPost<T = unknown>(pathname: string, body: unknown): Promise<T> {
  if (!endpoint) return Promise.reject(new Error('服务未就绪'))
  return request<T>(endpoint, 'POST', pathname, body)
}

function apiDelete<T = unknown>(pathname: string): Promise<T> {
  if (!endpoint) return Promise.reject(new Error('服务未就绪'))
  return request<T>(endpoint, 'DELETE', pathname)
}

// 测速直接连对端，不校验指纹：局域网直连只关心吞吐，加密本身仍然保留。
// 对端没开加密时 https 握手会失败，先探测一次再决定用哪种协议。
type PeerProto = 'https' | 'http'

function peerRequest(
  proto: PeerProto,
  host: string,
  port: number,
  method: string,
  pathname: string,
  body?: Buffer,
  headers: Record<string, string | number> = {},
  onSent?: (bytes: number) => void
): Promise<{ status: number; data: Buffer }> {
  const lib = proto === 'https' ? https : http
  return new Promise((resolve, reject) => {
    const req = lib.request(
      {
        hostname: host,
        port,
        path: pathname,
        method,
        rejectUnauthorized: false,
        timeout: 30_000,
        headers: { ...headers, ...(body ? { 'content-length': body.length } : {}) }
      },
      (res) => {
        const chunks: Buffer[] = []
        res.on('data', (chunk: Buffer) => chunks.push(chunk))
        res.on('end', () =>
          resolve({ status: res.statusCode ?? 0, data: Buffer.concat(chunks) })
        )
        res.on('error', reject)
      }
    )
    let dead = false
    req.on('timeout', () => {
      dead = true
      req.destroy(new Error('请求超时'))
    })
    req.on('error', (error) => {
      dead = true
      reject(error)
    })
    if (!body) {
      req.end()
      return
    }
    // 小片写入：配合背压让回调里的字节数贴近真实已发出的字节数，测速样本才平滑。
    const step = 64 * 1024
    let off = 0
    const pump = (): void => {
      while (off < body.length && !dead) {
        const n = Math.min(step, body.length - off)
        try {
          req.write(body.subarray(off, off + n))
        } catch {
          return
        }
        off += n
        onSent?.(n)
        if (req.writableNeedDrain) {
          req.once('drain', pump)
          return
        }
      }
      if (!dead) req.end()
    }
    pump()
  })
}

async function detectPeerProto(host: string, port: number): Promise<PeerProto> {
  try {
    await peerRequest('https', host, port, 'GET', '/v1/info')
    return 'https'
  } catch {
    return 'http'
  }
}

async function peerJson(
  proto: PeerProto,
  host: string,
  port: number,
  method: string,
  pathname: string,
  payload?: unknown
): Promise<Record<string, unknown>> {
  const body = payload === undefined ? undefined : Buffer.from(JSON.stringify(payload))
  const res = await peerRequest(
    proto,
    host,
    port,
    method,
    pathname,
    body,
    body ? { 'content-type': 'application/json' } : {}
  )
  let data: Record<string, unknown> = {}
  try {
    const parsed = JSON.parse(res.data.toString('utf8')) as unknown
    if (parsed && typeof parsed === 'object') data = parsed as Record<string, unknown>
  } catch {
    // 非 JSON 响应按 HTTP 状态处理
  }
  if (res.status < 200 || res.status >= 300) {
    const code = typeof data.code === 'string' ? data.code : ''
    const msg = typeof data.msg === 'string' ? data.msg : ''
    throw new Error(code ? `${code}: ${msg}` : `HTTP ${res.status}`)
  }
  return data
}

type NoticeMessage = { title: string; body: string; convId: string; offerId?: string }

type NoticeRow = {
  id: string
  name: string
  received: number
  total: number
  speed: number
  percent: number
  failed: boolean
}

const NOTICE_WIDTH = 380
const NOTICE_EDGE = 10
const NOTICE_ROW_HEIGHT = 46
const NOTICE_MAX_ROWS = 3
const NOTICE_MESSAGE_LIFETIME_MS = 6000
const NOTICE_FAILED_LIFETIME_MS = 6000

let noticeWin: BrowserWindow | null = null
let noticeMessage: NoticeMessage | null = null
let noticeMessageTimer: NodeJS.Timeout | null = null
let noticeSnapshot: Snapshot | null = null
let lastNoticePayload: unknown = null
let noticeIconUrl = ''
const noticeFailures = new Map<string, { name: string; timer: NodeJS.Timeout }>()

function assetPath(name: string): string {
  return path.join(APP_DIR, 'resources', name)
}

function noticeIcon(): string {
  if (noticeIconUrl) return noticeIconUrl
  try {
    noticeIconUrl = nativeImage
      .createFromPath(assetPath('icon.png'))
      .resize({ width: 80, height: 80 })
      .toDataURL()
  } catch {
    noticeIconUrl = ''
  }
  return noticeIconUrl
}

function currentTheme(): 'light' | 'dark' {
  if (settings.value.theme === 'dark' || settings.value.theme === 'light') {
    return settings.value.theme
  }
  return nativeTheme.shouldUseDarkColors ? 'dark' : 'light'
}

function noticeHtml(): string {
  const dark = currentTheme() === 'dark'
  const card = dark ? '#26242B' : '#FAF9FD'
  const fg = dark ? '#ECE6F0' : '#1C1B1F'
  const sub = dark ? '#CAC4D0' : '#5D5B66'
  const border = dark ? 'rgba(255,255,255,0.10)' : 'rgba(28,27,31,0.08)'
  const hover = dark ? 'rgba(255,255,255,0.10)' : 'rgba(28,27,31,0.06)'
  const track = dark ? 'rgba(255,255,255,0.14)' : 'rgba(28,27,31,0.10)'
  const accent = '#6750A4'
  const error = dark ? '#FFB4AB' : '#BA1A1A'
  const icon = noticeIcon()
  return `<!doctype html>
<html><head><meta charset="utf-8"><style>
  html,body{margin:0;height:100%;background:transparent;overflow:hidden;
    font-family:"Microsoft YaHei UI","Microsoft YaHei","Segoe UI",system-ui,sans-serif;-webkit-user-select:none;}
  .card{display:flex;flex-direction:column;gap:10px;margin:10px;padding:14px;
    height:calc(100% - 20px);box-sizing:border-box;border-radius:20px;background:${card};
    color:${fg};border:1px solid ${border};box-shadow:0 10px 28px rgba(0,0,0,0.28);}
  .msg{display:none;align-items:flex-start;gap:12px;flex:none;height:56px;cursor:pointer;}
  .icon{width:40px;height:40px;border-radius:12px;flex:none;display:block;}
  .text{flex:1;min-width:0;padding-top:1px;}
  .title{font-size:14px;font-weight:600;line-height:20px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
  .body{margin-top:3px;font-size:12.5px;line-height:17px;color:${sub};max-height:34px;
    overflow:hidden;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;word-break:break-all;}
  .close{flex:none;width:26px;height:26px;border-radius:50%;display:grid;place-items:center;color:${sub};}
  .close:hover{background:${hover};}
  .close svg{width:15px;height:15px;}
  .accept{flex:none;height:26px;padding:0 12px;margin-right:2px;border:none;border-radius:999px;
    background:${accent};color:#fff;font-size:12px;font-weight:600;display:none;align-items:center;
    justify-content:center;cursor:pointer;font-family:inherit;}
  .accept:hover{filter:brightness(1.08);}
  .reject{flex:none;height:26px;padding:0 10px;margin-right:6px;border:1px solid ${border};
    border-radius:999px;background:transparent;color:${fg};font-size:12px;font-weight:600;
    display:none;align-items:center;justify-content:center;cursor:pointer;font-family:inherit;}
  .reject:hover{background:${hover};}
  .list{flex:none;overflow:hidden;position:relative;}
  .rows{transition:transform .35s ease;}
  .row{height:46px;display:flex;align-items:center;gap:10px;}
  .fico{width:28px;height:28px;border-radius:9px;flex:none;display:grid;place-items:center;
    background:${track};color:${sub};}
  .fico svg{width:16px;height:16px;}
  .rmain{flex:1;min-width:0;}
  .rname{font-size:12.5px;line-height:17px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
  .rtrack{height:4px;border-radius:999px;background:${track};margin-top:5px;overflow:hidden;}
  .rfill{height:100%;width:0;border-radius:999px;background:${accent};transition:width .25s ease;}
  .rfill.indeterminate{width:35%;animation:slide 1.1s ease-in-out infinite;}
  @keyframes slide{0%{transform:translateX(-120%)}100%{transform:translateX(320%)}}
  .rmeta{flex:none;width:44px;text-align:right;font-size:11.5px;color:${sub};}
  .cancel{flex:none;width:26px;height:26px;border:none;border-radius:50%;background:transparent;
    color:${sub};display:grid;place-items:center;cursor:pointer;padding:0;}
  .cancel:hover{background:${hover};}
  .cancel svg{width:15px;height:15px;}
  .row.failed .rfill{width:100%;background:${error};opacity:.45;}
  .row.failed .rmeta{color:${error};}
</style></head><body>
  <div class="card" id="card">
    <div class="msg" id="msg">
      ${icon ? `<img class="icon" src="${icon}" alt="">` : ''}
      <div class="text">
        <div class="title" id="m-title"></div>
        <div class="body" id="m-body"></div>
      </div>
      <button class="reject" id="m-reject">拒绝</button>
      <button class="accept" id="m-accept">接收</button>
      <div class="close" id="m-close">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">
          <path d="M6 6l12 12M18 6L6 18"/>
        </svg>
      </div>
    </div>
    <div class="list" id="list"><div class="rows" id="rows"></div></div>
  </div>
  <script>
    const ipc = require('electron').ipcRenderer
    const ROW_H = ${NOTICE_ROW_HEIGHT}
    const MAX_ROWS = ${NOTICE_MAX_ROWS}
    const rowEls = new Map()
    let scrollIndex = 0
    let scrollTimer = null
    let currentMessage = null

    function fmt(value) {
      if (!value || value <= 0) return '0 B'
      const units = ['B', 'KB', 'MB', 'GB', 'TB']
      let index = 0
      let size = value
      while (size >= 1024 && index < units.length - 1) { size /= 1024; index += 1 }
      return (size >= 100 || index === 0 ? Math.round(size) : size.toFixed(1)) + ' ' + units[index]
    }

    function fileIcon() {
      return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>'
    }

    function stopIcon() {
      return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M8.5 12h7"/></svg>'
    }

    function buildRow(id) {
      const el = document.createElement('div')
      el.className = 'row'
      el.innerHTML = '<div class="fico">' + fileIcon() + '</div>' +
        '<div class="rmain"><div class="rname"></div><div class="rtrack"><div class="rfill"></div></div></div>' +
        '<div class="rmeta"></div>' +
        '<button class="cancel" title="取消接收">' + stopIcon() + '</button>'
      el.querySelector('.cancel').addEventListener('click', (event) => {
        event.stopPropagation()
        ipc.send('notice-cancel', id)
      })
      return el
    }

    function updateRow(el, row) {
      el.classList.toggle('failed', !!row.failed)
      el.querySelector('.rname').textContent = row.name || '文件'
      const fill = el.querySelector('.rfill')
      const meta = el.querySelector('.rmeta')
      const cancel = el.querySelector('.cancel')
      if (row.failed) {
        fill.classList.remove('indeterminate')
        fill.style.width = '100%'
        meta.textContent = '已中断'
        cancel.style.display = 'none'
        return
      }
      cancel.style.display = 'grid'
      if (row.total > 0) {
        fill.classList.remove('indeterminate')
        fill.style.width = Math.max(1, Math.min(100, row.percent)) + '%'
        meta.textContent = Math.round(row.percent) + '%'
      } else {
        fill.classList.add('indeterminate')
        meta.textContent = ''
      }
      el.title = (row.total > 0 ? fmt(row.received) + ' / ' + fmt(row.total) : fmt(row.received)) +
        (row.speed > 0 ? ' · ' + fmt(row.speed) + '/s' : '')
    }

    function setShift(value) {
      document.getElementById('rows').style.transform = 'translateY(' + (-value * ROW_H) + 'px)'
    }

    function syncScroll(count) {
      const list = document.getElementById('list')
      list.style.height = (Math.min(count, MAX_ROWS) * ROW_H) + 'px'
      const maxShift = Math.max(0, count - MAX_ROWS)
      if (maxShift === 0) {
        if (scrollTimer) { clearInterval(scrollTimer); scrollTimer = null }
        scrollIndex = 0
        setShift(0)
        return
      }
      if (scrollIndex > maxShift) scrollIndex = 0
      setShift(scrollIndex)
      if (!scrollTimer) {
        scrollTimer = setInterval(() => {
          scrollIndex = scrollIndex >= maxShift ? 0 : scrollIndex + 1
          setShift(scrollIndex)
        }, 2600)
      }
    }

    function update(data) {
      if (!data) return
      currentMessage = data.message || null
      const msg = document.getElementById('msg')
      if (currentMessage) {
        msg.style.display = 'flex'
        document.getElementById('m-title').textContent = currentMessage.title
        document.getElementById('m-body').textContent = currentMessage.body
      } else {
        msg.style.display = 'none'
      }
      const offerDisplay = currentMessage && currentMessage.offerId ? 'inline-flex' : 'none'
      document.getElementById('m-accept').style.display = offerDisplay
      document.getElementById('m-reject').style.display = offerDisplay
      const rowsEl = document.getElementById('rows')
      const seen = new Set()
      data.rows.forEach((row, index) => {
        let el = rowEls.get(row.id)
        if (!el) {
          el = buildRow(row.id)
          rowEls.set(row.id, el)
        }
        updateRow(el, row)
        seen.add(row.id)
        if (rowsEl.children[index] !== el) {
          rowsEl.insertBefore(el, rowsEl.children[index] || null)
        }
      })
      for (const [id, el] of rowEls) {
        if (!seen.has(id)) {
          el.remove()
          rowEls.delete(id)
        }
      }
      syncScroll(data.rows.length)
    }

    ipc.on('notice', (_event, data) => update(data))
    document.getElementById('msg').addEventListener('click', (event) => {
      if (event.target.closest('#m-close')) return
      if (currentMessage) ipc.send('notice-open')
    })
    document.getElementById('m-accept').addEventListener('click', (event) => {
      event.stopPropagation()
      if (currentMessage && currentMessage.offerId) ipc.send('notice-accept', currentMessage.offerId)
    })
    document.getElementById('m-reject').addEventListener('click', (event) => {
      event.stopPropagation()
      if (currentMessage && currentMessage.offerId) ipc.send('notice-reject', currentMessage.offerId)
    })
    document.getElementById('m-close').addEventListener('click', (event) => {
      event.stopPropagation()
      ipc.send('notice-close')
    })
  </script>
</body></html>`
}

function positionNotice(height: number): void {
  const notice = noticeWin
  if (!notice || notice.isDestroyed()) return
  const display =
    win && !win.isDestroyed() && win.isVisible()
      ? screen.getDisplayMatching(win.getBounds())
      : screen.getPrimaryDisplay()
  const area = display.workArea
  notice.setBounds({
    x: area.x + area.width - NOTICE_WIDTH - NOTICE_EDGE,
    y: area.y + area.height - height - NOTICE_EDGE,
    width: NOTICE_WIDTH,
    height
  })
}

function fadeToast(toast: BrowserWindow, to: number, done?: () => void): void {
  const from = toast.getOpacity()
  const steps = 10
  let step = 0
  const timer = setInterval(() => {
    if (toast.isDestroyed()) {
      clearInterval(timer)
      return
    }
    step += 1
    toast.setOpacity(from + ((to - from) * step) / steps)
    if (step >= steps) {
      clearInterval(timer)
      done?.()
    }
  }, 18)
}

function ensureNoticeWindow(): BrowserWindow {
  if (noticeWin && !noticeWin.isDestroyed()) return noticeWin
  const notice = new BrowserWindow({
    width: NOTICE_WIDTH,
    height: 120,
    frame: false,
    transparent: true,
    resizable: false,
    movable: false,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    focusable: false,
    hasShadow: false,
    show: false,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false,
      sandbox: false,
      devTools: false
    }
  })
  noticeWin = notice
  notice.setAlwaysOnTop(true, 'floating')
  notice.setSkipTaskbar(true)

  notice.webContents.on('context-menu', (event) => event.preventDefault())
  notice.webContents.ipc.on('notice-close', () => dismissNoticeMessage())
  notice.webContents.ipc.on('notice-open', () => {
    const convId = noticeMessage?.convId
    showWindow()
    if (convId) win?.webContents.send('navigate', convId)
    dismissNoticeMessage()
  })
  notice.webContents.ipc.on('notice-cancel', (_event, id: unknown) => {
    if (typeof id === 'string') void cancelIncoming(id)
  })
  notice.webContents.ipc.on('notice-accept', (_event, id: unknown) => {
    if (typeof id !== 'string' || !id) return
    void decideOffer(id, 'accept')
      .then(() => dismissNoticeMessage())
      .catch(() => {})
  })
  notice.webContents.ipc.on('notice-reject', (_event, id: unknown) => {
    if (typeof id !== 'string' || !id) return
    void decideOffer(id, 'reject')
      .then(() => dismissNoticeMessage())
      .catch(() => {})
  })
  notice.once('ready-to-show', () => {
    if (notice.isDestroyed()) return
    notice.setOpacity(0)
    notice.showInactive()
    fadeToast(notice, 1)
  })
  notice.on('closed', () => {
    if (noticeWin === notice) noticeWin = null
  })
  notice.webContents.once('did-finish-load', () => {
    if (!notice.isDestroyed() && lastNoticePayload) {
      notice.webContents.send('notice', lastNoticePayload)
    }
  })

  void notice.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(noticeHtml())}`)
  return notice
}

function closeNotice(): void {
  const notice = noticeWin
  noticeWin = null
  lastNoticePayload = null
  if (!notice || notice.isDestroyed()) return
  fadeToast(notice, 0, () => {
    if (!notice.isDestroyed()) notice.destroy()
  })
}

function resetNotices(): void {
  if (noticeMessageTimer) {
    clearTimeout(noticeMessageTimer)
    noticeMessageTimer = null
  }
  noticeMessage = null
  for (const entry of noticeFailures.values()) clearTimeout(entry.timer)
  noticeFailures.clear()
  closeNotice()
}

// 桌面右上角的拖放接收区：平时点击穿透，光标进入才接收文件拖放。
const PAD_WIDTH = 468
const PAD_HEIGHT = 98
const PAD_EDGE = 20
const PAD_TOP = 56

let padWin: BrowserWindow | null = null
let padInteractive = false
let padDragging = false
let padSuspended = false
let padHoverAt = 0
let padPoller: NodeJS.Timeout | null = null

function padBounds(): Electron.Rectangle {
  const area = screen.getPrimaryDisplay().workArea
  return {
    x: area.x + area.width - PAD_WIDTH - PAD_EDGE,
    y: area.y + PAD_TOP,
    width: PAD_WIDTH,
    height: PAD_HEIGHT
  }
}

function positionDropPad(): void {
  if (padWin && !padWin.isDestroyed()) padWin.setBounds(padBounds())
}

function destroyDropPad(): void {
  if (padPoller) {
    clearInterval(padPoller)
    padPoller = null
  }
  const pad = padWin
  padWin = null
  if (pad && !pad.isDestroyed()) pad.destroy()
}

function createDropPad(): void {
  if (quitting || (padWin && !padWin.isDestroyed())) return
  const pad = new BrowserWindow({
    ...padBounds(),
    frame: false,
    transparent: true,
    resizable: false,
    movable: false,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    focusable: false,
    hasShadow: false,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  })
  padWin = pad
  padInteractive = false
  padDragging = false
  padSuspended = false
  pad.setIgnoreMouseEvents(true)
  pad.on('closed', () => {
    if (padWin === pad) padWin = null
  })

  const devUrl = process.env['ELECTRON_RENDERER_URL']?.replace('localhost', '127.0.0.1')
  const load = (): void => {
    if (!padWin || padWin.isDestroyed()) return
    const reveal = (): void => {
      if (!pad.isDestroyed()) pad.showInactive()
    }
    if (devUrl) {
      void pad
        .loadURL(`${devUrl}#droppad`)
        .then(reveal)
        .catch(() => setTimeout(load, 300))
    } else {
      void pad
        .loadFile(path.join(__dirname, '../renderer/index.html'), { hash: 'droppad' })
        .then(reveal)
        .catch(() => {})
    }
  }
  load()

  if (padPoller) clearInterval(padPoller)
  padPoller = setInterval(() => {
    const target = padWin
    if (!target || target.isDestroyed()) return
    const point = screen.getCursorScreenPoint()
    const box = target.getBounds()
    const inside =
      point.x >= box.x &&
      point.x < box.x + box.width &&
      point.y >= box.y &&
      point.y < box.y + box.height
    if (!inside) {
      if (padInteractive) {
        padInteractive = false
        padSuspended = false
        target.setIgnoreMouseEvents(true)
      }
      return
    }
    if (!padInteractive) {
      padInteractive = true
      padHoverAt = Date.now()
      target.setIgnoreMouseEvents(false)
    } else if (!padDragging && !padSuspended && Date.now() - padHoverAt > 400) {
      // 只是路过：没在拖东西就恢复点击穿透，别挡住右上角窗口的按钮。
      padSuspended = true
      target.setIgnoreMouseEvents(true)
    }
  }, 90)
}

function activeIncoming(snapshot: Snapshot): Message[] {
  return snapshot.messages.filter(
    (message) =>
      message.direction === 'in' &&
      message.kind !== 'text' &&
      (message.status === 'active' || message.status === 'accepted')
  )
}

function noticeRows(snapshot: Snapshot | null): NoticeRow[] {
  const rows: NoticeRow[] = []
  const active = snapshot ? activeIncoming(snapshot) : []
  for (const message of active) {
    const total = message.total || message.file_size || 0
    const received = message.received || 0
    rows.push({
      id: message.id,
      name: message.file_name || '文件',
      received,
      total,
      speed: message.speed_bps || 0,
      percent: total > 0 ? Math.min(100, (received / total) * 100) : 0,
      failed: false
    })
  }
  for (const [id, entry] of noticeFailures) {
    if (rows.some((row) => row.id === id)) continue
    rows.push({
      id,
      name: entry.name,
      received: 0,
      total: 0,
      speed: 0,
      percent: 0,
      failed: true
    })
  }
  return rows
}

function noticeHeight(rowCount: number, hasMessage: boolean): number {
  const listHeight = Math.min(rowCount, NOTICE_MAX_ROWS) * NOTICE_ROW_HEIGHT
  const messageHeight = hasMessage ? 56 : 0
  const gap = hasMessage && listHeight > 0 ? 10 : 0
  return 20 + 28 + messageHeight + gap + listHeight
}

function pushNotice(): void {
  const rows = noticeRows(noticeSnapshot)
  const hasMessage = noticeMessage !== null
  if (!hasMessage && rows.length === 0) {
    if (noticeWin) closeNotice()
    return
  }
  if (!settings.value.notifications) return
  const payload = { message: noticeMessage, rows }
  lastNoticePayload = payload
  const notice = ensureNoticeWindow()
  if (notice.isDestroyed()) return
  const height = noticeHeight(rows.length, hasMessage)
  if (notice.getBounds().height !== height) positionNotice(height)
  if (!notice.webContents.isLoading()) notice.webContents.send('notice', payload)
}

function showNoticeMessage(message: NoticeMessage): void {
  if (!settings.value.notifications) return
  noticeMessage = message
  if (noticeMessageTimer) {
    clearTimeout(noticeMessageTimer)
    noticeMessageTimer = null
  }
  // 带「接收」按钮的提醒要一直留到用户处理（接收、拒绝、过期都会让它自行撤掉），
  // 普通文本提醒还是照旧几秒后消失。
  if (!message.offerId) {
    noticeMessageTimer = setTimeout(() => {
      noticeMessageTimer = null
      noticeMessage = null
      pushNotice()
    }, NOTICE_MESSAGE_LIFETIME_MS)
  }
  pushNotice()
}

function dismissNoticeMessage(): void {
  if (noticeMessageTimer) {
    clearTimeout(noticeMessageTimer)
    noticeMessageTimer = null
  }
  if (!noticeMessage) return
  noticeMessage = null
  pushNotice()
}

function markNoticeFailure(id: string, name: string): void {
  const old = noticeFailures.get(id)
  if (old) clearTimeout(old.timer)
  const timer = setTimeout(() => {
    noticeFailures.delete(id)
    pushNotice()
  }, NOTICE_FAILED_LIFETIME_MS)
  noticeFailures.set(id, { name, timer })
}

// cancelIncoming 先本地收口再通知服务端，点下去立刻有反馈。
function cancelIncoming(id: string): void {
  const message = db.getMessage(id)
  if (
    message &&
    (message.status === 'pending' || message.status === 'active' || message.status === 'accepted')
  ) {
    db.setMessageStatus(id, 'canceled')
    progressSamples.delete(id)
    inboundSeen.delete(id)
    state.emit()
  }
  void apiPost(`/v1/offer/${encodeURIComponent(id)}/decision`, { action: 'cancel' }).catch(() => {
    // 已结束的传输无需再取消
  })
}

// 清空会话、删除设备之前，先把两个方向还在跑的传输停掉：
// 否则记录没了，文件还在继续传，对端也还会收到。
function stopConversationTransfers(convId: string): void {
  sender.cancelPeer(convId)
  for (const message of db.listActiveInbound()) {
    if (message.conversation_id !== convId) continue
    cancelIncoming(message.id)
  }
}

async function decideOffer(id: string, action: 'accept' | 'reject' | 'cancel'): Promise<void> {
  if (action === 'cancel') {
    cancelIncoming(id)
    setTimeout(() => {
      refreshInbox().catch(() => {})
    }, 300)
    return
  }
  // 先本地改状态，按钮点下去立刻有反应；服务端失败再回滚。
  const message = db.getMessage(id)
  const prev = message?.status
  if (message && prev === 'pending') {
    db.setMessageStatus(id, action === 'accept' ? 'accepted' : 'rejected')
    if (action === 'accept') inboundSeen.set(id, Date.now())
    state.emit()
  }
  try {
    await apiPost(`/v1/offer/${encodeURIComponent(id)}/decision`, { action })
  } catch (error) {
    if (message && prev) {
      db.setMessageStatus(id, prev)
      inboundSeen.delete(id)
      state.emit()
    }
    throw error
  } finally {
    setTimeout(() => {
      refreshInbox().catch(() => {})
    }, 300)
  }
}

// 入站传输长时间没有任何字节进来 = 对方已经退出/取消，直接收口，别让进度条卡着。
function reapStuckInbound(): void {
  const nowMs = Date.now()
  for (const message of db.listActiveInbound()) {
    if (message.status === 'pending') continue
    // 已确认但还排在并发队列里、一个字节都没收到的传输由本地服务按轮询情况判断，
    // 这里只收口真正开始接收后长时间没动静的，否则批量发送时排队文件会被误杀。
    if ((message.received ?? 0) <= 0) {
      inboundSeen.delete(message.id)
      continue
    }
    const last = inboundSeen.get(message.id)
    if (last === undefined) {
      // 第一次看到先记个起点，给它一个宽限期。
      inboundSeen.set(message.id, nowMs)
      continue
    }
    if (nowMs - last < INBOUND_IDLE_MS) continue
    cancelIncoming(message.id)
  }
}

function syncNotice(snapshot: Snapshot): void {
  noticeSnapshot = snapshot
  if (!settings.value.notifications) {
    resetNotices()
    return
  }
  // 等待接收的提醒没有自动消失时间，这条 offer 一旦被处理（接收/拒绝/取消/过期）就撤掉。
  const offerId = noticeMessage?.offerId
  if (offerId) {
    const pending = snapshot.messages.some((m) => m.id === offerId && m.status === 'pending')
    if (!pending) dismissNoticeMessage()
  }
  pushNotice()
}

let previewWin: BrowserWindow | null = null
let previewPath = ''

function openPreview(target: string): void {
  if (!fs.existsSync(target)) {
    void shell.openPath(target)
    return
  }
  // 同一时间只留一个预览窗口；连点同一个文件不再重新加载，只把窗口提到前面。
  if (previewWin && !previewWin.isDestroyed()) {
    if (previewPath !== target) {
      previewPath = target
      previewWin.webContents.send('preview-file', { file: target, dark: currentTheme() === 'dark' })
    }
    if (previewWin.isMinimized()) previewWin.restore()
    previewWin.show()
    previewWin.focus()
    return
  }
  previewPath = target
  const dark = currentTheme() === 'dark'
  const preview = new BrowserWindow({
    width: 900,
    height: 660,
    minWidth: 420,
    minHeight: 320,
    frame: false,
    show: false,
    title: path.basename(target),
    backgroundColor: dark ? '#141218' : '#FDF7FF',
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false,
      sandbox: false,
      devTools: false
    }
  })
  previewWin = preview
  preview.setMenu(null)
  preview.webContents.on('context-menu', (event) => event.preventDefault())
  preview.webContents.ipc.on('preview-close', () => preview.close())
  preview.webContents.ipc.on('preview-open', () => {
    void shell.openPath(previewPath)
  })
  preview.webContents.once('did-finish-load', () => {
    if (!preview.isDestroyed()) {
      preview.webContents.send('preview-file', { file: previewPath, dark: dark })
    }
  })
  preview.once('ready-to-show', () => {
    if (!preview.isDestroyed()) preview.show()
  })
  preview.on('closed', () => {
    if (previewWin === preview) previewWin = null
  })
  void preview.loadFile(path.join(APP_DIR, 'resources', 'preview.html'))
}

function showWindow(): void {
  startupHidden = false
  if (!win) {
    createWindow()
    return
  }
  if (win.isMinimized()) win.restore()
  win.show()
  win.focus()
}

async function refreshInfo(): Promise<void> {
  if (!endpoint) return
  const info = await request<DeviceInfo>(endpoint, 'GET', '/v1/info')
  state.setInfo(info)
}

function refreshConfig(): void {
  const recvDir = settings.recvDir || service.configuredRecvDir
  state.setConfig({
    deviceName: state.info?.name ?? '',
    recvDir,
    // Windows 下要带上同样的 args 才能查到我们写的那一条自启项。
    autostart: app.getLoginItemSettings({ path: process.execPath, args: AUTOSTART_ARGS }).openAtLogin
  })
  watchRecvDir(recvDir)
}

// 接收目录被外部改动（解压、改名、删除、其他程序写入）时主动通知文件管理页刷新。
let recvWatcher: fs.FSWatcher | null = null
let recvWatchDir = ''
let recvWatchTimer: NodeJS.Timeout | null = null

function watchRecvDir(dir: string): void {
  const target = dir ? path.resolve(dir) : ''
  if (target === recvWatchDir) return
  recvWatchDir = target
  recvWatcher?.close()
  recvWatcher = null
  if (!target) return
  try {
    recvWatcher = fs.watch(target, { persistent: false }, () => {
      if (recvWatchTimer) clearTimeout(recvWatchTimer)
      recvWatchTimer = setTimeout(() => {
        recvWatchTimer = null
        for (const window of BrowserWindow.getAllWindows()) {
          window.webContents.send('files:changed')
        }
      }, 300)
    })
    recvWatcher.on('error', () => {})
  } catch {
    // 目录还没建好，下次 refreshConfig 再试
  }
}

function platformLabelOf(platform: string): string {
  const raw = platform.toLowerCase()
  if (raw.includes('win')) return 'Windows'
  if (raw.includes('android')) return 'Android'
  if (raw.includes('mac') || raw.includes('darwin')) return 'macOS'
  if (raw.includes('linux')) return 'Linux'
  return ''
}

function notifyItem(item: Item): void {
  if (item.from_id && item.from_id === state.info?.id) return
  const convId =
    item.channel === 'public'
      ? PUBLIC_CONVERSATION_ID
      : item.channel === 'ble'
        ? `${BLE_PREFIX}${item.from_id}`
        : item.from_id
  if (item.channel !== 'public' && convId && settings.value.clipboardPeers.includes(convId)) {
    const text = item.body ?? ''
    if (item.kind === 'text' && text) {
      clipboard.writeText(text)
      const platform = platformLabelOf(item.platform || state.peers.get(item.from_id)?.platform || '')
      const who = platform ? `${platform}平台` : item.from_name || item.from_id || '对端'
      for (const window of BrowserWindow.getAllWindows()) {
        window.webContents.send('clipboard:copied', `${who}复制了文本`)
      }
    }
  }
  if (!settings.value.notifications) return
  if (item.channel === 'public' && !settings.value.publicNotifications) return
  if (item.channel !== 'public' && convId && settings.value.muted.includes(convId)) {
    return
  }
  if (win && win.isVisible() && win.isFocused()) return
  const title = item.from_name || item.from_id || 'X互传'
  const body =
    item.kind === 'text'
      ? item.body ?? ''
      : `${item.kind === 'folder' ? '发来文件夹' : '发来文件'} ${item.name ?? ''}`
  showNoticeMessage({
    title,
    body: body.slice(0, 160),
    convId,
    // 待确认接收的文件，通知上直接给一个「接收」按钮，不用先进聊天页。
    offerId: item.kind !== 'text' && item.status === 'pending' ? item.id : undefined
  })
}

function sendUiAction(action: 'bucket' | 'files' | 'share' | 'settings'): void {
  showWindow()
  win?.webContents.send('ui:action', action)
}

function createTray(): void {
  if (tray) return
  const image = nativeImage.createFromPath(assetPath('tray.png'))
  tray = new Tray(image)
  tray.setToolTip('X互传')
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: '打开', click: () => showWindow() },
      { type: 'separator' },
      { label: '暂存桶', click: () => sendUiAction('bucket') },
      { label: '文件管理', click: () => sendUiAction('files') },
      { label: '内网分发', click: () => sendUiAction('share') },
      { label: '设置', click: () => sendUiAction('settings') },
      { type: 'separator' },
      {
        label: '打开接收目录',
        click: () => {
          void shell.openPath(settings.recvDir || service.configuredRecvDir)
        }
      },
      { label: '重启 xlan 服务', click: () => void restartService().catch(() => {}) },
      { type: 'separator' },
      {
        label: '退出',
        click: () => {
          quitting = true
          app.quit()
        }
      }
    ])
  )
  tray.on('click', () => showWindow())
  tray.on('double-click', () => showWindow())
}

async function scanPeers(): Promise<Snapshot> {
  const res = await apiPost<{ ok: boolean; peers?: Peer[] }>('/v1/scan?deep=1', {})
  await refreshPeers()
  // 手动刷新以这次扫描为准：没应答的直接判离线，不用等窗口过期。
  state.markUnseenOffline(new Set((res.peers ?? []).map((peer) => peer.id)))
  await refreshInbox(lastItemTs > 0 ? lastItemTs - 5 : 0)
  state.emit()
  return state.snapshot()
}

function runXlanCli(args: string[]): Promise<{ ok: boolean; out: Record<string, unknown> }> {
  return new Promise((resolve) => {
    execFile(
      service.exe,
      args,
      { cwd: XLAN_DIR, windowsHide: true, encoding: 'utf8', timeout: 30_000, maxBuffer: 1024 * 1024 },
      (error, stdout) => {
        let out: Record<string, unknown> = {}
        const lines = String(stdout ?? '').split(/\r?\n/)
        for (const line of lines) {
          const text = line.trim()
          if (!text.startsWith('{')) continue
          try {
            out = JSON.parse(text) as Record<string, unknown>
            break
          } catch {
            // 继续找下一行
          }
        }
        resolve({ ok: !error, out })
      }
    )
  })
}

async function waitForName(name: string): Promise<void> {
  const deadline = Date.now() + 10_000
  while (Date.now() < deadline) {
    try {
      await refreshInfo()
      if (state.info?.name === name) break
    } catch {
      // 服务重启中
    }
    await new Promise((resolve) => setTimeout(resolve, 400))
  }
  await refreshPeers().catch(() => {})
  refreshConfig()
}

async function refreshPeers(): Promise<void> {
  const res = await apiGet<{ ok: boolean; peers: Peer[] }>('/v1/peers')
  const peers = res.peers ?? []
  db.updatePeerMeta(peers)
  state.upsertPeers(peers)
}

function isBlockedIncoming(item: Item): boolean {
  if (item.channel === 'public') return false
  const from = item.from_id
  if (!from || from === state.info?.id) return false
  return (settings.value.blocked ?? []).includes(from)
}

function rejectBlocked(item: Item): void {
  if (item.status !== 'pending') return
  void apiPost(`/v1/offer/${encodeURIComponent(item.id)}/decision`, { action: 'reject' }).catch(
    () => {}
  )
}

// 对端测速时落下的临时文件：不进消息列表、不通知，传完立刻删掉。
function isSpeedtestItem(name: string | undefined): boolean {
  return typeof name === 'string' && name.startsWith(SPEEDTEST_PREFIX)
}

function isSpeedtestTerminal(status: string | undefined): boolean {
  return (
    status === 'done' ||
    status === 'failed' ||
    status === 'canceled' ||
    status === 'rejected' ||
    status === 'expired'
  )
}

function dropSpeedtestItem(id: string): void {
  void apiDelete(`/v1/item/${encodeURIComponent(id)}?files=1`).catch(() => {})
}

// 分页把服务端记录全部补回来，启动时不会只停在最近 500 条。
const INBOX_PAGE = 500
const INBOX_MAX_PAGES = 20

// 不传 since 时按已同步位置增量拉；进程刚启动 lastItemTs 还是 0，走全量分页。
async function refreshInbox(since = lastItemTs > 0 ? lastItemTs - 5 : 0): Promise<void> {
  let changed = false
  for (let page = 0; page < INBOX_MAX_PAGES; page += 1) {
    const query = `/v1/inbox?limit=${INBOX_PAGE}&offset=${page * INBOX_PAGE}${
      since > 0 ? `&since=${since}` : ''
    }`
    const res = await apiGet<{ ok: boolean; items: Item[] }>(query)
    const all = res.items ?? []
    const items = all.filter((item) => !isBlockedIncoming(item) && !isSpeedtestItem(item.name))
    for (const item of all) {
      if (isBlockedIncoming(item)) rejectBlocked(item)
      else if (isSpeedtestItem(item.name)) {
        if (isSpeedtestTerminal(item.status)) dropSpeedtestItem(item.id)
      }
    }
    if (items.length > 0) {
      db.ingestItems(items, state.info?.id ?? '')
      for (const item of items) {
        lastItemTs = Math.max(lastItemTs, item.created_at || 0)
      }
      changed = true
    }
    if (all.length < INBOX_PAGE) break
  }
  if (changed) state.emit()
}

function handleEvent(event: unknown): void {
  const ev = event as { type?: string; ts?: number; data?: Record<string, unknown> }
  if (!ev?.type) return
  lastEventTs = Math.max(lastEventTs, Number(ev.ts) || 0)

  switch (ev.type) {
    case 'item.new': {
      const item = ev.data as unknown as Item
      if (!item?.id) return
      if (isBlockedIncoming(item)) {
        rejectBlocked(item)
        return
      }
      if (isSpeedtestItem(item.name)) {
        if (isSpeedtestTerminal(item.status)) dropSpeedtestItem(item.id)
        return
      }
      db.ingestItem(item, state.info?.id ?? '')
      lastItemTs = Math.max(lastItemTs, item.created_at || 0)
      state.emit()
      notifyItem(item)
      break
    }
    case 'item.update': {
      const data = ev.data as {
        id?: string
        status?: string
        reason?: string
        path?: string
        name?: string
        size?: number
      }
      if (!data?.id) return
      if (isSpeedtestItem(data.name)) {
        if (isSpeedtestTerminal(data.status)) dropSpeedtestItem(data.id)
        return
      }
      const old = db.getMessage(data.id)
      db.applyItemUpdate(data)
      if (data.status === 'active') {
        inboundSeen.set(data.id, Date.now())
      } else if (data.status) {
        progressSamples.delete(data.id)
        inboundSeen.delete(data.id)
      }
      if (
        old &&
        old.direction === 'in' &&
        old.kind !== 'text' &&
        old.status !== 'canceled' &&
        old.status !== 'rejected' &&
        (data.status === 'failed' || data.status === 'canceled' || data.status === 'rejected')
      ) {
        markNoticeFailure(data.id, old.file_name || '文件')
      }
      if (
        old &&
        old.direction === 'in' &&
        old.kind !== 'text' &&
        old.status !== 'done' &&
        data.status === 'done' &&
        !(win && win.isVisible() && win.isFocused()) &&
        !(old.channel === 'public' && !settings.value.publicNotifications) &&
        !(old.from_id && settings.value.muted.includes(old.from_id))
      ) {
        showNoticeMessage({
          title: old.from_name || 'X互传',
          body: `接收完成 ${old.file_name || data.name || '文件'}`,
          convId: old.conversation_id
        })
      }
      state.emit()
      break
    }
    case 'peer.online':
      state.upsertPeer(ev.data as unknown as Peer)
      break
    case 'peer.offline': {
      const data = ev.data as { id?: string }
      if (!data?.id) return
      state.markPeerOffline(data.id)
      for (const message of db.listActiveInbound()) {
        if (message.from_id !== data.id) continue
        cancelIncoming(message.id)
      }
      break
    }
    case 'transfer.progress': {
      const data = ev.data as { id?: string; received?: number; total?: number }
      if (!data?.id) return
      const received = Number(data.received) || 0
      const total = Number(data.total) || 0
      const at = Date.now()
      const prev = progressSamples.get(data.id)
      const elapsed = prev ? Math.max(0.05, (at - prev.at) / 1000) : 0
      const speed = prev ? Math.max(0, (received - prev.received) / elapsed) : 0
      progressSamples.set(data.id, { received, at })
      inboundSeen.set(data.id, at)
      db.setProgress(data.id, received, total, speed)
      queueMessageUpdate([data.id])
      break
    }
    default:
      break
  }
}

function scheduleReconnect(): void {
  if (stopping || reconnectTimer) return
  reconnectTimer = setTimeout(async () => {
    reconnectTimer = null
    if (stopping) return
    try {
      await refreshPeers()
      await refreshInbox(lastItemTs > 0 ? lastItemTs - 5 : 0)
    } catch {
      // 服务可能还在重启，下一轮再试
    }
    connectEvents()
  }, 1000)
}

function connectEvents(): void {
  if (!endpoint || stopping || eventReq) return
  eventReq = openEventStream(endpoint, {
    onEvent: handleEvent,
    onClose: () => {
      eventReq = null
      scheduleReconnect()
    }
  })
}

async function bootstrap(): Promise<void> {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  state.setSettings(settings.value)
  state.setService({ starting: true, running: false, warning: undefined })
  try {
    const result = await service.start()
    endpoint = result.endpoint
    state.setInfo(result.info)
    // 上次关掉应用时蓝牙广播是开着的，启动后自动恢复。
    if (settings.value.bleEnabled && result.info.id) {
      void ble.start(result.info.id, result.info.name ?? '')
    }
    db.purgeSelfConversation(result.info.id)
    // 上次进程退出时没传完的，不会再有结果了，启动时直接收口为失败。
    if (db.failUnfinishedOutbound() > 0) state.emit()
    state.setService({
      running: true,
      starting: false,
      base: result.endpoint.base,
      tls: result.endpoint.tls,
      foreign: result.foreign,
      warning: result.foreign ? '外部 xlan 实例' : undefined
    })
    refreshConfig()
    await refreshPeers()
    await refreshInbox()
    connectEvents()
    pollTimer = setInterval(() => {
      refreshPeers()
        .catch(() => {})
        .finally(() => state.refreshOnline())
      reapStuckInbound()
    }, 1000)
  } catch (error) {
    state.setService({
      running: false,
      starting: false,
      base: '',
      tls: true,
      foreign: false,
      warning: error instanceof Error ? error.message : '服务启动失败'
    })
    refreshConfig()
  }
}

async function restartService(): Promise<void> {
  const foreign = state.service.foreign
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  eventReq?.destroy()
  eventReq = null
  endpoint = null
  state.setService({ running: false, starting: true, base: '', tls: true, foreign: false, warning: undefined })
  if (!foreign) service.stop()
  await bootstrap()
  if (!state.service.running) {
    throw new Error(state.service.warning || '服务启动失败')
  }
}

function createWindow(): void {
  win = new BrowserWindow({
    width: 1120,
    height: 720,
    minWidth: 880,
    minHeight: 560,
    frame: false,
    show: false,
    icon: assetPath('icon.png'),
    backgroundColor: '#141218',
    webPreferences: {
      preload: path.join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  })

  const logFile = path.join(DATA_DIR, 'logs', 'renderer.log')
  const log = (line: string): void => {
    try {
      fs.appendFileSync(logFile, `${new Date().toISOString()} ${line}\n`)
    } catch {
      // 日志写不进去不影响主流程
    }
  }

  let shown = false
  const reveal = (): void => {
    if (!win || shown || startupHidden || win.isVisible()) return
    shown = true
    win.show()
    win.focus()
  }
  // 等 React 真正挂载出内容再显示：否则首帧只有深色底，看起来就是黑屏。
  let checking = false
  let contentRetry = 0
  const revealWhenReady = async (): Promise<void> => {
    if (!win || shown || checking || startupHidden) return
    checking = true
    try {
      for (let attempt = 0; attempt < 80; attempt += 1) {
        if (!win || quitting || shown) return
        const count: number = await win.webContents
          .executeJavaScript('document.getElementById("root")?.childElementCount ?? 0', false)
          .catch(() => 0)
        if (count > 0) {
          reveal()
          return
        }
        await new Promise((resolve) => setTimeout(resolve, 100))
      }
      if (win && !quitting && contentRetry < 2) {
        contentRetry += 1
        win.webContents.reload()
        return
      }
      reveal()
    } finally {
      checking = false
    }
  }
  win.once('ready-to-show', () => void revealWhenReady())
  // ready-to-show 依赖首帧绘制，显卡/合成异常时可能一直不触发，兜底把窗口显示出来。
  setTimeout(reveal, 12000)

  win.webContents.on('console-message', (event) => {
    if (event.level === 'warning' || event.level === 'error') {
      log(`console[${event.level}] ${event.message} (${event.sourceId}:${event.lineNumber})`)
    }
  })
  let loadedOnce = false
  win.webContents.on('did-finish-load', () => {
    loadedOnce = true
    void revealWhenReady()
  })
  win.webContents.on('did-fail-load', (_event, code, desc, url) => {
    log(`did-fail-load ${code} ${desc} ${url}`)
    if (code === -3 || !loadedOnce || quitting) return
    setTimeout(() => {
      if (win && !quitting) void loadRenderer()
    }, 500)
  })
  win.webContents.on('render-process-gone', (_event, details) => {
    log(`render-process-gone ${details.reason} exit=${details.exitCode}`)
    if (!quitting) win?.webContents.reload()
  })
  win.on('unresponsive', () => {
    log('window unresponsive, reloading')
    win?.webContents.reload()
  })
  win.on('close', (event) => {
    if (!quitting && settings.value.closeToTray) {
      event.preventDefault()
      win?.hide()
    }
  })
  win.on('closed', () => {
    win = null
    destroyDropPad()
  })

  void loadRenderer()
  createDropPad()
}

// 开发模式下 Electron 常常比 Vite 先起来，直接 loadURL 会 ERR_CONNECTION_REFUSED
// 留下一个黑窗口，这里按退避重试，直到渲染页真正加载成功。
let loading = false
async function loadRenderer(): Promise<void> {
  if (!win || loading) return
  loading = true
  try {
    // 统一走 IPv4：Windows 上 localhost 可能被解析到 [::1]，而 dev server 只监听了 127.0.0.1。
    const devUrl = process.env['ELECTRON_RENDERER_URL']?.replace('localhost', '127.0.0.1')
    for (let attempt = 0; attempt < 60; attempt += 1) {
      if (!win || quitting) return
      try {
        if (devUrl) {
          await win.loadURL(devUrl)
        } else {
          await win.loadFile(path.join(__dirname, '../renderer/index.html'))
        }
        return
      } catch {
        await new Promise((resolve) => setTimeout(resolve, 300))
      }
    }
  } finally {
    loading = false
  }
}

function registerIpc(): void {
  ipcMain.handle('state:get', () => state.snapshot())

  ipcMain.handle('service:restart', () => restartService())

  ipcMain.handle('xlan:scan', () => scanPeers())


  ipcMain.handle('text:send', async (_event, req: SendTextReq) => {
    const body = (req?.body ?? '').trim()
    if (!body) throw new Error('内容为空')
    if (req.to?.startsWith(BLE_PREFIX)) {
      const peerId = req.to.slice(BLE_PREFIX.length)
      const device = state.bluetooth.devices.find((item) => item.id === peerId)
      const id = randomUUID()
      db.addOutboundText(
        req.to,
        body,
        id,
        state.info?.id ?? '',
        state.info?.name ?? '',
        device?.name || peerId
      )
      state.emit()
      try {
        await ble.send(peerId, id, body)
        db.setMessageStatus(id, 'done')
        state.emit()
        return { ok: true, channel: 'ble' }
      } catch (error) {
        db.setMessageStatus(id, 'failed', error instanceof Error ? error.message : '发送失败')
        state.emit()
        throw error
      }
    }
    if (!req.to) {
      const id = randomUUID()
      db.addOutboundText(
        PUBLIC_CONVERSATION_ID,
        body,
        id,
        state.info?.id ?? '',
        state.info?.name ?? ''
      )
      state.emit()
      try {
        const resp = await apiPost<SendTextResp>('/v1/text', { body, to: '' })
        db.setMessageStatus(id, 'done')
        state.emit()
        setTimeout(() => {
          refreshInbox().catch(() => {})
        }, 2000)
        return resp
      } catch (error) {
        db.setMessageStatus(id, 'failed', error instanceof Error ? error.message : '发送失败')
        state.emit()
        throw error
      }
    }

    const id = randomUUID()
    const peer = state.peers.get(req.to)
    db.addOutboundText(req.to, body, id, state.info?.id ?? '', state.info?.name ?? '', peer?.name)
    state.emit()
    try {
      const resp = await apiPost<SendTextResp>('/v1/text', { body, to: req.to })
      db.setMessageStatus(id, 'done')
      state.emit()
      return resp
    } catch (error) {
      db.setMessageStatus(id, 'failed', error instanceof Error ? error.message : '发送失败')
      state.emit()
      throw error
    }
  })

  ipcMain.handle('text:retry', async (_event, id: string) => {
    const message = db.getMessage(id)
    if (!message || message.direction !== 'out' || message.kind !== 'text') return
    const body = (message.body ?? '').trim()
    if (!body) return
    const isPublic = message.conversation_id === PUBLIC_CONVERSATION_ID
    db.setMessageStatus(id, 'sending')
    state.emit()
    if (message.conversation_id.startsWith(BLE_PREFIX)) {
      try {
        await ble.send(message.conversation_id.slice(BLE_PREFIX.length), id, body)
        db.setMessageStatus(id, 'done')
        state.emit()
      } catch (error) {
        db.setMessageStatus(id, 'failed', error instanceof Error ? error.message : '发送失败')
        state.emit()
        throw error
      }
      return
    }
    try {
      await apiPost('/v1/text', { body, to: isPublic ? '' : message.conversation_id })
      db.setMessageStatus(id, 'done')
      state.emit()
      if (isPublic) {
        setTimeout(() => {
          refreshInbox().catch(() => {})
        }, 2000)
      }
    } catch (error) {
      db.setMessageStatus(id, 'failed', error instanceof Error ? error.message : '发送失败')
      state.emit()
      throw error
    }
  })

  ipcMain.handle('public:sync', async () => {
    try {
      await apiPost('/v1/public/sync', {})
    } catch {
      // 服务未就绪或同步失败时静默跳过，历史仍可正常收发。
    }
  })

  ipcMain.handle('conv:read', (_event, convId: string) => {
    db.markRead(convId)
    state.emit()
  })

  // 本地服务里还存着这些条目，不清掉的话一刷新就会被重新灌回聊天记录。
  const purgeServerItems = async (ids: string[]): Promise<void> => {
    for (const id of ids) {
      await apiDelete(`/v1/item/${encodeURIComponent(id)}`).catch(() => {})
    }
  }

  ipcMain.handle('conv:clear', async (_event, convId: string) => {
    if (!convId) return
    if (convId === PUBLIC_CONVERSATION_ID) {
      db.clearConversation(convId)
      state.emit()
      // 服务端也清掉公共频道历史并记下时间点，之后补齐不再拉回旧消息。
      await apiPost('/v1/public/clear', {}).catch(() => {})
      return
    }
    const ids = db.listMessageIds(convId)
    stopConversationTransfers(convId)
    db.clearConversation(convId)
    state.emit()
    await purgeServerItems(ids)
  })

  // 删除设备时，把它在本地留下的痕迹一并抹掉：会话、消息、备注名、黑名单、免打扰、置顶。
  // 同时记下删除时刻，在它重新被发现之前不再出现在列表里。
  const forgetDevices = async (ids: Set<string>): Promise<void> => {
    if (ids.size === 0) return
    for (const id of ids) {
      const messageIds = db.listMessageIds(id)
      stopConversationTransfers(id)
      db.deleteConversation(id)
      state.peers.delete(id)
      await purgeServerItems(messageIds)
    }
    const nowSec = Date.now() / 1000
    const current = settings.value
    const hidden = { ...(current.hidden ?? {}) }
    const aliases = { ...(current.aliases ?? {}) }
    let aliasChanged = false
    for (const id of ids) {
      hidden[id] = nowSec
      if (id in aliases) {
        delete aliases[id]
        aliasChanged = true
      }
    }
    const next: Partial<Settings> = {
      blocked: (current.blocked ?? []).filter((id) => !ids.has(id)),
      muted: (current.muted ?? []).filter((id) => !ids.has(id)),
      clipboardPeers: (current.clipboardPeers ?? []).filter((id) => !ids.has(id)),
      pinned: (current.pinned ?? []).filter((id) => !ids.has(id)),
      hidden
    }
    if (aliasChanged) next.aliases = aliases
    settings.update(next)
    state.setSettings(settings.value)
  }

  ipcMain.handle('conv:delete', async (_event, convId: string) => {
    if (!convId || convId === PUBLIC_CONVERSATION_ID) return
    await forgetDevices(new Set([convId]))
  })

  ipcMain.handle('conv:deleteOffline', async () => {
    const nowSec = Date.now() / 1000
    const hidden = settings.value.hidden ?? {}
    const alreadyHidden = (id: string): boolean => {
      const hiddenAt = hidden[id] ?? 0
      const lastSeen = state.peers.get(id)?.last_seen || 0
      return hiddenAt > 0 && lastSeen <= hiddenAt
    }
    const ids = new Set<string>()
    for (const conv of db.listConversations()) {
      if (conv.id === PUBLIC_CONVERSATION_ID) continue
      const peer = state.peers.get(conv.id)
      if (peer && nowSec - (peer.last_seen || 0) <= ONLINE_WINDOW_SEC) continue
      if (!alreadyHidden(conv.id)) ids.add(conv.id)
    }
    for (const peer of state.peers.values()) {
      if (nowSec - (peer.last_seen || 0) <= ONLINE_WINDOW_SEC) continue
      if (!alreadyHidden(peer.id)) ids.add(peer.id)
    }
    await forgetDevices(ids)
    return ids.size
  })

  ipcMain.handle('msg:delete', async (_event, id: string) => {
    if (!id) return
    const message = db.getMessage(id)
    if (!message) return
    // 正在发送/排队的不允许删，删了传输也不会停，对端照样收到。
    if (message.direction === 'out' && SENDING_STATUSES.has(message.status)) return
    db.deleteMessage(id)
    state.emit()
    // 服务端 inbox 里还留着这条，不清掉的话重连/重启会把它重新灌回来。
    await purgeServerItems([id])
  })

  ipcMain.handle('files:reveal', (_event, target: string) => {
    if (!target) return
    const file = path.resolve(target)
    if (fs.existsSync(file)) shell.showItemInFolder(file)
  })

  ipcMain.handle('files:open', async (_event, target: string) => {
    if (!target) return
    const file = path.resolve(target)
    if (!fs.existsSync(file)) throw new Error('文件不存在')
    const error = await shell.openPath(file)
    if (error) throw new Error(error)
  })

  ipcMain.handle('files:preview', (_event, target: string) => {
    if (target) openPreview(path.resolve(target))
  })

  ipcMain.handle('files:thumb', async (_event, target: string) => {
    if (!target) return null
    const file = path.resolve(target)
    const isImage = /\.(jpe?g|png|gif|webp|bmp|ico|heic|heif)$/i.test(file)
    const isVideo = /\.(mp4|m4v|mkv|webm|mov|avi|wmv|flv|ts|mpg|mpeg|3gp)$/i.test(file)
    if (!isImage && !isVideo) return null
    let stat: fs.Stats
    try {
      stat = fs.statSync(file)
    } catch {
      return null
    }
    // 命中内存/磁盘缓存直接返回，避免每次进聊天页都重新解码、重复抽视频首帧。
    // 版本号进 key：调整缩略图尺寸后旧缓存自动失效。
    const key = `v2|${file}|${stat.mtimeMs}|${stat.size}`
    const cached = thumbCache.get(key)
    if (cached) {
      thumbCache.delete(key)
      thumbCache.set(key, cached)
      return cached
    }
    const disk = path.join(THUMB_DIR, `${createHash('sha1').update(key).digest('hex')}.txt`)
    try {
      const fromDisk = fs.readFileSync(disk, 'utf8')
      if (fromDisk) {
        rememberThumb(key, fromDisk)
        return fromDisk
      }
    } catch {
      // 还没有磁盘缓存
    }
    try {
      let url: string
      if (isImage) {
        const image = nativeImage.createFromPath(file)
        if (image.isEmpty()) return null
        url = image.resize({ width: 128, quality: 'good' }).toDataURL()
      } else {
        // 视频交给系统缩略图接口，取到的就是首帧；系统缺解码器时回落成图标。
        const image = await nativeImage.createThumbnailFromPath(file, { width: 160, height: 160 })
        if (!image || image.isEmpty()) return null
        url = image.toDataURL()
      }
      rememberThumb(key, url)
      void fs.promises.writeFile(disk, url).catch(() => {})
      return url
    } catch {
      return null
    }
  })

  ipcMain.handle('files:exists', (_event, target: string) => {
    if (!target) return false
    try {
      return fs.existsSync(path.resolve(target))
    } catch {
      return false
    }
  })

  ipcMain.handle('files:size', async (_event, target: string) => {
    if (!target) return 0
    const full = path.resolve(target)
    try {
      const stat = await fs.promises.stat(full)
      return stat.isDirectory() ? await dirSizeAsync(full) : stat.size
    } catch {
      return 0
    }
  })

  ipcMain.handle('files:delete', (_event, target: string) => {
    if (!target) return
    try {
      fs.rmSync(path.resolve(target), { recursive: true, force: true })
    } catch {
      // ignore
    }
  })

  ipcMain.handle('files:rename', async (_event, target: string, name: string) => {
    if (!target) throw new Error('路径为空')
    const full = path.resolve(target)
    const next = (name ?? '').trim()
    if (!next || next === path.basename(full)) return full
    if (/[\\/:*?"<>|]/.test(next) || /[. ]$/.test(next)) throw new Error('名称不合法')
    const dest = path.join(path.dirname(full), next)
    if (fs.existsSync(dest)) throw new Error('同名文件已存在')
    await fs.promises.rename(full, dest)
    return dest
  })

  ipcMain.handle('files:trash', async (_event, target: string) => {
    if (!target) return
    const full = path.resolve(target)
    if (!fs.existsSync(full)) return
    await shell.trashItem(full)
  })

  ipcMain.handle('files:saveAs', async (event, req: { source?: string; name?: string }) => {
    const source = req?.source ? path.resolve(req.source) : ''
    if (!source || !fs.existsSync(source)) throw new Error('文件不存在')
    if (fs.statSync(source).isDirectory()) throw new Error('文件夹不支持另存为')
    const parent = BrowserWindow.fromWebContents(event.sender)
    const options: Electron.SaveDialogOptions = {
      defaultPath: req?.name || path.basename(source)
    }
    const result = parent
      ? await dialog.showSaveDialog(parent, options)
      : await dialog.showSaveDialog(options)
    if (result.canceled || !result.filePath) return false
    if (path.resolve(result.filePath) !== source) {
      fs.copyFileSync(source, result.filePath)
    }
    return true
  })

  ipcMain.handle('files:pick', async (event) => {
    const parent = BrowserWindow.fromWebContents(event.sender)
    const options: Electron.OpenDialogOptions = { properties: ['openFile', 'multiSelections'] }
    const result = parent
      ? await dialog.showOpenDialog(parent, options)
      : await dialog.showOpenDialog(options)
    return result.canceled ? [] : inspectEntries(result.filePaths)
  })

  ipcMain.handle('files:pickImages', async (event) => {
    const parent = BrowserWindow.fromWebContents(event.sender)
    const options: Electron.OpenDialogOptions = {
      properties: ['openFile', 'multiSelections'],
      filters: [
        {
          name: '图片',
          extensions: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'ico', 'heic', 'heif', 'tif', 'tiff']
        }
      ]
    }
    const result = parent
      ? await dialog.showOpenDialog(parent, options)
      : await dialog.showOpenDialog(options)
    return result.canceled ? [] : inspectEntries(result.filePaths)
  })

  ipcMain.handle('files:pickApks', async (event) => {
    const parent = BrowserWindow.fromWebContents(event.sender)
    const options: Electron.OpenDialogOptions = {
      properties: ['openFile', 'multiSelections'],
      filters: [{ name: '安装包', extensions: ['apk', 'apks', 'xapk'] }]
    }
    const result = parent
      ? await dialog.showOpenDialog(parent, options)
      : await dialog.showOpenDialog(options)
    return result.canceled ? [] : inspectEntries(result.filePaths)
  })

  ipcMain.handle('files:pickMedia', async (event) => {
    const parent = BrowserWindow.fromWebContents(event.sender)
    const options: Electron.OpenDialogOptions = {
      properties: ['openFile', 'multiSelections'],
      filters: [
        {
          name: '图片和视频',
          extensions: [
            'png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'ico', 'heic', 'heif', 'tif', 'tiff',
            'mp4', 'm4v', 'mov', 'webm', 'mkv', 'avi', 'wmv', 'flv', 'ts', 'mts', 'm2ts',
            '3gp', 'ogv'
          ]
        }
      ]
    }
    const result = parent
      ? await dialog.showOpenDialog(parent, options)
      : await dialog.showOpenDialog(options)
    return result.canceled ? [] : inspectEntries(result.filePaths)
  })

  ipcMain.handle('files:pickFolders', async (event) => {
    const parent = BrowserWindow.fromWebContents(event.sender)
    const options: Electron.OpenDialogOptions = { properties: ['openDirectory', 'multiSelections'] }
    const result = parent
      ? await dialog.showOpenDialog(parent, options)
      : await dialog.showOpenDialog(options)
    return result.canceled ? [] : inspectEntries(result.filePaths)
  })

  ipcMain.handle('files:pickFolder', async (event) => {
    const parent = BrowserWindow.fromWebContents(event.sender)
    const options: Electron.OpenDialogOptions = { properties: ['openDirectory'] }
    const result = parent
      ? await dialog.showOpenDialog(parent, options)
      : await dialog.showOpenDialog(options)
    if (result.canceled || result.filePaths.length === 0) return null
    return inspectEntries(result.filePaths.slice(0, 1))[0] ?? null
  })

  ipcMain.handle('files:inspect', (_event, paths: string[]) => inspectEntries(paths ?? []))

  // 文件管理页列出接收目录：跳过 .parts 这类隐藏项，按修改时间从新到旧。
  ipcMain.handle('files:listDir', async (_event, dir: string) => {
    if (!dir) return []
    const base = path.resolve(dir)
    // 目录可能是后建的，进文件管理页时再补一次监听。
    watchRecvDir(base)
    let dirents: fs.Dirent[]
    try {
      dirents = await fs.promises.readdir(base, { withFileTypes: true })
    } catch {
      return []
    }
    const entries: FileEntry[] = []
    for (const dirent of dirents) {
      if (dirent.name.startsWith('.')) continue
      const full = path.join(base, dirent.name)
      try {
        const stat = await fs.promises.stat(full)
        entries.push({
          path: full,
          name: dirent.name,
          kind: stat.isDirectory() ? 'folder' : 'file',
          size: stat.isDirectory() ? 0 : stat.size,
          exists: true,
          mtime: stat.mtimeMs
        })
      } catch {
        // 文件可能刚好被删掉
      }
    }
    entries.sort((a, b) => (b.mtime ?? 0) - (a.mtime ?? 0))
    return entries
  })

  ipcMain.on('pad:drag', (_event, active: boolean) => {
    padDragging = active === true
    if (padDragging) {
      padSuspended = false
      padHoverAt = Date.now()
      if (padWin && !padWin.isDestroyed() && !padInteractive) {
        padInteractive = true
        padWin.setIgnoreMouseEvents(false)
      }
    }
  })

  ipcMain.handle('pad:receive', (_event, req: { paths?: string[]; open?: boolean }) => {
    const entries = inspectEntries((req?.paths ?? []).filter(Boolean))
    if (entries.length === 0) return { count: 0 }
    if (win && !win.isDestroyed()) win.webContents.send('bucket:add', entries)
    if (req?.open) showWindow()
    return { count: entries.length }
  })

  ipcMain.handle('files:send', (_event, req: SendFilesReq) => {
    const to = req?.to ?? ''
    if (!to) throw new Error('请选择设备')
    const paths = (req.paths ?? []).filter(Boolean)
    if (to.startsWith(BLE_PREFIX)) return sendBleFiles(to, paths)
    const peer = state.peers.get(to)
    const jobId = sender.send(
      to,
      peer?.name ?? to,
      paths,
      state.info?.id ?? '',
      state.info?.name ?? ''
    )
    if (!jobId) throw new Error('没有可发送的项目')
    return jobId
  })

  ipcMain.handle('screenshot:capture', async () => {
    if (capturingShot) return null
    capturingShot = true
    const wasVisible = Boolean(win && !win.isDestroyed() && win.isVisible())
    // 立刻藏起主窗口，遮罩直接盖在桌面上，中间不插任何等待/过渡。
    // Windows 隐藏窗口会带淡出动画，先清零透明度再藏，藏完恢复，遮罩才能瞬间出现。
    if (wasVisible && win) {
      win.setOpacity(0)
      win.hide()
      win.setOpacity(1)
    }
    try {
      const image = await captureRegion()
      if (!image) return null
      fs.mkdirSync(SCREENSHOT_DIR, { recursive: true })
      const file = path.join(SCREENSHOT_DIR, `截图_${Date.now()}.png`)
      fs.writeFileSync(file, image.toPNG())
      return inspectEntries([file])[0] ?? null
    } finally {
      capturingShot = false
      if (wasVisible && win && !win.isDestroyed()) {
        win.show()
        win.focus()
      }
    }
  })

  ipcMain.handle('files:cancel', (_event, messageId: string) => {
    if (!messageId) return
    const message = db.getMessage(messageId)
    if (message?.channel === 'ble') {
      db.setMessageStatus(messageId, 'canceled')
      queueMessageUpdate([messageId])
      ble.cancelFile(messageId)
      return
    }
    sender.cancelByMessage(messageId)
  })

  ipcMain.handle('files:retry', async (_event, req: RetryFileReq) => {
    if (!req?.to || !req.id) return
    if (req.to.startsWith(BLE_PREFIX)) {
      try {
        await retryBleFile(req.to, req.id)
      } catch (error) {
        db.setMessageStatus(req.id, 'failed', error instanceof Error ? error.message : '发送失败')
        queueMessageUpdate([req.id])
        throw error
      }
      return
    }
    sender.retry(req.to, req.id)
  })

  ipcMain.handle('offer:decision', async (_event, req: DecisionReq) => {
    if (!req?.id) return
    await decideOffer(req.id, req.action)
  })

  ipcMain.handle('app:version', () => ({
    app: app.getVersion().replace('-Beta', 'Beta'),
    xlan: state.info?.version ?? ''
  }))

  ipcMain.handle('app:openExternal', async (_event, url: string) => {
    if (typeof url !== 'string' || !/^https?:\/\//.test(url)) return
    await shell.openExternal(url)
  })

  ipcMain.handle('app:lanInfo', () => currentLan())

  ipcMain.handle('hotspot:status', () => hotspotStatus())
  ipcMain.handle('hotspot:start', async () => {
    const status = await hotspotStart()
    if (status.running) hotspotOwned = true
    return status
  })
  ipcMain.handle('hotspot:stop', async () => {
    const status = await hotspotStop()
    hotspotOwned = false
    return status
  })

  ipcMain.handle('ble:start', () => {
    if (!state.info?.id) throw new Error('设备信息未就绪')
    settings.update({ bleEnabled: true })
    state.setSettings(settings.value)
    return ble.start(state.info.id, state.info.name ?? '')
  })
  ipcMain.handle('ble:stop', () => {
    settings.update({ bleEnabled: false })
    state.setSettings(settings.value)
    return ble.stop()
  })
  ipcMain.handle('webchat:status', () => webChat.status())
  ipcMain.handle('webchat:start', async () => {
    settings.update({ webChat: true })
    state.setSettings(settings.value)
    return webChat.start()
  })
  ipcMain.handle('webchat:stop', async () => {
    settings.update({ webChat: false })
    state.setSettings(settings.value)
    return webChat.stop()
  })
  ipcMain.handle('ble:connect', async (_event, id: string) => {
    if (typeof id !== 'string' || !id) return
    // 广播关着时点「重连」：先把蓝牙通道打开，助手连上管道后再补发 connect。
    if (!ble.snapshot.on) {
      if (!state.info?.id) throw new Error('设备信息未就绪')
      settings.update({ bleEnabled: true })
      state.setSettings(settings.value)
      await ble.start(state.info.id, state.info.name ?? '')
    }
    ble.connect(id)
  })
  powerMonitor.on('resume', () => {
    void hotspotStatus()
      .then((status) => {
        if (win && !win.isDestroyed()) win.webContents.send('hotspot', status)
      })
      .catch(() => {})
  })
  ipcMain.handle('hotspot:settings', async () => {
    await shell.openExternal('ms-settings:network-mobilehotspot')
  })

  ipcMain.handle('settings:update', (_event, patch: Partial<Settings>) => {
    settings.update(patch ?? {})
    state.setSettings(settings.value)
    if (patch?.notifications === false) resetNotices()
    else if (patch?.theme !== undefined) closeNotice()
  })

  ipcMain.handle('config:setName', async (_event, name: string) => {
    const trimmed = (name ?? '').trim().slice(0, 15)
    if (!trimmed) throw new Error('设备名不能为空')
    await runXlanCli(['config', 'set', 'name', trimmed])
    await waitForName(trimmed)
    state.setConfig({ deviceName: trimmed })
  })

  ipcMain.handle('config:pickRecvDir', async (event) => {
    const parent = BrowserWindow.fromWebContents(event.sender)
    const options: Electron.OpenDialogOptions = { properties: ['openDirectory', 'createDirectory'] }
    const result = parent
      ? await dialog.showOpenDialog(parent, options)
      : await dialog.showOpenDialog(options)
    if (result.canceled || result.filePaths.length === 0) return null
    const dir = result.filePaths[0]
    settings.setRecvDir(dir)
    service.setRecvDir(dir)
    await runXlanCli(['config', 'set', 'recv_dir', dir])
    await new Promise((resolve) => setTimeout(resolve, 1500))
    await refreshInfo().catch(() => {})
    refreshConfig()
    return dir
  })

  ipcMain.handle('config:resetRecvDir', async () => {
    fs.mkdirSync(RECV_DIR, { recursive: true })
    settings.setRecvDir('')
    service.setRecvDir(RECV_DIR)
    await runXlanCli(['config', 'set', 'recv_dir', RECV_DIR])
    await new Promise((resolve) => setTimeout(resolve, 1500))
    await refreshInfo().catch(() => {})
    refreshConfig()
    return RECV_DIR
  })

  ipcMain.handle('config:autostart', (_event, enabled: boolean) => {
    app.setLoginItemSettings({ openAtLogin: !!enabled, path: process.execPath, args: AUTOSTART_ARGS })
    refreshConfig()
  })

  ipcMain.handle('trust:set', async (_event, id: string, trusted: boolean) => {
    await apiPost('/v1/trust', { id, trusted: !!trusted })
    await refreshPeers()
  })

  // 真实文件传输测速：向对端发一个 8MiB 的临时文件，5 秒内多线程反复写前半段保持传输活跃，
  // 最后补上后半段让对端完成落盘；对端识别到测速文件名后不入库、直接删除。
  ipcMain.handle('speed:test', async (event, peerId: string) => {
    const peer = state.peers.get(peerId)
    if (!peer?.addr || !peer.port) throw new Error('设备不在线')
    const host = peer.addr
    const port = peer.port
    const proto = await detectPeerProto(host, port)
    const offer = await peerJson(proto, host, port, 'POST', '/v1/offer', {
      from_id: state.info?.id ?? '',
      from_name: state.info?.name ?? '',
      to_id: peerId,
      kind: 'file',
      name: `xlan-speedtest-${Date.now()}.bin`,
      size: SPEEDTEST_SIZE
    })
    const offerId = typeof offer.id === 'string' ? offer.id : ''
    if (!offerId) throw new Error('对端没有返回传输编号')
    let accepted = offer.accepted === true || offer.duplicate === true
    if (!accepted) {
      const waitUntil = Date.now() + 6000
      while (Date.now() < waitUntil) {
        await new Promise((resolve) => setTimeout(resolve, 400))
        const st = await peerJson(
          proto,
          host,
          port,
          'GET',
          `/v1/offer/${encodeURIComponent(offerId)}`
        ).catch(() => ({} as Record<string, unknown>))
        const status = typeof st.status === 'string' ? st.status : ''
        if (status === 'active' || status === 'done') {
          accepted = true
          break
        }
        if (status === 'failed' || status === 'expired') break
      }
    }
    if (!accepted) throw new Error('对方未接受测速文件（需对方信任本机）')

    const dataUrl = `/v1/offer/${encodeURIComponent(offerId)}/data`
    const block = Buffer.alloc(SPEEDTEST_CHUNK)
    let measured = 0
    let failure: Error | null = null
    const put = async (start: number, end: number): Promise<void> => {
      const res = await peerRequest(
        proto,
        host,
        port,
        'PUT',
        dataUrl,
        block,
        {
          'content-type': 'application/octet-stream',
          'content-range': `bytes ${start}-${end}/${SPEEDTEST_SIZE}`
        },
        (n) => {
          measured += n
        }
      )
      if (res.status < 200 || res.status >= 300) {
        throw new Error(`上传失败：HTTP ${res.status}`)
      }
    }
    const deadline = Date.now() + 5000
    const worker = async (): Promise<void> => {
      while (!failure && Date.now() < deadline) {
        try {
          await put(0, SPEEDTEST_CHUNK - 1)
        } catch (error) {
          failure = error instanceof Error ? error : new Error(String(error))
        }
      }
    }
    const workers = [worker(), worker(), worker(), worker()]
    const samples: number[] = []
    let lastBytes = 0
    let peak = 0
    for (let index = 0; index < 5; index += 1) {
      await new Promise((resolve) => setTimeout(resolve, 1000))
      const sample = Math.max(0, measured - lastBytes)
      lastBytes = measured
      samples.push(sample)
      peak = Math.max(peak, sample)
      if (!event.sender.isDestroyed()) {
        event.sender.send('speed:progress', { id: peerId, sample, index: index + 1 })
      }
    }
    await Promise.all(workers)
    if (failure) throw failure
    const bytes = measured
    await put(SPEEDTEST_CHUNK, SPEEDTEST_SIZE - 1)
    const avg = samples.length
      ? Math.round(samples.reduce((sum, value) => sum + value, 0) / samples.length)
      : 0
    return { peak: Math.round(peak), avg, bytes, seconds: 5, samples }
  })

  // 内网分发：把暂存桶里的文件通过局域网 HTTP 服务出去，扫码即可下载。
  ipcMain.handle('share:start', async (_event, entries: FileEntry[]) => {
    const host = shareHost()
    if (!host) return { running: false, url: '', count: 0, bytes: 0, error: '未连接到局域网' }
    const files: ShareFile[] = []
    const targets = (entries ?? [])
      .map((entry) => entry?.path)
      .filter((item): item is string => typeof item === 'string' && item.length > 0)
    collectShareFiles(targets, files)
    // 暂存桶为空也照常启动，之后往桶里加文件会立即出现在分发页上。
    shareFiles = files
    if (!shareServer) {
      await new Promise<void>((resolve, reject) => {
        const server = http.createServer((req, res) => {
          try {
            serveShare(req, res)
          } catch {
            try {
              res.writeHead(500)
              res.end()
            } catch {
              // 连接已经断开
            }
          }
        })
        server.on('error', (error) => {
          shareServer = null
          reject(error)
        })
        server.listen(0, '0.0.0.0', () => {
          shareServer = server
          sharePort = (server.address() as AddressInfo).port
          resolve()
        })
      })
    }
    return shareSnapshot()
  })

  ipcMain.handle('share:stop', () => {
    shareServer?.close()
    shareServer = null
    shareFiles = []
    return shareSnapshot()
  })

  ipcMain.handle('share:status', () => shareSnapshot())

  ipcMain.handle('cache:size', () => ({ total: cacheSize() }))

  ipcMain.handle('cache:clean', async (_event, preservePaths?: string[]) => {
    const res = await apiPost<{ parts?: number; freed_bytes?: number; records?: number }>(
      '/v1/cache/clean',
      { older_than_sec: 30 * 24 * 3600, dry_run: false }
    )
    // 临时目录和缩略图缓存也一起清掉；暂存桶里的文件留着。
    const keep = new Set((preservePaths ?? []).map((item) => path.resolve(item)))
    const localFreed = purgeDirExcept(TMP_DIR, keep) + clearDir(THUMB_DIR)
    thumbCache.clear()
    return {
      parts: res.parts ?? 0,
      freedBytes: (res.freed_bytes ?? 0) + localFreed,
      records: res.records ?? 0
    }
  })

  ipcMain.handle('doctor', async (): Promise<DoctorResult> => {
    const { ok, out } = await runXlanCli(['doctor', '--json'])
    const checks = Array.isArray(out.checks)
      ? (out.checks as { name?: string; ok?: boolean; detail?: string }[]).map((check) => ({
          name: String(check.name ?? ''),
          ok: check.ok !== false,
          detail: check.detail ? String(check.detail) : undefined
        }))
      : []
    return { ok: ok && out.ok !== false, checks }
  })

  ipcMain.on('win:minimize', (event) => {
    BrowserWindow.fromWebContents(event.sender)?.minimize()
  })
  ipcMain.on('win:toggleMaximize', (event) => {
    const window = BrowserWindow.fromWebContents(event.sender)
    if (!window) return
    if (window.isMaximized()) window.unmaximize()
    else window.maximize()
  })
  ipcMain.on('win:close', (event) => {
    BrowserWindow.fromWebContents(event.sender)?.close()
  })
}

const locked = app.requestSingleInstanceLock()
if (!locked) {
  app.quit()
} else {
  app.on('second-instance', (_event, argv) => {
    // 自启项拉起的第二个实例不该把窗口顶到前台。
    if (argv.includes('--hidden')) return
    showWindow()
  })

  app.whenReady().then(async () => {
    // 旧版自启项没带 --hidden，启动时补写一次，用户不必手动开关一遍。
    const login = app.getLoginItemSettings()
    if (login.openAtLogin) {
      app.setLoginItemSettings({ openAtLogin: true, path: process.execPath, args: AUTOSTART_ARGS })
    }
    Menu.setApplicationMenu(null)
    registerIpc()
    createWindow()
    screen.on('display-metrics-changed', positionDropPad)
    screen.on('display-added', positionDropPad)
    screen.on('display-removed', positionDropPad)
    startLanWatch()
    createTray()
    if (settings.value.webChat) void webChat.start().catch(() => {})
    await bootstrap()
  })

  app.on('window-all-closed', () => {
    app.quit()
  })

  app.on('before-quit', (event) => {
    quitting = true
    stopping = true
    resetNotices()
    if (reconnectTimer) clearTimeout(reconnectTimer)
    if (pollTimer) clearInterval(pollTimer)
    if (lanTimer) clearInterval(lanTimer)
    eventReq?.destroy()
    eventReq = null
    sender.dispose()
    ble.dispose()
    void webChat.stop()
    if (!hotspotOwned || hotspotClosing) return
    // 关热点要等 WinRT 返回，先拦下这次退出，最多等 8 秒。
    hotspotClosing = true
    event.preventDefault()
    const timeout = new Promise<void>((resolve) => setTimeout(resolve, 8000))
    void Promise.race([hotspotStop().catch(() => undefined), timeout]).finally(() => {
      hotspotOwned = false
      app.quit()
    })
  })

  app.on('will-quit', () => {
    tray?.destroy()
    tray = null
    if (state.service.running && !state.service.foreign) service.stop()
    db.close()
  })
}
