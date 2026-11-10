import { contextBridge, ipcRenderer, webUtils } from 'electron'
import type {
  BleState,
  DecisionReq,
  DoctorResult,
  FileEntry,
  HotspotStatus,
  LanInfo,
  Message,
  RetryFileReq,
  SendFilesReq,
  SendTextReq,
  Settings,
  ShareStatus,
  Snapshot,
  SpeedTestProgress,
  SpeedTestResult,
  WebChatStatus,
  XtApi
} from '../shared/types'

const api: XtApi = {
  getState: () => ipcRenderer.invoke('state:get') as Promise<Snapshot>,
  onState: (cb) => {
    const handler = (_event: Electron.IpcRendererEvent, snapshot: Snapshot): void => cb(snapshot)
    ipcRenderer.on('state', handler)
    return () => ipcRenderer.removeListener('state', handler)
  },
  onMessages: (cb) => {
    const handler = (_event: Electron.IpcRendererEvent, messages: Message[]): void => cb(messages)
    ipcRenderer.on('messages', handler)
    return () => ipcRenderer.removeListener('messages', handler)
  },
  scan: () => ipcRenderer.invoke('xlan:scan') as Promise<Snapshot>,
  sendText: (req: SendTextReq) => ipcRenderer.invoke('text:send', req),
  retryText: (id: string) => ipcRenderer.invoke('text:retry', id) as Promise<void>,
  decide: (req: DecisionReq) => ipcRenderer.invoke('offer:decision', req) as Promise<void>,
  markRead: (convId: string) => ipcRenderer.invoke('conv:read', convId) as Promise<void>,
  clearConversation: (convId: string) =>
    ipcRenderer.invoke('conv:clear', convId) as Promise<void>,
  deleteConversation: (convId: string) =>
    ipcRenderer.invoke('conv:delete', convId) as Promise<void>,
  deleteOfflineDevices: () => ipcRenderer.invoke('conv:deleteOffline') as Promise<number>,
  deleteMessage: (id: string) => ipcRenderer.invoke('msg:delete', id) as Promise<void>,
  revealPath: (path: string) => ipcRenderer.invoke('files:reveal', path) as Promise<void>,
  openPath: (path: string) => ipcRenderer.invoke('files:open', path) as Promise<void>,
  previewFile: (path: string) => ipcRenderer.invoke('files:preview', path) as Promise<void>,
  fileThumb: (path: string) => ipcRenderer.invoke('files:thumb', path) as Promise<string | null>,
  pathExists: (path: string) => ipcRenderer.invoke('files:exists', path) as Promise<boolean>,
  pathSize: (path: string) => ipcRenderer.invoke('files:size', path) as Promise<number>,
  deleteFile: (path: string) => ipcRenderer.invoke('files:delete', path) as Promise<void>,
  saveFileAs: (source: string, name: string) =>
    ipcRenderer.invoke('files:saveAs', { source, name }) as Promise<boolean>,
  pickFiles: () => ipcRenderer.invoke('files:pick') as Promise<FileEntry[]>,
  pickImages: () => ipcRenderer.invoke('files:pickImages') as Promise<FileEntry[]>,
  pickApks: () => ipcRenderer.invoke('files:pickApks') as Promise<FileEntry[]>,
  pickFolders: () => ipcRenderer.invoke('files:pickFolders') as Promise<FileEntry[]>,
  pickMedia: () => ipcRenderer.invoke('files:pickMedia') as Promise<FileEntry[]>,
  pickFolder: () => ipcRenderer.invoke('files:pickFolder') as Promise<FileEntry | null>,
  inspectPaths: (paths: string[]) => ipcRenderer.invoke('files:inspect', paths) as Promise<FileEntry[]>,
  listDir: (dir: string) => ipcRenderer.invoke('files:listDir', dir) as Promise<FileEntry[]>,
  renamePath: (path: string, name: string) =>
    ipcRenderer.invoke('files:rename', path, name) as Promise<string>,
  trashPath: (path: string) => ipcRenderer.invoke('files:trash', path) as Promise<void>,
  onFilesChanged: (cb) => {
    const handler = (): void => cb()
    ipcRenderer.on('files:changed', handler)
    return () => ipcRenderer.removeListener('files:changed', handler)
  },
  pathForFile: (file: File) => webUtils.getPathForFile(file),
  onBucketAdd: (cb) => {
    const handler = (_event: Electron.IpcRendererEvent, entries: FileEntry[]): void => cb(entries)
    ipcRenderer.on('bucket:add', handler)
    return () => ipcRenderer.removeListener('bucket:add', handler)
  },
  padDrag: (active: boolean) => ipcRenderer.send('pad:drag', active),
  padReceive: (req) => ipcRenderer.invoke('pad:receive', req) as Promise<{ count: number }>,
  sendFiles: (req: SendFilesReq) => ipcRenderer.invoke('files:send', req) as Promise<string>,
  captureScreenshot: () =>
    ipcRenderer.invoke('screenshot:capture') as Promise<FileEntry | null>,
  cancelSend: (messageId: string) => ipcRenderer.invoke('files:cancel', messageId) as Promise<void>,
  retryFile: (req: RetryFileReq) => ipcRenderer.invoke('files:retry', req) as Promise<void>,
  onNavigate: (cb) => {
    const handler = (_event: Electron.IpcRendererEvent, convId: string): void => cb(convId)
    ipcRenderer.on('navigate', handler)
    return () => ipcRenderer.removeListener('navigate', handler)
  },
  onUiAction: (cb) => {
    const handler = (_event: Electron.IpcRendererEvent, action: string): void => cb(action)
    ipcRenderer.on('ui:action', handler)
    return () => ipcRenderer.removeListener('ui:action', handler)
  },
  onLanChange: (cb) => {
    const handler = (_event: Electron.IpcRendererEvent, info: LanInfo | null): void => cb(info)
    ipcRenderer.on('lan', handler)
    return () => ipcRenderer.removeListener('lan', handler)
  },
  updateSettings: (patch: Partial<Settings>) =>
    ipcRenderer.invoke('settings:update', patch) as Promise<void>,
  setDeviceName: (name: string) => ipcRenderer.invoke('config:setName', name) as Promise<void>,
  pickRecvDir: () => ipcRenderer.invoke('config:pickRecvDir') as Promise<string | null>,
  resetRecvDir: () => ipcRenderer.invoke('config:resetRecvDir') as Promise<string>,
  setAutostart: (enabled: boolean) =>
    ipcRenderer.invoke('config:autostart', enabled) as Promise<void>,
  setTrust: (id: string, trusted: boolean) =>
    ipcRenderer.invoke('trust:set', id, trusted) as Promise<void>,
  speedTest: (peerId: string) =>
    ipcRenderer.invoke('speed:test', peerId) as Promise<SpeedTestResult>,
  onSpeedProgress: (cb) => {
    const handler = (_event: Electron.IpcRendererEvent, progress: SpeedTestProgress): void =>
      cb(progress)
    ipcRenderer.on('speed:progress', handler)
    return () => ipcRenderer.removeListener('speed:progress', handler)
  },
  shareStart: (entries: FileEntry[]) =>
    ipcRenderer.invoke('share:start', entries) as Promise<ShareStatus>,
  shareStop: () => ipcRenderer.invoke('share:stop') as Promise<ShareStatus>,
  shareStatus: () => ipcRenderer.invoke('share:status') as Promise<ShareStatus>,
  restartService: () => ipcRenderer.invoke('service:restart') as Promise<void>,
  cacheSize: () => ipcRenderer.invoke('cache:size') as Promise<{ total: number }>,
  cleanCache: (preservePaths?: string[]) =>
    ipcRenderer.invoke('cache:clean', preservePaths) as Promise<{
      parts: number
      freedBytes: number
      records: number
    }>,
  runDoctor: () => ipcRenderer.invoke('doctor') as Promise<DoctorResult>,
  lanInfo: () => ipcRenderer.invoke('app:lanInfo') as Promise<LanInfo | null>,
  onHotspotChange: (cb) => {
    const handler = (_event: Electron.IpcRendererEvent, status: HotspotStatus): void => cb(status)
    ipcRenderer.on('hotspot', handler)
    return () => ipcRenderer.removeListener('hotspot', handler)
  },
  hotspotStatus: () => ipcRenderer.invoke('hotspot:status') as Promise<HotspotStatus>,
  hotspotStart: () => ipcRenderer.invoke('hotspot:start') as Promise<HotspotStatus>,
  hotspotStop: () => ipcRenderer.invoke('hotspot:stop') as Promise<HotspotStatus>,
  openHotspotSettings: () => ipcRenderer.invoke('hotspot:settings') as Promise<void>,
  bleStart: () => ipcRenderer.invoke('ble:start') as Promise<BleState>,
  bleStop: () => ipcRenderer.invoke('ble:stop') as Promise<BleState>,
  bleConnect: (peerId: string) => ipcRenderer.invoke('ble:connect', peerId) as Promise<void>,
  webChatStatus: () => ipcRenderer.invoke('webchat:status') as Promise<WebChatStatus>,
  webChatStart: () => ipcRenderer.invoke('webchat:start') as Promise<WebChatStatus>,
  webChatStop: () => ipcRenderer.invoke('webchat:stop') as Promise<WebChatStatus>,
  relayCreate: (name: string) => ipcRenderer.invoke('relay:create', name) as Promise<string>,
  relayJoin: (code: string) => ipcRenderer.invoke('relay:join', code) as Promise<string>,
  relayLeave: (roomId: string) => ipcRenderer.invoke('relay:leave', roomId) as Promise<void>,
  relaySend: (roomId: string, body: string) =>
    ipcRenderer.invoke('relay:send', roomId, body) as Promise<void>,
  relaySendFiles: (roomId: string, paths: string[]) =>
    ipcRenderer.invoke('relay:sendFiles', roomId, paths) as Promise<void>,
  relayRecall: (roomId: string, messageId: string) =>
    ipcRenderer.invoke('relay:recall', roomId, messageId) as Promise<void>,
  relayKick: (roomId: string, deviceId: string) =>
    ipcRenderer.invoke('relay:kick', roomId, deviceId) as Promise<void>,
  relayRename: (roomId: string, name: string) =>
    ipcRenderer.invoke('relay:rename', roomId, name) as Promise<void>,
  relayMembers: (roomId: string) =>
    ipcRenderer.invoke('relay:members', roomId) as Promise<void>,
  relayTest: (address: string) =>
    ipcRenderer.invoke('relay:test', address) as Promise<{
      ok: boolean
      name?: string
      error?: string
    }>,
  relayDownload: (messageId: string) =>
    ipcRenderer.invoke('relay:download', messageId) as Promise<string>,
  relayCache: (messageId: string) => ipcRenderer.invoke('relay:cache', messageId) as Promise<string>,
  syncPublic: () => ipcRenderer.invoke('public:sync') as Promise<void>,
  minimize: () => ipcRenderer.send('win:minimize'),
  toggleMaximize: () => ipcRenderer.send('win:toggleMaximize'),
  close: () => ipcRenderer.send('win:close'),
  version: () => ipcRenderer.invoke('app:version'),
  openExternal: (url: string) => ipcRenderer.invoke('app:openExternal', url) as Promise<void>
}

contextBridge.exposeInMainWorld('xt', api)
