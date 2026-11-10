import { useEffect, useMemo, useRef, useState, type DragEvent } from 'react'
import {
  Alert,
  Box,
  Button,
  CssBaseline,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Snackbar,
  ThemeProvider,
  Typography,
  useMediaQuery
} from '@mui/material'
import AppsRounded from '@mui/icons-material/AppsRounded'
import type {
  Conversation,
  FileEntry,
  HotspotStatus,
  LanInfo,
  Message,
  Snapshot
} from '../../shared/types'
import { PUBLIC_CONVERSATION_ID } from '../../shared/types'
import AppNav, { type AppPage } from './components/AppNav'
import BucketDrawer from './components/BucketDrawer'
import ChatPane from './components/ChatPane'
import FileManager from './components/FileManager'
import HotspotDialog from './components/HotspotDialog'
import BluetoothDialog from './components/BluetoothDialog'
import ConnectionsDialog from './components/ConnectionsDialog'
import SettingsDialog from './components/SettingsDialog'
import SharePage from './components/SharePage'
import Sidebar from './components/Sidebar'
import TitleBar from './components/TitleBar'
import { describeError } from './model'
import { buildTheme } from './theme'

const PREVIEW_EXTS = new Set([
  'txt', 'md', 'markdown', 'log', 'json', 'csv', 'tsv', 'xml', 'yaml', 'yml', 'ini', 'conf',
  'toml', 'js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx', 'html', 'htm', 'css', 'scss', 'less',
  'py', 'java', 'kt', 'kts', 'go', 'c', 'cpp', 'cc', 'h', 'hpp', 'cs', 'rs', 'rb', 'php',
  'swift', 'lua', 'sh', 'bash', 'zsh', 'bat', 'cmd', 'ps1', 'sql', 'gradle', 'properties',
  'env', 'mp3', 'wav', 'm4a', 'aac', 'flac', 'ogg', 'oga', 'opus', 'wma',
  'mp4', 'm4v', 'mkv', 'webm', 'mov', 'avi', 'wmv', 'flv', 'ts', 'mpg', 'mpeg', '3gp',
  'jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'ico', 'heic', 'heif'
])

function Shell() {
  const prefersDark = useMediaQuery('(prefers-color-scheme: dark)')
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null)
  const [selectedId, setSelectedId] = useState(PUBLIC_CONVERSATION_ID)
  const [scanning, setScanning] = useState(false)
  const [bucket, setBucket] = useState<FileEntry[]>([])
  const [page, setPage] = useState<AppPage>('chat')
  const [bucketOpen, setBucketOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [dropNotice, setDropNotice] = useState('')
  const [dropNoticeVisible, setDropNoticeVisible] = useState(false)
  const [lan, setLan] = useState<LanInfo | null>(null)
  const [lanReady, setLanReady] = useState(false)
  const [hotspot, setHotspot] = useState<HotspotStatus | null>(null)
  const [hotspotBusy, setHotspotBusy] = useState(false)
  const [hotspotOpen, setHotspotOpen] = useState(false)
  const [bluetoothOpen, setBluetoothOpen] = useState(false)
  const [bluetoothBusy, setBluetoothBusy] = useState(false)
  const [connectionsOpen, setConnectionsOpen] = useState(false)
  // 用过任意一种连接方式后本会话不再提示，重启应用后重新出现。
  const [solutionUsed, setSolutionUsed] = useState(false)
  const hotspotBusyRef = useRef(false)
  const dropTimer = useRef<number | undefined>(undefined)

  const refreshHotspot = async (): Promise<HotspotStatus | null> => {
    try {
      const status = await window.xt.hotspotStatus()
      setHotspot(status)
      return status
    } catch {
      return null
    }
  }

  useEffect(() => {
    let alive = true
    window.xt
      .lanInfo()
      .then((info) => {
        if (!alive) return
        setLan((prev) => (prev?.name === info?.name && prev?.cidr === info?.cidr ? prev : info))
        setLanReady(true)
      })
      .catch(() => {
        if (alive) setLanReady(true)
      })
    const off = window.xt.onLanChange((info) => {
      setLan(info)
      setLanReady(true)
      // 换网络后热点可能被系统关掉或换了 IP，重新确认一次。
      void refreshHotspot()
    })
    return () => {
      alive = false
      off()
    }
  }, [])

  useEffect(() => {
    void refreshHotspot()
  }, [])

  useEffect(() => window.xt.onHotspotChange(setHotspot), [])

  useEffect(() => {
    if (!hotspotOpen) return
    let alive = true
    const tick = (): void => {
      // 开关热点期间不查，避免查询排在长操作后面越积越多。
      if (hotspotBusyRef.current) return
      window.xt
        .hotspotStatus()
        .then((status) => {
          if (alive) setHotspot(status)
        })
        .catch(() => {})
    }
    tick()
    const timer = window.setInterval(tick, 3000)
    return () => {
      alive = false
      window.clearInterval(timer)
    }
  }, [hotspotOpen])

  const startHotspot = async (): Promise<void> => {
    hotspotBusyRef.current = true
    setHotspotBusy(true)
    try {
      setHotspot(await window.xt.hotspotStart())
    } catch (error) {
      notify(error instanceof Error ? error.message : '开启热点失败', 'error')
    } finally {
      hotspotBusyRef.current = false
      setHotspotBusy(false)
    }
  }

  const stopHotspot = async (): Promise<void> => {
    hotspotBusyRef.current = true
    setHotspotBusy(true)
    try {
      setHotspot(await window.xt.hotspotStop())
    } catch (error) {
      notify(error instanceof Error ? error.message : '关闭热点失败', 'error')
    } finally {
      hotspotBusyRef.current = false
      setHotspotBusy(false)
    }
  }

  const openHotspot = async (): Promise<void> => {
    setHotspotOpen(true)
    // 只打开弹窗并刷新真实状态，开不开由弹窗里的按钮决定。
    await refreshHotspot()
  }

  const [notice, setNotice] = useState<{
    text: string
    severity: 'success' | 'error' | 'warning' | 'info'
  } | null>(null)
  const [errorText, setErrorText] = useState<string | null>(null)
  const pendingSends = useRef(new Map<string, number>())

  const notify = (text: string, severity: 'success' | 'error' | 'warning' | 'info' = 'success'): void => {
    // 错误用弹窗而不是提示条：要能停下来慢慢看、把原文复制出去。
    if (severity === 'error') {
      setErrorText(describeError(text))
      return
    }
    setNotice({ text, severity })
  }
  // 退出动画期间内容不能清空，否则 Alert 会缩成一个小方块闪一下。
  const lastNotice = useRef(notice)
  if (notice) lastNotice.current = notice
  const shownNotice = notice ?? lastNotice.current

  const themeSetting = snapshot?.settings.theme ?? 'system'
  const seed = snapshot?.settings.seed ?? 'purple'
  const misans = snapshot?.settings.misans ?? true
  const mode = themeSetting === 'system' ? (prefersDark ? 'dark' : 'light') : themeSetting
  const theme = useMemo(() => buildTheme(mode, seed, misans), [mode, seed, misans])

  useEffect(() => {
    let alive = true
    window.xt
      .getState()
      .then((state) => {
        if (alive) setSnapshot(state)
      })
      .catch(() => {})
    const off = window.xt.onState((state) => setSnapshot(state))
    const offMessages = window.xt.onMessages((updates) => {
      setSnapshot((prev) => {
        if (!prev || updates.length === 0) return prev
        const changed = new Map(updates.map((message) => [message.id, message]))
        let touched = false
        const messages = prev.messages.map((message) => {
          const next = changed.get(message.id)
          if (!next) return message
          touched = true
          return next
        })
        return touched ? { ...prev, messages } : prev
      })
    })
    const offNavigate = window.xt.onNavigate((convId) => {
      if (convId) setSelectedId(convId)
    })
    const offUiAction = window.xt.onUiAction((action) => {
      if (action === 'bucket') setBucketOpen(true)
      else if (action === 'files') setPage('files')
      else if (action === 'share') setPage('lan')
      else if (action === 'settings') setSettingsOpen(true)
    })
    return () => {
      alive = false
      off()
      offMessages()
      offNavigate()
      offUiAction()
    }
  }, [])

  // 点到标题栏这类禁选区域时，浏览器不会自动取消已经选中的文字，这里统一清掉。
  useEffect(() => {
    const clearSelection = (event: MouseEvent): void => {
      if (event.button !== 0) return
      const target = event.target as HTMLElement | null
      if (target?.closest('input, textarea, [contenteditable="true"], [data-selectable="true"]')) return
      const selection = window.getSelection()
      if (selection && !selection.isCollapsed) selection.removeAllRanges()
    }
    document.addEventListener('mousedown', clearSelection)
    return () => document.removeEventListener('mousedown', clearSelection)
  }, [])

  const conversations = snapshot?.conversations ?? []
  const allMessages = snapshot?.messages ?? []
  const bleSelected = selectedId.startsWith('ble:')
  const bleDevice = bleSelected
    ? snapshot?.bluetooth.devices.find((device) => `ble:${device.id}` === selectedId)
    : undefined
  const synthesized: Conversation | undefined = bleSelected
    ? {
        id: selectedId,
        name: bleDevice?.name || selectedId.slice(4),
        platform: bleDevice?.platform,
        online: bleDevice?.connected === true,
        last_message_at: 0,
        last_read_at: 0,
        unread: 0,
        created_at: 0
      }
    : undefined
  const conv =
    conversations.find((item) => item.id === selectedId) ?? synthesized ?? conversations[0]
  const peer = snapshot?.peers.find((item) => item.id === conv?.id)

  const messages = useMemo(
    () => (conv ? allMessages.filter((message) => message.conversation_id === conv.id) : []),
    [allMessages, conv]
  )

  useEffect(() => {
    if (conv && conv.unread > 0) window.xt.markRead(conv.id).catch(() => {})
  }, [conv])

  // 进入公共频道时向记录最全的设备补齐一次历史。
  const publicActive = conv?.id === PUBLIC_CONVERSATION_ID
  useEffect(() => {
    if (!publicActive) return
    window.xt.syncPublic().catch(() => {})
  }, [publicActive])

  useEffect(() => {
    const pending = pendingSends.current
    if (pending.size === 0) return
    const done: string[] = []
    for (const message of allMessages) {
      if (message.direction !== 'out' || message.kind === 'text') continue
      if (!message.local_path || (message.status !== 'done' && message.status !== 'duplicate')) continue
      const startedAt = pending.get(message.local_path)
      if (startedAt === undefined || message.created_at < startedAt) continue
      done.push(message.local_path)
    }
    if (done.length === 0) return
    for (const filePath of done) pending.delete(filePath)
    setBucket((prev) => prev.filter((entry) => !done.includes(entry.path)))
  }, [allMessages])

  const failedPaths = useMemo(() => {
    const latest = new Map<string, Message>()
    for (const message of allMessages) {
      if (message.direction !== 'out' || message.kind === 'text' || !message.local_path) continue
      const prev = latest.get(message.local_path)
      if (!prev || message.created_at >= prev.created_at) latest.set(message.local_path, message)
    }
    const failed = new Set<string>()
    for (const [filePath, message] of latest) {
      if (message.status === 'failed') failed.add(filePath)
    }
    return failed
  }, [allMessages])

  const serviceRunning = snapshot?.service.running ?? false
  const serviceStarting = snapshot?.service.starting ?? false
  const serviceWarning = snapshot?.service.warning ?? ''
  const [serviceChip, setServiceChip] = useState<{
    text: string
    tone: 'error' | 'warning' | 'success'
  } | null>(null)
  const [serviceChipVisible, setServiceChipVisible] = useState(false)
  const serviceChipTimer = useRef<number | undefined>(undefined)

  useEffect(() => {
    if (!snapshot) return
    window.clearTimeout(serviceChipTimer.current)
    if (serviceStarting) {
      setServiceChip({ text: 'xlan 服务启动中', tone: 'warning' })
      setServiceChipVisible(true)
      return
    }
    if (!serviceRunning) {
      setServiceChip({ text: serviceWarning || 'xlan 服务未启动', tone: 'error' })
      setServiceChipVisible(true)
      return
    }
    // 启动成功后提示两秒再收起，之后不再常驻打扰。
    setServiceChip({ text: 'xlan 服务已启动', tone: 'success' })
    setServiceChipVisible(true)
    serviceChipTimer.current = window.setTimeout(() => setServiceChipVisible(false), 2000)
    return () => window.clearTimeout(serviceChipTimer.current)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [snapshot !== null, serviceStarting, serviceRunning, serviceWarning])

  const addEntries = (entries: FileEntry[]): void => {
    if (entries.length === 0) return
    setBucket((prev) => {
      const map = new Map(prev.map((entry) => [entry.path, entry]))
      for (const entry of entries) map.set(entry.path, entry)
      return [...map.values()]
    })
  }

  const showDropNotice = (text: string): void => {
    setDropNotice(text)
    setDropNoticeVisible(true)
    if (dropTimer.current !== undefined) window.clearTimeout(dropTimer.current)
    dropTimer.current = window.setTimeout(() => setDropNoticeVisible(false), 2000)
  }

  useEffect(
    () => () => {
      if (dropTimer.current !== undefined) window.clearTimeout(dropTimer.current)
    },
    []
  )

  useEffect(
    () =>
      window.xt.onBucketAdd((entries) => {
        addEntries(entries)
        if (entries.length === 0) return
        const files = entries.filter((entry) => entry.kind === 'file').length
        const folders = entries.length - files
        const parts: string[] = []
        if (files > 0) parts.push(`${files}个文件`)
        if (folders > 0) parts.push(`${folders}个文件夹`)
        showDropNotice(`${parts.join('，')} 进入暂存桶`)
      }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    []
  )

  const handlePickFiles = async (): Promise<void> => {
    try {
      addEntries(await window.xt.pickFiles())
    } catch (error) {
      notify(error instanceof Error ? error.message : '选择文件失败', 'error')
    }
  }

  const handlePickImages = async (): Promise<void> => {
    try {
      addEntries(await window.xt.pickImages())
    } catch (error) {
      notify(error instanceof Error ? error.message : '选择图片失败', 'error')
    }
  }

  const handlePickApks = async (): Promise<void> => {
    try {
      addEntries(await window.xt.pickApks())
    } catch (error) {
      notify(error instanceof Error ? error.message : '选择安装包失败', 'error')
    }
  }

  const handlePickFolder = async (): Promise<void> => {
    try {
      const entry = await window.xt.pickFolder()
      if (entry) addEntries([entry])
    } catch (error) {
      notify(error instanceof Error ? error.message : '选择文件夹失败', 'error')
    }
  }

  const handleDrop = async (event: DragEvent<HTMLDivElement>): Promise<void> => {
    event.preventDefault()
    const paths = Array.from(event.dataTransfer.files)
      .map((file) => window.xt.pathForFile(file))
      .filter(Boolean)
    if (paths.length === 0) return
    try {
      const entries = await window.xt.inspectPaths(paths)
      if (conv && conv.id.startsWith('ble:')) {
        const files = entries.filter((entry) => entry.kind === 'file')
        if (files.length < entries.length) notify('蓝牙不支持发送文件夹', 'warning')
        if (files.length > 0) await sendEntries(files, false)
        return
      }
      if (conv && conv.id.startsWith('relay:')) {
        const files = entries.filter((entry) => entry.kind === 'file')
        if (files.length < entries.length) notify('公网聊天室只支持发送单个文件', 'warning')
        if (files.length > 0) await sendEntries(files, false)
        return
      }
      addEntries(entries)
      if (entries.length > 0) {
        const files = entries.filter((entry) => entry.kind === 'file').length
        const folders = entries.length - files
        const parts: string[] = []
        if (files > 0) parts.push(`${files}个文件`)
        if (folders > 0) parts.push(`${folders}个文件夹`)
        showDropNotice(`${parts.join('，')} 进入暂存桶`)
      }
    } catch (error) {
      notify(error instanceof Error ? error.message : '读取拖入项目失败', 'error')
    }
  }

  const sendEntries = async (entries: FileEntry[], clearBucket: boolean): Promise<void> => {
    if (!conv || conv.id === PUBLIC_CONVERSATION_ID) return
    const usable = entries.filter((entry) => entry.exists)
    if (usable.length === 0) {
      notify('没有可发送的项目', 'warning')
      return
    }
    if (usable.length < entries.length) {
      notify(`已跳过 ${entries.length - usable.length} 个不可用项目`, 'warning')
    }
    if (conv.id.startsWith('relay:')) {
      const files = usable.filter((entry) => entry.kind === 'file')
      if (files.length === 0) {
        notify('公网聊天室只支持发送单个文件', 'warning')
        return
      }
      try {
        await window.xt.relaySendFiles(
          conv.id.slice('relay:'.length),
          files.map((entry) => entry.path)
        )
        if (clearBucket) setBucket([])
      } catch (error) {
        notify(error instanceof Error ? error.message : '发送失败', 'error')
      }
      return
    }
    const startedAt = Math.floor(Date.now() / 1000)
    for (const entry of usable) pendingSends.current.set(entry.path, startedAt)
    try {
      await window.xt.sendFiles({ to: conv.id, paths: usable.map((entry) => entry.path) })
      // 文件已经交给发送队列，暂存桶随即清空。
      if (clearBucket) setBucket([])
    } catch (error) {
      for (const entry of usable) pendingSends.current.delete(entry.path)
      notify(error instanceof Error ? error.message : '发送失败', 'error')
    }
  }

  const handleSendBucket = async (): Promise<void> => {
    await sendEntries(bucket, true)
  }

  const handlePickAndSend = async (kind: 'media' | 'files' | 'folders'): Promise<void> => {
    try {
      const entries =
        kind === 'media'
          ? await window.xt.pickMedia()
          : kind === 'folders'
            ? await window.xt.pickFolders()
            : await window.xt.pickFiles()
      if (entries.length === 0) return
      await sendEntries(entries, false)
    } catch (error) {
      notify(error instanceof Error ? error.message : '选择文件失败', 'error')
    }
  }

  const handleRetry = (id: string): void => {
    if (!conv) return
    window.xt
      .retryFile({ to: conv.id, id })
      .catch((error) => notify(error instanceof Error ? error.message : '重试失败', 'error'))
  }

  const handleRetryText = (id: string): void => {
    window.xt
      .retryText(id)
      .catch((error) => notify(error instanceof Error ? error.message : '重试失败', 'error'))
  }

  const openLocal = (filePath: string, name: string, kind: string, strict = false): void => {
    const lower = name.toLowerCase()
    const ext = lower.includes('.') ? lower.slice(lower.lastIndexOf('.') + 1) : ''
    const quick = snapshot?.settings.quickPreview ?? true
    if (kind !== 'folder' && quick) {
      if (PREVIEW_EXTS.has(ext)) {
        void window.xt
          .previewFile(filePath)
          .catch((error) => notify(error instanceof Error ? error.message : '打开失败', 'error'))
        return
      }
      if (strict) {
        notify('不支持快速预览', 'warning')
        return
      }
    }
    void window.xt
      .openPath(filePath)
      .catch((error) => notify(error instanceof Error ? error.message : '打开失败', 'error'))
  }

  const handleOpenFile = (message: Message): void => {
    if (message.channel === 'relay' && !message.local_path) {
      window.xt
        .relayCache(message.id)
        .then((filePath) => openLocal(filePath, message.file_name || filePath, message.kind, true))
        .catch((error) => notify(error instanceof Error ? error.message : '查看失败', 'error'))
      return
    }
    const filePath = message.local_path
    if (!filePath) return
    openLocal(filePath, message.file_name || filePath, message.kind, true)
  }

  const handleOpenBucketEntry = (entry: FileEntry): void => {
    if (!entry.exists) {
      notify('文件不存在', 'warning')
      return
    }
    openLocal(entry.path, entry.name, entry.kind)
  }

  const handleScreenshot = async (): Promise<void> => {
    if (!conv || conv.id === PUBLIC_CONVERSATION_ID) return
    try {
      const entry = await window.xt.captureScreenshot()
      if (!entry) return
      addEntries([entry])
      notify('已加入暂存桶')
    } catch (error) {
      notify(error instanceof Error ? error.message : '截图失败', 'error')
    }
  }

  const handleCancelSend = (id: string): void => {
    window.xt
      .cancelSend(id)
      .catch((error) => notify(error instanceof Error ? error.message : '取消失败', 'error'))
  }

  const handleDeleteMessage = (id: string): void => {
    window.xt
      .deleteMessage(id)
      .then(() => notify('已删除'))
      .catch((error) => notify(error instanceof Error ? error.message : '删除失败', 'error'))
  }

  const handleScan = async (): Promise<void> => {
    if (scanning) return
    setScanning(true)
    try {
      const state = await window.xt.scan()
      setSnapshot(state)
      notify('刷新成功')
    } catch (error) {
      notify(error instanceof Error ? error.message : '扫描失败', 'error')
    } finally {
      setScanning(false)
    }
  }

  const handleSend = async (body: string): Promise<void> => {
    if (!conv) throw new Error('未选择会话')
    if (conv.id.startsWith('relay:')) {
      await window.xt.relaySend(conv.id.slice('relay:'.length), body)
      return
    }
    await window.xt.sendText({
      to: conv.id === PUBLIC_CONVERSATION_ID ? '' : conv.id,
      body
    })
  }

  const bleError = snapshot?.bluetooth.error ?? ''
  const shownBleError = useRef('')
  useEffect(() => {
    if (!bleError || bleError === shownBleError.current || snapshot?.bluetooth.on) return
    shownBleError.current = bleError
    notify(bleError, 'error')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bleError, snapshot?.bluetooth.on])

  const handleBleToggle = (on: boolean): void => {
    setBluetoothBusy(true)
    const pending = on ? window.xt.bleStart() : window.xt.bleStop()
    pending
      .catch((error) => notify(error instanceof Error ? error.message : '蓝牙操作失败', 'error'))
      .finally(() => setBluetoothBusy(false))
  }

  const handleDecide = (id: string, action: 'accept' | 'reject' | 'cancel'): void => {
    window.xt
      .decide({ id, action })
      .catch((error) => notify(error instanceof Error ? error.message : '操作失败', 'error'))
  }

  const hotspotActive = hotspot?.running === true
  const bluetoothActive = snapshot?.bluetooth.on === true
  useEffect(() => {
    if (hotspotActive || bluetoothActive) setSolutionUsed(true)
  }, [hotspotActive, bluetoothActive])

  const offline =
    lanReady && lan === null && !solutionUsed && !hotspotActive && !bluetoothActive

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <Box
        sx={{
          position: 'relative',
          height: '100vh',
          overflow: 'hidden',
          display: 'flex',
          flexDirection: 'column'
        }}
        onDragOver={(event) => event.preventDefault()}
        onDrop={(event) => void handleDrop(event)}
      >
        <TitleBar
          lan={lan}
          bucketCount={bucket.length}
          notice={dropNotice}
          noticeVisible={dropNoticeVisible}
          hotspotVisible={hotspot?.supported === true && (lan !== null || hotspot.running)}
          hotspotRunning={hotspot?.running === true}
          bluetoothSupported={snapshot?.bluetooth.supported === true}
          bluetoothOn={snapshot?.bluetooth.on === true}
          onOpenHotspot={openHotspot}
          onOpenBluetooth={() => setBluetoothOpen(true)}
          onOpenBucket={() => setBucketOpen(true)}
          onOpenSettings={() => setSettingsOpen(true)}
        />
        {serviceChip && (
          <Box
            sx={{
              position: 'absolute',
              top: 24,
              left: '50%',
              zIndex: 30,
              pointerEvents: 'none',
              transform: serviceChipVisible
                ? 'translate(-50%, -50%) scale(1)'
                : 'translate(-50%, -50%) scale(0.82)',
              opacity: serviceChipVisible ? 1 : 0,
              transition:
                'transform 280ms cubic-bezier(0.4, 0, 0.2, 1), opacity 200ms ease'
            }}
          >
            <Box
              sx={{
                px: 2.5,
                py: 0.9,
                borderRadius: '999px',
                boxShadow: 8,
                bgcolor: `${serviceChip.tone}.main`,
                color: `${serviceChip.tone}.contrastText`,
                fontSize: 13,
                fontWeight: 500,
                whiteSpace: 'nowrap'
              }}
            >
              {serviceChip.text}
            </Box>
          </Box>
        )}
        <Box
          sx={{
            flex: 1,
            minHeight: 0,
            display: 'flex',
            flexDirection: 'column'
          }}
        >
      <Box sx={{ flex: 1, display: 'flex', minHeight: 0 }}>
        <AppNav value={page} onChange={setPage} />
        {page === 'chat' ? (
          <>
        <Sidebar
          conversations={conversations}
          messages={allMessages}
          peers={snapshot?.peers ?? []}
          selectedId={conv?.id ?? PUBLIC_CONVERSATION_ID}
          onSelect={setSelectedId}
          onScan={handleScan}
          scanning={scanning}
          serviceRunning={serviceRunning}
          settings={snapshot?.settings}
          bluetooth={snapshot?.bluetooth}
          relay={snapshot?.relay}
          notify={notify}
          lanOffline={offline}
          onOpenConnections={() => setConnectionsOpen(true)}
        />
        {conv ? (
          <ChatPane
            conv={conv}
            messages={messages}
            recvDir={snapshot?.config.recvDir ?? ''}
            enterToSend={snapshot?.settings.enterToSend ?? true}
            serviceRunning={serviceRunning}
            lanOnline={lan !== null}
            bluetooth={snapshot?.bluetooth}
            relay={snapshot?.relay}
            onLeaveRoom={() => {
              const roomId = conv.id.slice('relay:'.length)
              window.xt
                .relayLeave(roomId)
                .then(() => {
                  setSelectedId(PUBLIC_CONVERSATION_ID)
                  notify('已退出房间')
                })
                .catch((error) =>
                  notify(error instanceof Error ? error.message : '退出失败', 'error')
                )
            }}
            trusted={conv.id === PUBLIC_CONVERSATION_ID ? undefined : peer?.trusted}
            onSetTrust={(trusted) => {
              window.xt
                .setTrust(conv.id, trusted)
                .then(() => notify(trusted ? '已开启自动接受' : '已关闭自动接受'))
                .catch((error) =>
                  notify(error instanceof Error ? error.message : '操作失败', 'error')
                )
            }}
            publicNotify={snapshot?.settings.publicNotifications ?? false}
            onSetPublicNotify={(enabled) => {
              window.xt
                .updateSettings({ publicNotifications: enabled })
                .catch((error) =>
                  notify(error instanceof Error ? error.message : '操作失败', 'error')
                )
            }}
            alias={snapshot?.settings.aliases[conv.id] ?? ''}
            onSetAlias={(next) => {
              const aliases = { ...(snapshot?.settings.aliases ?? {}) }
              if (next) aliases[conv.id] = next
              else delete aliases[conv.id]
              window.xt
                .updateSettings({ aliases })
                .then(() => notify(next ? '已保存' : '已清除备注名'))
                .catch((error) =>
                  notify(error instanceof Error ? error.message : '操作失败', 'error')
                )
            }}
            muted={snapshot?.settings.muted?.includes(conv.id) ?? false}
            onSetMuted={(mutedOn) => {
              const list = snapshot?.settings.muted ?? []
              const next = mutedOn
                ? list.includes(conv.id)
                  ? list
                  : [...list, conv.id]
                : list.filter((id) => id !== conv.id)
              window.xt
                .updateSettings({ muted: next })
                .catch((error) =>
                  notify(error instanceof Error ? error.message : '操作失败', 'error')
                )
            }}
            onClearHistory={() => {
              window.xt
                .clearConversation(conv.id)
                .then(() => notify('已清空'))
                .catch((error) =>
                  notify(error instanceof Error ? error.message : '清空失败', 'error')
                )
            }}
            bucketEntries={bucket}
            onSendBucket={() => void handleSendBucket()}
            onOpenBucket={() => setBucketOpen(true)}
            onDecide={handleDecide}
            onRetry={handleRetry}
            onRetryText={handleRetryText}
            onCancelSend={handleCancelSend}
            onDelete={handleDeleteMessage}
            onOpenFile={handleOpenFile}
            onScreenshot={() => void handleScreenshot()}
            onPickAndSend={(kind) => void handlePickAndSend(kind)}
            onSend={handleSend}
            notify={notify}
          />
        ) : (
          <Box sx={{ flex: 1, display: 'grid', placeItems: 'center' }}>
            <Typography sx={{ color: 'text.secondary', fontSize: 13 }}>暂无会话</Typography>
          </Box>
        )}
          </>
        ) : page === 'files' ? (
          <FileManager
            recvDir={snapshot?.config.recvDir ?? ''}
            refreshKey={allMessages.length}
            onOpen={handleOpenBucketEntry}
            notify={notify}
          />
        ) : page === 'lan' ? (
          <SharePage
            bucket={bucket}
            onAddFiles={() => void handlePickFiles()}
            onAddImages={() => void handlePickImages()}
            onAddApks={() => void handlePickApks()}
            onAddFolder={() => void handlePickFolder()}
            onRemove={(path) => setBucket((prev) => prev.filter((entry) => entry.path !== path))}
            onOpen={handleOpenBucketEntry}
            notify={notify}
          />
        ) : (
          <Box
            sx={{
              flex: 1,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 1.5
            }}
          >
            <Box
              sx={{
                width: 104,
                height: 104,
                borderRadius: '50%',
                bgcolor: 'action.hover',
                display: 'grid',
                placeItems: 'center'
              }}
            >
              <AppsRounded sx={{ fontSize: 44, color: 'text.disabled' }} />
            </Box>
            <Typography sx={{ color: 'text.secondary', fontSize: 13 }}>暂无小程序</Typography>
          </Box>
        )}
      </Box>
      <BucketDrawer
        open={bucketOpen}
        entries={bucket}
        failedPaths={failedPaths}
        onClose={() => setBucketOpen(false)}
        onAddFiles={() => void handlePickFiles()}
        onAddFolder={() => void handlePickFolder()}
        onOpen={handleOpenBucketEntry}
        onRemove={(path) =>
          setBucket((prev) => prev.filter((entry) => entry.path !== path))
        }
        onClear={() => {
          setBucket([])
          notify('已清空')
        }}
      />
      <SettingsDialog
        open={settingsOpen}
        snapshot={snapshot}
        preservedPaths={bucket.map((entry) => entry.path)}
        onClose={() => setSettingsOpen(false)}
        notify={notify}
      />
      <BluetoothDialog
        open={bluetoothOpen}
        info={snapshot?.info ?? null}
        bluetooth={snapshot?.bluetooth}
        busy={bluetoothBusy}
        onClose={() => setBluetoothOpen(false)}
        onToggle={handleBleToggle}
      />
      <HotspotDialog
        open={hotspotOpen}
        status={hotspot}
        busy={hotspotBusy}
        onClose={() => setHotspotOpen(false)}
        onStart={() => void startHotspot()}
        onStop={() => void stopHotspot()}
        onOpenSettings={() => {
          window.xt.openHotspotSettings().catch(() => {})
        }}
      />
      <ConnectionsDialog
        open={connectionsOpen}
        hotspotSupported={hotspot?.supported === true}
        bluetoothSupported={snapshot?.bluetooth.supported === true}
        onOpenHotspot={() => {
          setConnectionsOpen(false)
          void openHotspot()
        }}
        onOpenBluetooth={() => {
          setConnectionsOpen(false)
          setBluetoothOpen(true)
        }}
        onClose={() => setConnectionsOpen(false)}
      />
      <Snackbar
        open={notice !== null}
        autoHideDuration={3000}
        onClose={() => setNotice(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert
          severity={shownNotice?.severity ?? 'info'}
          variant="filled"
          onClose={() => setNotice(null)}
          data-selectable="true"
          sx={{
            width: '100%',
            '& .MuiAlert-message': { userSelect: 'text', whiteSpace: 'pre-wrap', cursor: 'text' }
          }}
        >
          {shownNotice?.text}
        </Alert>
      </Snackbar>
      <Dialog
        open={errorText !== null}
        onClose={() => setErrorText(null)}
        slotProps={{
          paper: {
            sx: {
              width: 620,
              maxWidth: 'calc(100vw - 80px)',
              borderRadius: '16px',
              bgcolor: 'background.default'
            }
          }
        }}
      >
        <DialogTitle sx={{ fontSize: 18 }}>错误</DialogTitle>
        <DialogContent>
          <Typography
            data-selectable="true"
            sx={{
              fontSize: 13,
              color: 'text.secondary',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
              userSelect: 'text',
              cursor: 'text',
              maxHeight: '46vh',
              overflowY: 'auto'
            }}
          >
            {errorText}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setErrorText(null)}>关闭</Button>
        </DialogActions>
      </Dialog>
      </Box>
      </Box>
    </ThemeProvider>
  )
}

export default function App() {
  return <Shell />
}
