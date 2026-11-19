import fs from 'node:fs'
import path from 'node:path'
import type { SeedColor, Settings } from '../shared/types'

const SEEDS: SeedColor[] = [
  'system',
  'green',
  'purple',
  'blue',
  'orange',
  'teal',
  'pink',
  'red',
  'indigo',
  'yellow',
  'grey'
]

const DEFAULTS: Settings = {
  theme: 'system',
  seed: 'green',
  notifications: true,
  publicNotifications: false,
  closeToTray: true,
  quickPreview: true,
  enterToSend: true,
  autoAcceptAll: false,
  misans: true,
  bleEnabled: false,
  webChat: false,
  relayUrl: '',
  blocked: [],
  aliases: {},
  muted: [],
  pinned: [],
  hidden: {},
  speedPeaks: {}
}

type Stored = Settings & { recvDir?: string }

function strList(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string' && item !== '')
    : []
}

function timeMap(value: unknown): Record<string, number> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const out: Record<string, number> = {}
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (key && typeof raw === 'number' && Number.isFinite(raw)) out[key] = raw
  }
  return out
}

function aliasMap(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const out: Record<string, string> = {}
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (key && typeof raw === 'string' && raw.trim()) out[key] = raw.trim()
  }
  return out
}

export class SettingsStore {
  value: Settings = { ...DEFAULTS }
  recvDir = ''

  constructor(private readonly file: string) {
    try {
      const raw = JSON.parse(fs.readFileSync(file, 'utf8')) as Partial<Stored>
      this.value = {
        theme: raw.theme === 'light' || raw.theme === 'dark' ? raw.theme : 'system',
        seed: SEEDS.includes(raw.seed as SeedColor) ? (raw.seed as SeedColor) : 'green',
        notifications: raw.notifications !== false,
        publicNotifications: raw.publicNotifications === true,
        closeToTray: raw.closeToTray !== false,
        quickPreview: raw.quickPreview !== false,
        enterToSend: raw.enterToSend !== false,
        autoAcceptAll: raw.autoAcceptAll === true,
        misans: raw.misans !== false,
        bleEnabled: raw.bleEnabled === true,
        webChat: raw.webChat === true,
        relayUrl: typeof raw.relayUrl === 'string' ? raw.relayUrl.trim() : '',
        blocked: strList(raw.blocked),
        aliases: aliasMap(raw.aliases),
        muted: strList(raw.muted),
        pinned: strList(raw.pinned),
        hidden: timeMap(raw.hidden),
        speedPeaks: timeMap(raw.speedPeaks)
      }
      this.recvDir = typeof raw.recvDir === 'string' ? raw.recvDir : ''
    } catch {
      // 首次运行或文件损坏时用默认值
    }
  }

  update(patch: Partial<Settings>): void {
    this.value = { ...this.value, ...patch }
    this.save()
  }

  setRecvDir(dir: string): void {
    this.recvDir = dir
    this.save()
  }

  private save(): void {
    fs.mkdirSync(path.dirname(this.file), { recursive: true })
    const payload: Stored = { ...this.value, recvDir: this.recvDir || undefined }
    fs.writeFileSync(this.file, JSON.stringify(payload, null, 2), 'utf8')
  }
}
