import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import {
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  IconButton,
  LinearProgress,
  Menu,
  MenuItem,
  Switch,
  TextField,
  Tooltip,
  Typography
} from '@mui/material'
import AndroidRounded from '@mui/icons-material/AndroidRounded'
import ArticleRounded from '@mui/icons-material/ArticleRounded'
import BluetoothRounded from '@mui/icons-material/BluetoothRounded'
import WifiRounded from '@mui/icons-material/WifiRounded'
import AudiotrackRounded from '@mui/icons-material/AudiotrackRounded'
import CancelRounded from '@mui/icons-material/CancelRounded'
import CheckCircleRounded from '@mui/icons-material/CheckCircleRounded'
import DoneAllRounded from '@mui/icons-material/DoneAllRounded'
import FolderRounded from '@mui/icons-material/FolderRounded'
import FolderZipRounded from '@mui/icons-material/FolderZipRounded'
import ImageRounded from '@mui/icons-material/ImageRounded'
import InsertDriveFileRounded from '@mui/icons-material/InsertDriveFileRounded'
import MovieRounded from '@mui/icons-material/MovieRounded'
import PhotoLibraryRounded from '@mui/icons-material/PhotoLibraryRounded'
import PictureAsPdfRounded from '@mui/icons-material/PictureAsPdfRounded'
import LanguageRounded from '@mui/icons-material/LanguageRounded'
import MoreVertRounded from '@mui/icons-material/MoreVertRounded'
import ReplayRounded from '@mui/icons-material/ReplayRounded'
import ScreenshotRounded from '@mui/icons-material/ScreenshotRounded'
import SendRounded from '@mui/icons-material/SendRounded'
import StopCircleRounded from '@mui/icons-material/StopCircleRounded'
import type {
  BleState,
  Conversation,
  FileEntry,
  Message,
  WebChatStatus
} from '../../../shared/types'
import { PUBLIC_CONVERSATION_ID } from '../../../shared/types'
import { describeError, formatChatTime, formatSize, formatTime } from '../model'
import { useRetained } from './useRetained'
import { useRightClickDismiss } from './useRightClickDismiss'

const TIME_GAP_SECONDS = 5 * 60
const BUCKET_TYPE_ORDER = ['图片', '视频', '音频', '文档', 'PDF', '压缩包', '安装包', '文件', '文件夹']

function isSendingMessage(message: Message): boolean {
  return (
    message.direction === 'out' &&
    (message.status === 'queued' ||
      message.status === 'sending' ||
      message.status === 'active' ||
      message.status === 'accepted')
  )
}

type Props = {
  conv: Conversation
  messages: Message[]
  recvDir: string
  enterToSend: boolean
  serviceRunning: boolean
  lanOnline: boolean
  bluetooth?: BleState
  trusted?: boolean
  onSetTrust: (trusted: boolean) => void
  publicNotify?: boolean
  onSetPublicNotify: (enabled: boolean) => void
  alias?: string
  onSetAlias: (alias: string) => void
  clipboardOn?: boolean
  onSetClipboard: (enabled: boolean) => void
  muted?: boolean
  onSetMuted: (muted: boolean) => void
  onClearHistory: () => void
  bucketEntries: FileEntry[]
  onSendBucket: () => void
  onOpenBucket: () => void
  onDecide: (id: string, action: 'accept' | 'reject' | 'cancel') => void
  onRetry: (id: string) => void
  onRetryText: (id: string) => void
  onCancelSend: (id: string) => void
  onDelete: (id: string) => void
  onOpenFile: (message: Message) => void
  onScreenshot: () => void
  onPickAndSend: (kind: 'media' | 'files' | 'folders') => void
  onSend: (body: string) => Promise<void>
  notify: (text: string, severity?: 'success' | 'error' | 'warning' | 'info') => void
}

function statusLabel(status: Message['status']): string {
  switch (status) {
    case 'pending':
      return '待接收'
    case 'queued':
      return '正在队列中'
    case 'sending':
      return '等待确认'
    case 'accepted':
    case 'active':
      return '传输中'
    case 'failed':
      return '失败'
    case 'canceled':
      return '已取消'
    case 'peer_canceled':
      return '对方已取消'
    case 'rejected':
      return '已拒绝'
    case 'duplicate':
      return '已有相同文件'
    default:
      return '已完成'
  }
}

function StatusLine({
  message,
  mine,
  missing
}: {
  message: Message
  mine: boolean
  missing: boolean
}) {
  const secondary = {
    fontSize: 11.5,
    color: mine ? 'primary.contrastText' : 'text.secondary',
    opacity: mine ? 0.85 : 1
  }
  const kindLabel =
    message.kind === 'folder'
      ? '文件夹'
      : fileKindLabel(message.file_name || message.local_path || '')
  if (missing) {
    return <Typography sx={secondary}>{kindLabel} - 文件不存在</Typography>
  }
  if (message.status === 'pending') {
    const waited = Date.now() / 1000 - (message.created_at || 0)
    return (
      <Typography sx={mine ? secondary : { fontSize: 11.5, color: 'warning.main' }}>
        {kindLabel} - 待接收{waited > 60 ? ' · 可能已超时' : ''}
      </Typography>
    )
  }
  if (message.status === 'queued') {
    return <Typography sx={secondary}>{kindLabel} - 正在队列中</Typography>
  }
  if (message.status === 'sending') {
    return <Typography sx={secondary}>{kindLabel} - 等待确认</Typography>
  }
  if (message.status === 'active' || message.status === 'accepted') {
    const total = message.total || message.file_size || 0
    return (
      <Typography sx={secondary}>
        {kindLabel} -{' '}
        {total > 0 ? `${formatSize(message.received)} / ${formatSize(total)}` : '传输中'}
        {message.speed_bps > 0 ? ` · ${formatSize(message.speed_bps)}/s` : ''}
      </Typography>
    )
  }
  if (message.status === 'failed') {
    return (
      <Typography sx={mine ? secondary : { fontSize: 11.5, color: 'error.main' }}>
        {kindLabel} - 失败
      </Typography>
    )
  }
  if (message.status === 'canceled') {
    return <Typography sx={secondary}>{kindLabel} - 已取消</Typography>
  }
  if (message.status === 'peer_canceled') {
    return <Typography sx={secondary}>{kindLabel} - 对方已取消</Typography>
  }
  if (message.status === 'rejected') {
    return <Typography sx={secondary}>{kindLabel} - 已拒绝</Typography>
  }
  if (message.status === 'duplicate') {
    return <Typography sx={secondary}>{kindLabel} - 已有相同文件，未传输</Typography>
  }
  if (message.status === 'done') {
    return <Typography sx={secondary}>{kindLabel} - 传输已完成</Typography>
  }
  return null
}

const IMAGE_EXT = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'heic', 'heif', 'ico'])
const AUDIO_EXT = new Set(['mp3', 'wav', 'm4a', 'aac', 'flac', 'ogg', 'oga', 'opus', 'wma'])
const VIDEO_EXT = new Set(['mp4', 'm4v', 'mkv', 'webm', 'mov', 'avi', 'wmv', 'flv', 'ts', 'mpg', 'mpeg', '3gp'])
const ARCHIVE_EXT = new Set(['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz'])
const DOC_EXT = new Set(['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'md', 'rtf', 'csv'])

function extOf(name: string): string {
  const lower = name.toLowerCase()
  return lower.includes('.') ? lower.slice(lower.lastIndexOf('.') + 1) : ''
}

export function fileKindLabel(name: string): string {
  const ext = extOf(name)
  if (IMAGE_EXT.has(ext)) return '图片'
  if (AUDIO_EXT.has(ext)) return '音频'
  if (VIDEO_EXT.has(ext)) return '视频'
  if (ARCHIVE_EXT.has(ext)) return '压缩包'
  if (ext === 'pdf') return 'PDF'
  if (ext === 'apk') return '安装包'
  if (DOC_EXT.has(ext)) return '文档'
  return '文件'
}

export function typeIcon(name: string): React.ReactElement {
  const ext = extOf(name)
  if (IMAGE_EXT.has(ext)) return <ImageRounded fontSize="medium" />
  if (AUDIO_EXT.has(ext)) return <AudiotrackRounded fontSize="medium" />
  if (VIDEO_EXT.has(ext)) return <MovieRounded fontSize="medium" />
  if (ARCHIVE_EXT.has(ext)) return <FolderZipRounded fontSize="medium" />
  if (ext === 'pdf') return <PictureAsPdfRounded fontSize="medium" />
  if (ext === 'apk') return <AndroidRounded fontSize="medium" />
  if (DOC_EXT.has(ext)) return <ArticleRounded fontSize="medium" />
  return <InsertDriveFileRounded fontSize="medium" />
}

function useThumb(message: Message): string | null {
  const [thumb, setThumb] = useState<string | null>(null)
  const localPath = message.local_path
  const fileName = message.file_name ?? ''
  useEffect(() => {
    let alive = true
    setThumb(null)
    if (message.kind !== 'file' || !localPath) return
    const ext = extOf(fileName || localPath)
    if (!IMAGE_EXT.has(ext) && !VIDEO_EXT.has(ext)) return
    void window.xt.fileThumb(localPath).then((url) => {
      if (alive) setThumb(url)
    })
    return () => {
      alive = false
    }
  }, [message.kind, localPath, fileName])
  return thumb
}

function FileCard({
  message,
  mine,
  missing,
  onRetry,
  onCancelSend,
  onCancelReceive,
  onOpen,
  onContextMenu
}: {
  message: Message
  mine: boolean
  missing: boolean
  onRetry: (id: string) => void
  onCancelSend: (id: string) => void
  onCancelReceive: (id: string) => void
  onOpen: () => void
  onContextMenu: (event: React.MouseEvent) => void
}) {
  const active = message.status === 'active' || message.status === 'accepted'
  const total = message.total || message.file_size || 0
  const percent = active && total > 0 ? Math.min(100, Math.round((message.received / total) * 100)) : 0
  const dimmed =
    missing ||
    message.status === 'canceled' ||
    message.status === 'peer_canceled' ||
    message.status === 'rejected'
  const canCancel =
    !missing && (mine ? message.status === 'queued' || message.status === 'sending' || active : active)
  const openable = !missing && (message.status === 'done' || message.status === 'duplicate')
  const thumb = useThumb(message)
  return (
    <Box
      onClick={openable ? onOpen : undefined}
      onContextMenu={onContextMenu}
      sx={{
        minWidth: 250,
        maxWidth: 360,
        borderRadius: '18px',
        border: 1,
        borderColor: 'divider',
        bgcolor: mine ? 'primary.main' : 'background.paper',
        color: mine ? 'primary.contrastText' : 'text.primary',
        p: 1.25,
        opacity: dimmed ? 0.45 : 1,
        cursor: openable ? 'pointer' : undefined,
        transition: 'filter .15s ease',
        '&:hover': openable ? { filter: 'brightness(1.04)' } : undefined
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <Box
          sx={{
            width: 52,
            height: 52,
            borderRadius: '12px',
            overflow: 'hidden',
            display: 'grid',
            placeItems: 'center',
            bgcolor: mine ? 'rgba(255,255,255,0.18)' : 'action.hover',
            flexShrink: 0
          }}
        >
          {thumb ? (
            <Box
              component="img"
              src={thumb}
              alt=""
              sx={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
            />
          ) : message.kind === 'folder' ? (
            <FolderRounded fontSize="medium" />
          ) : (
            typeIcon(message.file_name || message.local_path || '')
          )}
        </Box>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography noWrap sx={{ fontSize: 13.5, fontWeight: 500 }}>
            {message.file_name || '未命名'}
          </Typography>
          <StatusLine message={message} mine={mine} missing={missing} />
        </Box>
        {canCancel && (
          <Tooltip title="取消">
            <IconButton
              size="small"
              onClick={() => (mine ? onCancelSend(message.id) : onCancelReceive(message.id))}
              sx={{ color: 'inherit' }}
            >
              <StopCircleRounded fontSize="small" />
            </IconButton>
          </Tooltip>
        )}
        {mine && !missing && message.status === 'failed' && message.local_path && (
          <Tooltip title="重试">
            <IconButton
              size="small"
              onClick={() => onRetry(message.id)}
              sx={{ color: 'inherit' }}
            >
              <ReplayRounded fontSize="small" />
            </IconButton>
          </Tooltip>
        )}
      </Box>
      {active && (
        <LinearProgress
          variant={percent > 0 ? 'determinate' : 'indeterminate'}
          value={percent}
          sx={{ mt: 1.25, borderRadius: 999, height: 4 }}
        />
      )}
    </Box>
  )
}

function useMissingFile(message: Message): boolean {
  const [missing, setMissing] = useState(false)
  const path = message.kind !== 'text' ? message.local_path : undefined
  useEffect(() => {
    let alive = true
    if (!path) {
      setMissing(false)
      return
    }
    void window.xt.pathExists(path).then((exists) => {
      if (alive) setMissing(!exists)
    })
    return () => {
      alive = false
    }
  }, [path])
  return missing
}

function MessageRow({
  message,
  showName,
  onDecide,
  onAcceptAll,
  onRetry,
  onRetryText,
  onCancelSend,
  onOpenFile,
  onContextMenu
}: {
  message: Message
  showName: boolean
  onDecide: (id: string, action: 'accept' | 'reject' | 'cancel') => void
  onAcceptAll?: () => void
  onRetry: (id: string) => void
  onRetryText: (id: string) => void
  onCancelSend: (id: string) => void
  onOpenFile: (message: Message) => void
  onContextMenu: (event: React.MouseEvent, missing: boolean) => void
}) {
  const mine = message.direction === 'out'
  const missing = useMissingFile(message)
  const pending = message.status === 'pending' && message.kind !== 'text' && !mine
  const spinning = mine && message.kind === 'text' && message.status === 'sending'
  const handleContext = (event: React.MouseEvent): void => {
    onContextMenu(event, missing)
  }
  return (
    <Box
      sx={{ display: 'flex', justifyContent: mine ? 'flex-end' : 'flex-start', mb: 1.5 }}
    >
      <Box
        sx={{
          maxWidth: '68%',
          display: 'flex',
          flexDirection: 'column',
          alignItems: mine ? 'flex-end' : 'flex-start',
          gap: 0.5
        }}
      >
        {showName && !mine && (
          <Typography sx={{ fontSize: 11.5, fontWeight: 600, color: 'text.secondary', px: 0.5 }}>
            {message.from_name || message.from_id}
          </Typography>
        )}
        <Box
          sx={{
            display: 'flex',
            alignItems: 'flex-end',
            gap: 1,
            flexDirection: mine ? 'row' : 'row-reverse'
          }}
        >
          {spinning && (
            <Box sx={{ pb: 0.25, flexShrink: 0 }}>
              <CircularProgress size={14} thickness={5} sx={{ color: 'text.secondary' }} />
            </Box>
          )}
          {message.kind === 'text' ? (
            <Box
              onContextMenu={handleContext}
              sx={{
                px: 1.75,
                py: 1,
                borderRadius: mine ? '20px 20px 6px 20px' : '20px 20px 20px 6px',
                bgcolor: mine ? 'primary.main' : 'background.paper',
                color: mine ? 'primary.contrastText' : 'text.primary',
                fontSize: 14,
                lineHeight: 1.55,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
                userSelect: 'text',
                cursor: 'text'
              }}
            >
              {message.body}
            </Box>
          ) : (
            <FileCard
              message={message}
              mine={mine}
              missing={missing}
              onRetry={onRetry}
              onCancelSend={onCancelSend}
              onCancelReceive={(id) => onDecide(id, 'cancel')}
              onOpen={() => onOpenFile(message)}
              onContextMenu={handleContext}
            />
          )}
        </Box>
        {pending && (
          <Box sx={{ display: 'flex', gap: 0.25, mt: 0.25 }}>
            <Tooltip title="接受">
              <IconButton
                size="small"
                color="primary"
                onClick={() => onDecide(message.id, 'accept')}
              >
                <CheckCircleRounded fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title="拒绝">
              <IconButton
                size="small"
                sx={{ color: 'text.secondary' }}
                onClick={() => onDecide(message.id, 'reject')}
              >
                <CancelRounded fontSize="small" />
              </IconButton>
            </Tooltip>
            {onAcceptAll && (
              <Tooltip title="全部接收">
                <IconButton size="small" color="primary" onClick={onAcceptAll}>
                  <DoneAllRounded fontSize="small" />
                </IconButton>
              </Tooltip>
            )}
          </Box>
        )}
        {mine && message.kind === 'text' && message.status === 'failed' && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, px: 0.5 }}>
            <Typography sx={{ fontSize: 11, color: 'error.main' }}>发送失败</Typography>
            <Button
              size="small"
              onClick={() => onRetryText(message.id)}
              sx={{ minWidth: 0, minHeight: 20, px: 0.75, py: 0, fontSize: 11 }}
            >
              重试
            </Button>
          </Box>
        )}
      </Box>
    </Box>
  )
}

function shortDir(full: string): string {
  const parts = full.split(/[\\/]+/).filter(Boolean)
  return parts.slice(-2).join('\\')
}

export default function ChatPane({
  conv,
  messages,
  recvDir,
  enterToSend,
  serviceRunning,
  lanOnline,
  bluetooth,
  trusted,
  onSetTrust,
  publicNotify,
  onSetPublicNotify,
  alias,
  onSetAlias,
  clipboardOn,
  onSetClipboard,
  muted,
  onSetMuted,
  onClearHistory,
  bucketEntries,
  onSendBucket,
  onOpenBucket,
  onDecide,
  onRetry,
  onRetryText,
  onCancelSend,
  onDelete,
  onOpenFile,
  onScreenshot,
  onPickAndSend,
  onSend,
  notify
}: Props) {
  const [body, setBody] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const [menu, setMenu] = useState<{
    x: number
    y: number
    message: Message
    missing: boolean
  } | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<Message | null>(null)
  const [confirmMissing, setConfirmMissing] = useState(false)
  const [infoTarget, setInfoTarget] = useState<Message | null>(null)
  const [folderSize, setFolderSize] = useState<number | null>(null)
  const [trustAnchor, setTrustAnchor] = useState<HTMLElement | null>(null)
  const [confirmClear, setConfirmClear] = useState(false)
  const [aliasOpen, setAliasOpen] = useState(false)
  const [aliasDraft, setAliasDraft] = useState('')
  const [searchOpen, setSearchOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [webOpen, setWebOpen] = useState(false)
  const [webBusy, setWebBusy] = useState(false)
  const [webStatus, setWebStatus] = useState<WebChatStatus | null>(null)
  const [webQr, setWebQr] = useState('')
  const listRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<HTMLTextAreaElement | null>(null)

  // 自己插入换行，不依赖浏览器对 Ctrl/Shift+Enter 的默认处理。
  const insertBreak = (): void => {
    const el = inputRef.current
    if (!el) {
      setBody((prev) => `${prev}\n`)
      return
    }
    const start = el.selectionStart ?? el.value.length
    const end = el.selectionEnd ?? start
    setBody(`${el.value.slice(0, start)}\n${el.value.slice(end)}`)
    requestAnimationFrame(() => {
      el.selectionStart = start + 1
      el.selectionEnd = start + 1
    })
  }
  // 只有停在底部附近才跟随新消息，用户往上翻历史时不要把他拽回来。
  const stickRef = useRef(true)
  const menuShown = useRetained(menu)
  const deleteShown = useRetained(confirmDelete)
  const infoShown = useRetained(infoTarget)
  const trustShown = useRetained(trustAnchor)
  const webShown = useRetained(webStatus)

  useEffect(() => {
    setBody('')
    setError('')
    setSearchOpen(false)
    setSearchQuery('')
    setAliasOpen(false)
  }, [conv.id])

  const pendingIncoming = useMemo(
    () =>
      messages
        .filter((item) => item.status === 'pending' && item.kind !== 'text' && item.direction === 'in')
        .map((item) => item.id),
    [messages]
  )
  // 只有最后一条待接收的文件带「全部接收」，前面的只留接受/拒绝。
  const lastPendingId = pendingIncoming[pendingIncoming.length - 1]

  const infoId = infoTarget?.id
  const infoKind = infoTarget?.kind
  const infoPath = infoTarget?.local_path
  useEffect(() => {
    setFolderSize(null)
    if (infoKind !== 'folder' || !infoPath) return
    let alive = true
    void window.xt.pathSize(infoPath).then((size) => {
      if (alive) setFolderSize(size)
    })
    return () => {
      alive = false
    }
  }, [infoId, infoKind, infoPath])

  const lastMessage = messages.length > 0 ? messages[messages.length - 1] : undefined
  const lastMessageId = lastMessage?.id ?? ''
  const lastMessageStatus = lastMessage?.status ?? ''

  // 切换会话一律回到底部。
  useEffect(() => {
    const node = listRef.current
    if (!node) return
    stickRef.current = true
    node.scrollTop = node.scrollHeight
    const raf = requestAnimationFrame(() => {
      node.scrollTop = node.scrollHeight
    })
    return () => cancelAnimationFrame(raf)
  }, [conv.id])

  useEffect(() => {
    const node = listRef.current
    if (!node || !stickRef.current) return
    node.scrollTop = node.scrollHeight
    // 文件缩略图/卡片渲染后再补一次，保证真正贴到底。
    const raf = requestAnimationFrame(() => {
      node.scrollTop = node.scrollHeight
    })
    return () => cancelAnimationFrame(raf)
  }, [messages.length, lastMessageId, lastMessageStatus])

  const handleListScroll = (): void => {
    const node = listRef.current
    if (!node) return
    stickRef.current = node.scrollHeight - node.scrollTop - node.clientHeight < 80
  }

  const isBle = conv.id.startsWith('ble:')
  const blePeerId = isBle ? conv.id.slice(4) : ''
  const bleDevice = isBle
    ? (bluetooth?.devices ?? []).find((device) => device.id === blePeerId)
    : undefined
  const bleConnected = isBle && bluetooth?.on === true && bleDevice?.connected === true
  const bleConnecting = isBle && bleDevice?.connecting === true
  const transportReady = isBle ? bleConnected : serviceRunning
  const online = isBle
    ? bleConnected
    : conv.id === PUBLIC_CONVERSATION_ID
      ? lanOnline
      : conv.online
  const canSend = transportReady && body.trim() !== '' && !sending

  const bleAttemptRef = useRef('')
  useEffect(() => {
    bleAttemptRef.current = ''
  }, [conv.id])

  const [reconnectBusy, setReconnectBusy] = useState(false)
  const [bleOffHint, setBleOffHint] = useState(false)
  const notifyRef = useRef(notify)
  notifyRef.current = notify
  useEffect(() => {
    if (bleConnected) setReconnectBusy(false)
  }, [bleConnected])
  // 点重连后最多等 10 秒；一直没连上就说明没扫到设备，告诉用户去查对方有没有开广播。
  useEffect(() => {
    if (!reconnectBusy) return
    const timer = window.setTimeout(() => {
      setReconnectBusy(false)
      notifyRef.current('未发现该设备，请确认对方已开启蓝牙广播', 'warning')
    }, 10_000)
    return () => window.clearTimeout(timer)
  }, [reconnectBusy])

  const startReconnect = (): void => {
    if (bluetooth?.on !== true) {
      setBleOffHint(true)
      return
    }
    setReconnectBusy(true)
    void window.xt.bleConnect(blePeerId)
  }

  const showReconnect = isBle && !bleConnected && !bleConnecting

  useEffect(() => {
    if (!isBle || bluetooth?.on !== true || !bleDevice || bleConnected || bleConnecting) return
    if (bleAttemptRef.current === blePeerId) return
    bleAttemptRef.current = blePeerId
    void window.xt.bleConnect(blePeerId)
  }, [isBle, blePeerId, bluetooth?.on, bleDevice, bleConnected, bleConnecting])

  const openMenu = (event: React.MouseEvent, message: Message, missing: boolean): void => {
    event.preventDefault()
    setMenu({ x: event.clientX, y: event.clientY, message, missing })
  }

  const closeMenu = (): void => setMenu(null)

  useRightClickDismiss(menu !== null, closeMenu)
  useRightClickDismiss(trustAnchor !== null, () => setTrustAnchor(null))

  const copyText = (message: Message): void => {
    closeMenu()
    if (!message.body) return
    navigator.clipboard
      .writeText(message.body)
      .then(() => notify('已复制'))
      .catch(() => notify('复制失败', 'error'))
  }

  const revealFile = (message: Message): void => {
    if (message.local_path) void window.xt.revealPath(message.local_path)
    closeMenu()
  }

  const openWithOther = (message: Message): void => {
    closeMenu()
    if (!message.local_path) return
    void window.xt
      .openPath(message.local_path)
      .catch((error) => notify(error instanceof Error ? error.message : '打开失败', 'error'))
  }

  const copyPath = (message: Message): void => {
    closeMenu()
    if (!message.local_path) return
    navigator.clipboard
      .writeText(message.local_path)
      .then(() => notify('已复制'))
      .catch(() => notify('复制失败', 'error'))
  }

  const saveAs = (message: Message): void => {
    const source = message.local_path
    closeMenu()
    if (!source) return
    void window.xt
      .saveFileAs(source, message.file_name || '')
      .then((saved) => {
        if (saved) notify('已保存')
      })
      .catch((error) => notify(error instanceof Error ? error.message : '保存失败', 'error'))
  }

  const cancelSend = (message: Message): void => {
    onCancelSend(message.id)
    closeMenu()
  }

  const retrySend = (message: Message): void => {
    onRetry(message.id)
    closeMenu()
  }

  const askDelete = (message: Message, missing: boolean): void => {
    setConfirmMissing(missing)
    setConfirmDelete(message)
    closeMenu()
  }

  const doDelete = (removeFile: boolean): void => {
    const target = confirmDelete
    setConfirmDelete(null)
    setConfirmMissing(false)
    if (!target || isSendingMessage(target)) return
    void (async () => {
      if (removeFile && target.local_path) await window.xt.deleteFile(target.local_path)
      onDelete(target.id)
    })()
  }

  const submit = async (): Promise<void> => {
    const text = body.trim()
    if (!text || sending) return
    setSending(true)
    setError('')
    stickRef.current = true
    // 立刻清空输入框，气泡也在同一时刻乐观插入；失败再把文字放回去。
    setBody('')
    try {
      await onSend(text)
    } catch (err) {
      setBody((current) => (current ? current : text))
      setError(err instanceof Error ? describeError(err.message) : '发送失败')
    } finally {
      setSending(false)
    }
  }

  const isPublic = conv.id === PUBLIC_CONVERSATION_ID
  const webUrl = webStatus?.running ? webStatus.url : ''
  const webOnline = webStatus?.running ? (webStatus.online ?? 0) : 0

  useEffect(() => {
    if (!isPublic) return
    let alive = true
    const pull = (): void => {
      window.xt
        .webChatStatus()
        .then((status) => {
          if (alive) setWebStatus(status)
        })
        .catch(() => {})
    }
    pull()
    const timer = window.setInterval(pull, 3000)
    return () => {
      alive = false
      window.clearInterval(timer)
    }
  }, [isPublic])

  useEffect(() => {
    if (!webOpen || !webUrl) return
    let alive = true
    void import('qrcode')
      .then((mod) => mod.toDataURL(webUrl, { width: 480, margin: 1, errorCorrectionLevel: 'M' }))
      .then((data) => {
        if (alive) setWebQr(data)
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [webOpen, webUrl])

  const toggleWeb = async (enabled: boolean): Promise<void> => {
    if (webBusy) return
    setWebBusy(true)
    try {
      const next = enabled ? await window.xt.webChatStart() : await window.xt.webChatStop()
      setWebStatus(next)
      if (enabled && next.error) notify(next.error, 'warning')
    } catch (error) {
      notify(error instanceof Error ? error.message : '操作失败', 'error')
    } finally {
      setWebBusy(false)
    }
  }

  const keyword = searchQuery.trim().toLowerCase()
  const visibleMessages = keyword
    ? messages.filter(
        (message) =>
          (message.body ?? '').toLowerCase().includes(keyword) ||
          (message.file_name ?? '').toLowerCase().includes(keyword)
      )
    : messages
  const bucketCounts = new Map<string, number>()
  for (const entry of bucketEntries) {
    const label = entry.kind === 'folder' ? '文件夹' : fileKindLabel(entry.name || entry.path)
    bucketCounts.set(label, (bucketCounts.get(label) ?? 0) + 1)
  }
  const bucketParts = BUCKET_TYPE_ORDER.filter((label) => bucketCounts.has(label)).map(
    (label) => `${bucketCounts.get(label)}个${label}`
  )
  const bucketLabel = `桶中 ${bucketParts.join('，')}`

  const menuMessage = menuShown?.message
  const menuIsText = menuMessage?.kind === 'text'
  const menuIsAttach = menuMessage !== undefined && menuMessage.kind !== 'text'
  const menuHasPath = !!menuMessage?.local_path
  const menuCanSaveAs =
    menuMessage?.kind === 'file' && menuHasPath && !menuShown?.missing
  const menuCanOpenWith = menuIsAttach && menuHasPath && !menuShown?.missing
  const menuCanCancel =
    menuMessage !== undefined &&
    menuMessage.direction === 'out' &&
    menuIsAttach &&
    (menuMessage.status === 'queued' ||
      menuMessage.status === 'sending' ||
      menuMessage.status === 'active' ||
      menuMessage.status === 'accepted')
  const menuCanRetry =
    menuMessage !== undefined &&
    menuMessage.direction === 'out' &&
    menuIsAttach &&
    menuMessage.status === 'failed' &&
    menuHasPath &&
    !menuShown?.missing
  const menuCanDelete = menuMessage !== undefined && !isSendingMessage(menuMessage)

  const messageMenuItems: React.ReactNode[] = []
  if (menuIsText && menuMessage) {
    messageMenuItems.push(<MenuItem onClick={() => copyText(menuMessage)}>复制文本</MenuItem>)
  }
  if (!menuIsText && menuHasPath && menuMessage) {
    messageMenuItems.push(<MenuItem onClick={() => copyPath(menuMessage)}>复制文件路径</MenuItem>)
  }
  if (menuIsAttach && menuMessage) {
    messageMenuItems.push(
      <MenuItem
        onClick={() => {
          setInfoTarget(menuMessage)
          closeMenu()
        }}
      >
        查看文件信息
      </MenuItem>
    )
  }
  if (menuCanSaveAs && menuMessage) {
    messageMenuItems.push(<MenuItem onClick={() => saveAs(menuMessage)}>另存为…</MenuItem>)
  }
  if (menuCanOpenWith && menuMessage) {
    messageMenuItems.push(
      <MenuItem onClick={() => openWithOther(menuMessage)}>用其他应用打开</MenuItem>
    )
    messageMenuItems.push(
      <MenuItem onClick={() => revealFile(menuMessage)}>打开所在文件夹</MenuItem>
    )
  }
  if (menuCanCancel && menuMessage) {
    messageMenuItems.push(<MenuItem onClick={() => cancelSend(menuMessage)}>取消发送</MenuItem>)
  }
  if (menuCanRetry && menuMessage) {
    messageMenuItems.push(<MenuItem onClick={() => retrySend(menuMessage)}>重试</MenuItem>)
  }
  if (menuCanDelete && menuMessage) {
    messageMenuItems.push(
      <MenuItem onClick={() => askDelete(menuMessage, !!menuShown?.missing)}>删除</MenuItem>
    )
  }

  return (
    <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', bgcolor: 'background.default' }}>
      <Box
        sx={{
          px: 3,
          py: 1.25,
          minHeight: 64,
          display: 'flex',
          alignItems: 'center',
          gap: 1.25,
          borderBottom: 1,
          borderColor: 'divider'
        }}
      >
        <Box sx={{ minWidth: 0 }}>
          <Typography noWrap sx={{ fontSize: 16, fontWeight: 600 }}>
            {conv.name}
            {conv.originalName && (
              <Box
                component="span"
                sx={{ fontWeight: 400, color: 'text.secondary', opacity: 0.75 }}
              >
                {`（${conv.originalName}）`}
              </Box>
            )}
          </Typography>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
            {bleConnecting ? (
              <CircularProgress size={9} thickness={6} />
            ) : (
              <Box
                sx={{
                  width: 7,
                  height: 7,
                  borderRadius: '50%',
                  bgcolor: online ? 'success.main' : 'text.disabled'
                }}
              />
            )}
            <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
              {isPublic
                ? lanOnline
                  ? '已连接'
                  : '未连接'
                : isBle
                  ? bleConnected
                    ? '已连接'
                    : bleConnecting
                      ? '正在连接'
                      : '未连接'
                  : online
                    ? '在线'
                    : '离线'}
            </Typography>
            <Box
              onClick={showReconnect && !reconnectBusy ? startReconnect : undefined}
              sx={{
                height: 20,
                borderRadius: '6px',
                bgcolor: online ? 'success.main' : 'action.hover',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                flexShrink: 0,
                gap: 0.35,
                pl: 0,
                pr: showReconnect ? 0.6 : 0,
                cursor: showReconnect && !reconnectBusy ? 'pointer' : 'default'
              }}
            >
              <Box
                sx={{
                  width: 20,
                  height: 20,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center'
                }}
              >
                {isBle ? (
                  <BluetoothRounded
                    sx={{ fontSize: 13, color: online ? 'success.contrastText' : 'text.disabled' }}
                  />
                ) : (
                  <WifiRounded
                    sx={{ fontSize: 13, color: online ? 'success.contrastText' : 'text.disabled' }}
                  />
                )}
              </Box>
              {showReconnect && (
                <Box component="span" sx={{ fontSize: 11, color: 'primary.main' }}>
                  {reconnectBusy ? '连接中' : '重连'}
                </Box>
              )}
            </Box>
          </Box>
        </Box>
        <Box sx={{ flex: 1 }} />
        {isPublic && (
          <Tooltip title="网页聊天">
            <IconButton
              size="small"
              onClick={() => setWebOpen(true)}
              sx={{
                color: webStatus?.running ? 'primary.main' : 'inherit',
                gap: 0.5,
                px: webOnline > 0 ? 1 : undefined
              }}
            >
              <LanguageRounded fontSize="small" />
              {webOnline > 0 && (
                <Typography
                  component="span"
                  sx={{ fontSize: 12, fontWeight: 600, lineHeight: 1 }}
                >
                  {webOnline}
                </Typography>
              )}
            </IconButton>
          </Tooltip>
        )}
        {(isPublic || isBle || trusted !== undefined) && (
          <>
            <IconButton size="small" onClick={(event) => setTrustAnchor(event.currentTarget)}>
              <MoreVertRounded fontSize="small" />
            </IconButton>
            <Menu
              anchorEl={trustShown}
              open={trustAnchor !== null}
              onClose={() => setTrustAnchor(null)}
              slotProps={{
                paper: {
                  sx: {
                    minWidth: 180,
                    borderRadius: '14px',
                    '& .MuiMenuItem-root': { minHeight: 32, py: 0, fontSize: 14 }
                  }
                }
              }}
            >
              {isPublic ? (
                <>
                  <MenuItem
                    sx={{ gap: 2, pr: 1 }}
                    // 开关项点文字、点开关都只切换状态，都不关菜单。
                    onClick={() => onSetPublicNotify(!publicNotify)}
                  >
                    <Typography sx={{ flex: 1, fontSize: 14 }}>允许通知</Typography>
                    <Switch
                      size="small"
                      edge="end"
                      checked={!!publicNotify}
                      onClick={(event) => event.stopPropagation()}
                      onChange={(event) => onSetPublicNotify(event.target.checked)}
                    />
                  </MenuItem>
                  <Divider />
                  <MenuItem
                    onClick={() => {
                      setTrustAnchor(null)
                      setConfirmClear(true)
                    }}
                  >
                    清空聊天记录
                  </MenuItem>
                </>
              ) : isBle ? (
                <>
                  <MenuItem
                    sx={{ gap: 2, pr: 1 }}
                    // 开关项点文字、点开关都只切换状态，都不关菜单。
                    onClick={() => onSetMuted(!muted)}
                  >
                    <Typography sx={{ flex: 1, fontSize: 14 }}>免打扰</Typography>
                    <Switch
                      size="small"
                      edge="end"
                      checked={!!muted}
                      onClick={(event) => event.stopPropagation()}
                      onChange={(event) => onSetMuted(event.target.checked)}
                    />
                  </MenuItem>
                  <Divider />
                  <MenuItem
                    onClick={() => {
                      setTrustAnchor(null)
                      setAliasDraft(alias ?? '')
                      setAliasOpen(true)
                    }}
                  >
                    修改备注
                  </MenuItem>
                  <Divider />
                  <MenuItem
                    onClick={() => {
                      setTrustAnchor(null)
                      setSearchQuery('')
                      setSearchOpen(true)
                    }}
                  >
                    搜索历史记录
                  </MenuItem>
                  <Divider />
                  <MenuItem
                    onClick={() => {
                      setTrustAnchor(null)
                      setConfirmClear(true)
                    }}
                  >
                    清空聊天记录
                  </MenuItem>
                </>
              ) : (
                <>
                  <MenuItem
                    sx={{ gap: 2, pr: 1 }}
                    // 开关项点文字、点开关都只切换状态，都不关菜单。
                    onClick={() => onSetClipboard(!clipboardOn)}
                  >
                    <Typography sx={{ flex: 1, fontSize: 14 }}>接收跨设备复制</Typography>
                    <Switch
                      size="small"
                      edge="end"
                      checked={!!clipboardOn}
                      onClick={(event) => event.stopPropagation()}
                      onChange={(event) => onSetClipboard(event.target.checked)}
                    />
                  </MenuItem>
                  <MenuItem
                    sx={{ gap: 2, pr: 1 }}
                    onClick={() => onSetTrust(!trusted)}
                  >
                    <Typography sx={{ flex: 1, fontSize: 14 }}>自动接收文件</Typography>
                    <Switch
                      size="small"
                      edge="end"
                      checked={!!trusted}
                      onClick={(event) => event.stopPropagation()}
                      onChange={(event) => onSetTrust(event.target.checked)}
                    />
                  </MenuItem>
                  <Divider />
                  <MenuItem
                    sx={{ gap: 2, pr: 1 }}
                    onClick={() => onSetMuted(!muted)}
                  >
                    <Typography sx={{ flex: 1, fontSize: 14 }}>免打扰</Typography>
                    <Switch
                      size="small"
                      edge="end"
                      checked={!!muted}
                      onClick={(event) => event.stopPropagation()}
                      onChange={(event) => onSetMuted(event.target.checked)}
                    />
                  </MenuItem>
                  <Divider />
                  <MenuItem
                    onClick={() => {
                      setTrustAnchor(null)
                      setAliasDraft(alias ?? '')
                      setAliasOpen(true)
                    }}
                  >
                    修改备注
                  </MenuItem>
                  <Divider />
                  <MenuItem
                    onClick={() => {
                      setTrustAnchor(null)
                      setSearchQuery('')
                      setSearchOpen(true)
                    }}
                  >
                    搜索历史记录
                  </MenuItem>
                </>
              )}
            </Menu>
          </>
        )}
      </Box>

      {searchOpen && (
        <Box
          sx={{
            px: 2.5,
            py: 1,
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            borderBottom: 1,
            borderColor: 'divider'
          }}
        >
          <TextField
            autoFocus
            size="small"
            fullWidth
            placeholder="搜索聊天记录"
            value={searchQuery}
            onChange={(event) => setSearchQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Escape') {
                setSearchOpen(false)
                setSearchQuery('')
              }
            }}
          />
          <Button
            size="small"
            onClick={() => {
              setSearchOpen(false)
              setSearchQuery('')
            }}
          >
            关闭
          </Button>
        </Box>
      )}

      <Box
        ref={listRef}
        onScroll={handleListScroll}
        sx={{ flex: 1, overflowY: 'auto', px: 3, py: 2.5 }}
      >
        {visibleMessages.length === 0 ? (
          <Box sx={{ height: '100%', display: 'grid', placeItems: 'center' }}>
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
              {keyword ? '无匹配结果' : '暂无消息'}
            </Typography>
          </Box>
        ) : (
          visibleMessages.map((message, index) => {
            const prev = visibleMessages[index - 1]
            const stamp = message.created_at || 0
            const showTime =
              stamp > 0 && (!prev || stamp - (prev.created_at || 0) >= TIME_GAP_SECONDS)
            return (
              <Fragment key={message.id}>
                {showTime && (
                  <Box sx={{ display: 'flex', justifyContent: 'center', py: 1.25 }}>
                    <Typography sx={{ fontSize: 11.5, color: 'text.secondary' }}>
                      {formatChatTime(stamp)}
                    </Typography>
                  </Box>
                )}
                <MessageRow
                  message={message}
                  showName={isPublic}
                  onDecide={onDecide}
                  onAcceptAll={
                    message.id === lastPendingId
                      ? () => pendingIncoming.forEach((id) => onDecide(id, 'accept'))
                      : undefined
                  }
                  onRetry={onRetry}
                  onRetryText={onRetryText}
                  onCancelSend={onCancelSend}
                  onOpenFile={onOpenFile}
                  onContextMenu={(event, missing) => openMenu(event, message, missing)}
                />
              </Fragment>
            )
          })
        )}
      </Box>

      <Box sx={{ px: 2.5, pb: 2, pt: 1.25, borderTop: 1, borderColor: 'divider' }}>
        {!isPublic && (
          <Box
            sx={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'flex-start',
              gap: 0.5,
              mb: 0.75,
              ml: -0.75
            }}
          >
            <Tooltip title="截图">
              <span>
                <IconButton size="small" disabled={!transportReady} onClick={onScreenshot}>
                  <ScreenshotRounded sx={{ fontSize: 18 }} />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip title="相册">
              <span>
                <IconButton
                  size="small"
                  disabled={!transportReady}
                  onClick={() => onPickAndSend('media')}
                >
                  <PhotoLibraryRounded sx={{ fontSize: 18 }} />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip title="文件">
              <span>
                <IconButton
                  size="small"
                  disabled={!transportReady}
                  onClick={() => onPickAndSend('files')}
                >
                  <InsertDriveFileRounded sx={{ fontSize: 18 }} />
                </IconButton>
              </span>
            </Tooltip>
            {!isBle && (
              <Tooltip title="文件夹">
                <span>
                  <IconButton
                    size="small"
                    disabled={!transportReady}
                    onClick={() => onPickAndSend('folders')}
                  >
                    <FolderRounded sx={{ fontSize: 18 }} />
                  </IconButton>
                </span>
              </Tooltip>
            )}
            <Box sx={{ flex: 1 }} />
            {recvDir && (
              <Box sx={{ display: 'flex', alignItems: 'center', minWidth: 0, maxWidth: 280 }}>
                <Typography noWrap sx={{ fontSize: 12, color: 'text.secondary', flexShrink: 0 }}>
                  文件接收保存到：
                </Typography>
                <Typography
                  noWrap
                  onClick={() => {
                    window.xt.openPath(recvDir).catch(() => {})
                  }}
                  sx={{ fontSize: 12, color: '#1976d2', minWidth: 0, cursor: 'pointer' }}
                >
                  {shortDir(recvDir)}
                </Typography>
              </Box>
            )}
          </Box>
        )}
        {!isPublic && !isBle && bucketParts.length > 0 && (
          <Box
            onClick={onOpenBucket}
            sx={{
              mb: 1,
              px: 1.5,
              py: 0.75,
              borderRadius: 3,
              bgcolor: 'action.hover',
              display: 'flex',
              alignItems: 'center',
              gap: 1,
              width: 'fit-content',
              maxWidth: '100%',
              cursor: 'pointer'
            }}
          >
            <Typography
              sx={{
                flex: '1 1 auto',
                minWidth: 0,
                fontSize: 12.5,
                color: 'text.secondary',
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                textOverflow: 'ellipsis'
              }}
            >
              {bucketLabel}
            </Typography>
            <Button
              size="small"
              variant="contained"
              disabled={!serviceRunning}
              sx={{ flexShrink: 0 }}
              onClick={(event) => {
                event.stopPropagation()
                onSendBucket()
              }}
            >
              发送
            </Button>
          </Box>
        )}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <TextField
            fullWidth
            multiline
            minRows={1}
            maxRows={5}
            placeholder={
              isBle && !bleConnected ? (bleConnecting ? '正在连接' : '蓝牙未连接') : '输入消息'
            }
            value={body}
            disabled={!transportReady}
            inputRef={inputRef}
            onChange={(event) => setBody(event.target.value)}
            onKeyDown={(event) => {
              if (event.key !== 'Enter' || event.nativeEvent.isComposing) return
              if (event.shiftKey) {
                event.preventDefault()
                insertBreak()
                return
              }
              const withCtrl = event.ctrlKey || event.metaKey
              // 开启回车发送时 Enter 发、Ctrl+Enter 换行；关闭时反过来。
              if (!enterToSend && !withCtrl) return
              event.preventDefault()
              if (enterToSend && withCtrl) {
                insertBreak()
              } else {
                void submit()
              }
            }}
            sx={{
              '& .MuiOutlinedInput-root': {
                borderRadius: '22px',
                py: 1,
                alignItems: 'flex-end'
              },
              '& textarea': { userSelect: 'text' }
            }}
          />
          <Tooltip title="发送">
            <span>
              <IconButton
                color="primary"
                onClick={() => void submit()}
                disabled={!canSend}
                sx={{ width: 40, height: 40, flexShrink: 0 }}
              >
                <SendRounded />
              </IconButton>
            </span>
          </Tooltip>
        </Box>
        {error && (
          <Typography
            data-selectable="true"
            sx={{
              mt: 0.75,
              px: 1,
              fontSize: 12,
              color: 'error.main',
              userSelect: 'text',
              whiteSpace: 'pre-wrap',
              cursor: 'text'
            }}
          >
            {error}
          </Typography>
        )}
        {!transportReady && (
          <Typography sx={{ mt: 0.75, px: 1, fontSize: 12, color: 'warning.main' }}>
            {isBle ? (bleConnecting ? '正在连接蓝牙设备' : '蓝牙未连接') : '服务未运行'}
          </Typography>
        )}
      </Box>

      <Menu
        open={menu !== null}
        onClose={closeMenu}
        anchorReference="anchorPosition"
        anchorPosition={menuShown ? { top: menuShown.y, left: menuShown.x } : undefined}
        slotProps={{
          paper: {
            sx: {
              minWidth: 180,
              borderRadius: '14px',
              '& .MuiMenuItem-root': { minHeight: 32, py: 0, fontSize: 14 }
            }
          }
        }}
      >
        {messageMenuItems.map((item, index) => (
          <Fragment key={index}>
            {index > 0 && <Divider />}
            {item}
          </Fragment>
        ))}
      </Menu>

      <Dialog open={confirmDelete !== null} onClose={() => setConfirmDelete(null)}>
        <DialogTitle sx={{ fontSize: 18 }}>删除</DialogTitle>
        <DialogContent sx={{ pt: 1, pb: 0 }}>
          <Typography sx={{ fontSize: 14, wordBreak: 'break-all' }}>
            {deleteShown?.kind === 'text'
              ? '确定要删除这条消息吗？'
              : `确定要删除「${deleteShown?.file_name || '文件'}」吗？`}
          </Typography>
          {deleteShown && deleteShown.kind !== 'text' && deleteShown.local_path && (
            <Typography
              sx={{ mt: 0.75, fontSize: 12, color: 'text.secondary', wordBreak: 'break-all' }}
            >
              {deleteShown.local_path}
            </Typography>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 2, pb: 1.5 }}>
          {deleteShown !== null &&
            deleteShown.kind !== 'text' &&
            deleteShown.direction === 'in' &&
            !confirmMissing && (
              <Button color="error" onClick={() => doDelete(true)}>
                删除文件
              </Button>
            )}
          <Box sx={{ flex: 1 }} />
          <Button onClick={() => doDelete(false)}>删除记录</Button>
          <Button onClick={() => setConfirmDelete(null)}>取消</Button>
        </DialogActions>
      </Dialog>

      <Dialog open={infoTarget !== null} onClose={() => setInfoTarget(null)}>
        <DialogTitle sx={{ fontSize: 18 }}>文件信息</DialogTitle>
        <DialogContent sx={{ minWidth: 400 }}>
          {infoShown &&
            [
              { label: '文件名', value: infoShown.file_name || '未命名' },
              {
                label: '类型',
                value:
                  infoShown.kind === 'folder'
                    ? '文件夹'
                    : fileKindLabel(infoShown.file_name || infoShown.local_path || '')
              },
              {
                label: '大小',
                value:
                  infoShown.kind === 'folder'
                    ? folderSize !== null
                      ? formatSize(folderSize) || '0 B'
                      : '—'
                    : formatSize(infoShown.file_size) || '—'
              },
              { label: '路径', value: infoShown.local_path || '—' },
              { label: '时间', value: formatTime(infoShown.created_at, true) },
              { label: '状态', value: statusLabel(infoShown.status) }
            ].map((row) => (
              <Box key={row.label} sx={{ display: 'flex', gap: 2, py: 0.5 }}>
                <Typography
                  sx={{ width: 56, flexShrink: 0, fontSize: 13, color: 'text.secondary' }}
                >
                  {row.label}
                </Typography>
                <Typography sx={{ flex: 1, fontSize: 13, wordBreak: 'break-all' }}>
                  {row.value}
                </Typography>
              </Box>
            ))}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setInfoTarget(null)}>关闭</Button>
        </DialogActions>
      </Dialog>

      <Dialog open={confirmClear} onClose={() => setConfirmClear(false)}>
        <DialogTitle sx={{ fontSize: 18 }}>清空聊天记录</DialogTitle>
        <DialogContent sx={{ pt: 1, pb: 0 }}>
          <Typography sx={{ fontSize: 14 }}>
            确定要清空{isPublic ? '' : `和 ${conv.name} 的`}聊天记录吗？
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmClear(false)}>取消</Button>
          <Button
            color="error"
            variant="contained"
            onClick={() => {
              setConfirmClear(false)
              onClearHistory()
            }}
          >
            清空
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={webOpen} onClose={() => setWebOpen(false)}>
        <DialogTitle sx={{ fontSize: 18 }}>网页聊天</DialogTitle>
        <DialogContent sx={{ minWidth: 380, pt: 1 }}>
          <Box sx={{ display: 'flex', alignItems: 'center' }}>
            <Typography sx={{ flex: 1, fontSize: 14 }}>
              {webShown?.running ? '已开启' : '未开启'}
            </Typography>
            <Switch
              checked={!!webStatus?.running}
              disabled={webBusy}
              onChange={(event) => void toggleWeb(event.target.checked)}
            />
          </Box>
          <Typography sx={{ mt: 0.5, fontSize: 12.5, color: 'text.secondary' }}>
            开启后，局域网内的其他人可以用浏览器打开网址，以网页访客身份在公共频道聊天。
          </Typography>
          {webShown?.running && (
            <>
              <Box
                sx={{
                  mt: 1.5,
                  pl: 1.5,
                  pr: 0.5,
                  py: 0.5,
                  borderRadius: '12px',
                  bgcolor: 'action.hover',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1
                }}
              >
                <Box
                  component="a"
                  href={webShown.url}
                  onClick={(event) => {
                    event.preventDefault()
                    void window.xt.openExternal(webShown.url).catch(() => {})
                  }}
                  sx={{
                    flex: 1,
                    minWidth: 0,
                    fontSize: 13,
                    wordBreak: 'break-all',
                    color: 'primary.main',
                    textDecoration: 'none',
                    cursor: 'pointer',
                    '&:hover': { textDecoration: 'underline' }
                  }}
                >
                  {webShown.url}
                </Box>
                <Button
                  size="small"
                  sx={{ flexShrink: 0, minWidth: 0 }}
                  onClick={() => {
                    void window.xt.openExternal(webShown.url).catch(() => {})
                  }}
                >
                  打开
                </Button>
              </Box>
              {webQr && (
                <Box sx={{ mt: 2, display: 'flex', justifyContent: 'center' }}>
                  <Box
                    component="img"
                    src={webQr}
                    alt=""
                    sx={{ width: 220, height: 220, p: 1, bgcolor: '#fff', borderRadius: '16px' }}
                  />
                </Box>
              )}
            </>
          )}
          {!webShown?.running && webShown?.error && (
            <Typography sx={{ mt: 1, fontSize: 12.5, color: 'error.main' }}>
              {webShown.error}
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setWebOpen(false)}>关闭</Button>
        </DialogActions>
      </Dialog>

      <Dialog open={bleOffHint} onClose={() => setBleOffHint(false)}>
        <DialogTitle sx={{ fontSize: 18 }}>蓝牙广播未开启</DialogTitle>
        <DialogContent sx={{ pt: 1, pb: 0 }}>
          <Typography sx={{ fontSize: 14 }}>
            蓝牙广播关着，无法连接这个设备。开启后会立即尝试连接。
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setBleOffHint(false)}>取消</Button>
          <Button
            variant="contained"
            onClick={() => {
              setBleOffHint(false)
              setReconnectBusy(true)
              void window.xt.bleConnect(blePeerId)
            }}
          >
            开启并连接
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={aliasOpen} onClose={() => setAliasOpen(false)}>
        <DialogTitle sx={{ fontSize: 18 }}>修改备注</DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            fullWidth
            value={aliasDraft}
            onChange={(event) => setAliasDraft(event.target.value)}
            slotProps={{ htmlInput: { maxLength: 15 } }}
            sx={{ mt: 1, minWidth: 320 }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAliasOpen(false)}>取消</Button>
          <Button
            variant="contained"
            onClick={() => {
              const text = aliasDraft.trim().slice(0, 15)
              setAliasOpen(false)
              onSetAlias(text)
            }}
          >
            保存
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
