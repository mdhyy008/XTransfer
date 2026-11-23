import { Fragment, useCallback, useEffect, useMemo, useRef, useState, type DragEvent } from 'react'
import {
  Box,
  Button,
  ButtonBase,
  Checkbox,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Drawer,
  IconButton,
  LinearProgress,
  List,
  ListItem,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  TextField,
  Tooltip,
  Typography
} from '@mui/material'
import ArrowBackRounded from '@mui/icons-material/ArrowBackRounded'
import ArrowUpwardRounded from '@mui/icons-material/ArrowUpwardRounded'
import ChecklistRounded from '@mui/icons-material/ChecklistRounded'
import CloudDownloadRounded from '@mui/icons-material/CloudDownloadRounded'
import ChevronRightRounded from '@mui/icons-material/ChevronRightRounded'
import CloseRounded from '@mui/icons-material/CloseRounded'
import CreateNewFolderRounded from '@mui/icons-material/CreateNewFolderRounded'
import DeleteRounded from '@mui/icons-material/DeleteRounded'
import DescriptionRounded from '@mui/icons-material/DescriptionRounded'
import DoneAllRounded from '@mui/icons-material/DoneAllRounded'
import DownloadDoneRounded from '@mui/icons-material/DownloadDoneRounded'
import DownloadRounded from '@mui/icons-material/DownloadRounded'
import ErrorOutlineRounded from '@mui/icons-material/ErrorOutlineRounded'
import FolderOpenRounded from '@mui/icons-material/FolderOpenRounded'
import FolderRounded from '@mui/icons-material/FolderRounded'
import InsertDriveFileRounded from '@mui/icons-material/InsertDriveFileRounded'
import MovieRounded from '@mui/icons-material/MovieRounded'
import MusicNoteRounded from '@mui/icons-material/MusicNoteRounded'
import PhotoCameraRounded from '@mui/icons-material/PhotoCameraRounded'
import PhotoLibraryRounded from '@mui/icons-material/PhotoLibraryRounded'
import RefreshRounded from '@mui/icons-material/RefreshRounded'
import SmartphoneRounded from '@mui/icons-material/SmartphoneRounded'
import UploadRounded from '@mui/icons-material/UploadRounded'
import type {
  AdbDevice,
  AdbDownloadRecord,
  AdbPullProgress,
  AdbPushProgress,
  AdbStatus,
  PhoneEntry
} from '../../../shared/types'
import { formatChatTime, formatSize } from '../model'

type MenuState = { x: number; y: number; entry: PhoneEntry } | null

function parentOf(dir: string): string {
  const trimmed = dir.replace(/\/+$/, '')
  const cut = trimmed.lastIndexOf('/')
  return cut <= 0 ? '/' : trimmed.slice(0, cut)
}

const ROOT = '/storage/emulated/0'

const SHORTCUTS = [
  { label: '内部存储', path: ROOT, icon: SmartphoneRounded },
  { label: '下载', path: `${ROOT}/Download`, icon: DownloadRounded },
  { label: '相机', path: `${ROOT}/DCIM`, icon: PhotoCameraRounded },
  { label: '图片', path: `${ROOT}/Pictures`, icon: PhotoLibraryRounded },
  { label: '音乐', path: `${ROOT}/Music`, icon: MusicNoteRounded },
  { label: '视频', path: `${ROOT}/Movies`, icon: MovieRounded },
  { label: '文档', path: `${ROOT}/Documents`, icon: DescriptionRounded }
]

// adb 和文件系统抛出的英文原因对普通用户没有意义，在原文后面补一句括号解释。
const ERROR_HINTS: [RegExp, string][] = [
  [/no such file or directory/i, '手机上的这个文件或文件夹可能已经被删除'],
  [/permission denied|operation not permitted/i, 'Android 不允许访问这个位置'],
  [/device offline/i, '手机和电脑的连接已断开，请重新插拔数据线'],
  [/device .*not found|no devices\/emulators found/i, '手机不在线，请检查数据线连接'],
  [/more than one device/i, '同时连接了多台手机，无法确定操作哪一台'],
  [/unauthorized/i, '手机上还没有允许这台电脑的 USB 调试'],
  [/not a directory/i, '这个路径不是文件夹'],
  [/is a directory/i, '目标是文件夹，不是文件'],
  [/no space left|insufficient storage/i, '存储空间不足'],
  [/illegal byte sequence/i, '文件名编码在电脑上无法处理'],
  [/timed out|timeout/i, '设备长时间没有响应'],
  [/eacces|eperm/i, '电脑上没有权限写入这个位置'],
  [/enospc/i, '电脑磁盘空间不足'],
  [/enametoolong/i, '文件路径太长，Windows 无法创建'],
  [/ebusy/i, '文件正被其他程序占用'],
  [/adb 退出码|exit code/i, '手机端执行失败，可能路径不存在或没有权限']
]

function explainError(text: string): string {
  for (const [pattern, hint] of ERROR_HINTS) {
    if (pattern.test(text)) return text.includes(hint) ? text : `${text}（${hint}）`
  }
  return text
}

function errorText(error: unknown): string {
  if (!(error instanceof Error)) return '操作失败'
  return explainError(error.message)
}

const CONNECT_STEPS = [
  '打开手机「设置 → 关于手机」，连续点击「版本号」7 次',
  '进入「设置 → 开发者选项」，打开「USB 调试」',
  '用数据线连接电脑，连接方式选「传输文件」',
  '手机弹出「允许 USB 调试」时点允许，可勾选「一律允许」',
  '连接成功后这里会自动显示手机文件'
]

function GuideList({ steps }: { steps: string[] }) {
  return (
    <Box
      component="ol"
      sx={{
        m: 0,
        pl: '1.5em',
        maxWidth: 420,
        display: 'flex',
        flexDirection: 'column',
        gap: 0.75
      }}
    >
      {steps.map((step) => (
        <Typography
          key={step}
          component="li"
          sx={{ fontSize: 13, lineHeight: 1.6, color: 'text.secondary' }}
        >
          {step}
        </Typography>
      ))}
    </Box>
  )
}

function DeviceHint({
  installed,
  progress,
  onInstall,
  onRefresh
}: {
  installed: boolean
  progress: { received: number; total: number } | null
  onInstall: () => void
  onRefresh: () => void
}) {
  const percent =
    progress && progress.total > 0
      ? Math.min(100, Math.round((progress.received / progress.total) * 100))
      : null
  return (
    <Box
      sx={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 2,
        px: 4
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
        <SmartphoneRounded sx={{ fontSize: 44, color: 'text.disabled' }} />
      </Box>
      <Typography sx={{ fontSize: 15, fontWeight: 600, color: 'text.primary' }}>
        未找到设备
      </Typography>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <Box
          sx={{
            width: 7,
            height: 7,
            borderRadius: '50%',
            bgcolor: installed ? 'success.main' : 'error.main'
          }}
        />
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          {installed ? 'ADB 可用' : 'ADB 不可用'}
        </Typography>
        {!installed && !progress && (
          <Button variant="contained" size="small" onClick={onInstall}>
            安装 ADB
          </Button>
        )}
      </Box>
      {!installed && progress && (
        <Box sx={{ width: 260 }}>
          <LinearProgress
            variant={percent === null ? 'indeterminate' : 'determinate'}
            value={percent ?? 0}
            sx={{ borderRadius: '999px' }}
          />
          {percent !== null && (
            <Typography sx={{ mt: 0.75, fontSize: 12, color: 'text.secondary', textAlign: 'center' }}>
              {percent}%
            </Typography>
          )}
        </Box>
      )}
      <GuideList steps={CONNECT_STEPS} />
      <Button variant="contained" size="small" onClick={onRefresh}>
        刷新
      </Button>
    </Box>
  )
}

export default function PhoneFiles({
  notify,
  onBack
}: {
  notify: (text: string, severity?: 'success' | 'error' | 'warning' | 'info') => void
  onBack?: () => void
}) {
  const [status, setStatus] = useState<AdbStatus | null>(null)
  const [devices, setDevices] = useState<AdbDevice[]>([])
  const [serial, setSerial] = useState('')
  const [current, setCurrent] = useState('/storage/emulated/0')
  const [entries, setEntries] = useState<PhoneEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState<{ received: number; total: number } | null>(null)
  const [selecting, setSelecting] = useState(false)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [menu, setMenu] = useState<MenuState>(null)
  const [deviceMenuAnchor, setDeviceMenuAnchor] = useState<HTMLElement | null>(null)
  const [renameTarget, setRenameTarget] = useState<PhoneEntry | null>(null)
  const [renameDraft, setRenameDraft] = useState('')
  const [deleteTargets, setDeleteTargets] = useState<string[] | null>(null)
  const [mkdirOpen, setMkdirOpen] = useState(false)
  const [mkdirDraft, setMkdirDraft] = useState('')
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [pull, setPull] = useState<AdbPullProgress | null>(null)
  const [pullOpen, setPullOpen] = useState(false)
  const [history, setHistory] = useState<AdbDownloadRecord[]>([])
  const [pushProgress, setPushProgress] = useState<AdbPushProgress | null>(null)
  const dragOutRef = useRef(false)
  const aliveRef = useRef(true)
  // notify 每次渲染都是新函数，放进 load 的依赖会导致反复拉目录。
  const notifyRef = useRef(notify)
  notifyRef.current = notify

  useEffect(() => {
    aliveRef.current = true
    window.xt
      .adbStatus()
      .then((next) => {
        if (aliveRef.current) setStatus(next)
      })
      .catch(() => {
        if (aliveRef.current) setStatus({ installed: false, path: '' })
      })
    return () => {
      aliveRef.current = false
    }
  }, [])

  useEffect(
    () => window.xt.onAdbProgress((received, total) => setProgress({ received, total })),
    []
  )

  useEffect(() => window.xt.onAdbPullProgress(setPull), [])

  useEffect(() => window.xt.onAdbPushProgress(setPushProgress), [])

  // 拖出去的文件在原生拖拽期间，窗口自己不再接收任何拖放，鼠标移回来显示禁止。
  useEffect(() => {
    const stop = (): void => {
      dragOutRef.current = false
    }
    const onDragOver = (event: globalThis.DragEvent): void => {
      if (!dragOutRef.current) return
      event.stopPropagation()
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'none'
    }
    const onDrop = (event: globalThis.DragEvent): void => {
      if (!dragOutRef.current) return
      event.preventDefault()
      event.stopPropagation()
    }
    const offDragEnd = window.xt.onAdbDragEnd(stop)
    window.addEventListener('dragover', onDragOver, true)
    window.addEventListener('drop', onDrop, true)
    return () => {
      offDragEnd()
      window.removeEventListener('dragover', onDragOver, true)
      window.removeEventListener('drop', onDrop, true)
    }
  }, [])

  const loadHistory = useCallback((): void => {
    window.xt
      .adbPullHistory()
      .then((list) => {
        if (aliveRef.current) setHistory(list)
      })
      .catch(() => {})
  }, [])

  // 抽屉开着时定时重读，正在下载的任务开始后也能出现在记录里。
  useEffect(() => {
    if (!pullOpen) return
    loadHistory()
    const timer = window.setInterval(loadHistory, 1500)
    return () => window.clearInterval(timer)
  }, [pullOpen, loadHistory])

  // 插拔手机不需要手动刷新：每 3 秒看一次设备列表，设备状态变了自动跟着换。
  useEffect(() => {
    if (!status?.installed) return
    let alive = true
    const tick = (): void => {
      window.xt
        .adbDevices()
        .then((list) => {
          if (!alive) return
          setDevices(list)
          setSerial((prev) => {
            if (prev && list.some((item) => item.serial === prev && item.state === 'device')) {
              return prev
            }
            return list.find((item) => item.state === 'device')?.serial ?? ''
          })
        })
        .catch(() => {})
    }
    tick()
    const timer = window.setInterval(tick, 3000)
    return () => {
      alive = false
      window.clearInterval(timer)
    }
  }, [status?.installed])

  const load = useCallback((): void => {
    if (!serial) {
      setEntries([])
      return
    }
    setLoading(true)
    window.xt
      .adbList(serial, current)
      .then((list) => {
        if (aliveRef.current) setEntries(list)
      })
      .catch((error) => notifyRef.current(errorText(error), 'error'))
      .finally(() => {
        if (aliveRef.current) setLoading(false)
      })
  }, [serial, current])

  useEffect(() => {
    setSelected(new Set())
    setSelecting(false)
    load()
  }, [load])

  useEffect(() => {
    setSelected((prev) => {
      if (prev.size === 0) return prev
      const alive = new Set(entries.map((entry) => entry.path))
      const next = new Set([...prev].filter((path) => alive.has(path)))
      return next.size === prev.size ? prev : next
    })
  }, [entries])

  const device = useMemo(
    () => devices.find((item) => item.serial === serial) ?? null,
    [devices, serial]
  )

  const crumbs = useMemo(() => {
    const parts = current.split('/').filter(Boolean)
    const out: { label: string; path: string }[] = [{ label: '/', path: '/' }]
    let acc = ''
    for (const part of parts) {
      acc += `/${part}`
      out.push({ label: part, path: acc })
    }
    return out
  }, [current])

  // 手动输入或点面包屑的目标先列一次，列不出来就不切换，保持原目录不变。
  const goTo = async (path: string, silent = false): Promise<boolean> => {
    if (!serial) return false
    const target = path.trim()
    if (!target) return false
    setLoading(true)
    try {
      const list = await window.xt.adbList(serial, target)
      if (!aliveRef.current) return true
      setEntries(list)
      setCurrent(target)
      exitSelection()
      return true
    } catch (error) {
      if (!silent) notify(errorText(error), 'error')
      return false
    } finally {
      if (aliveRef.current) setLoading(false)
    }
  }

  // 常用目录不是每台手机都有，缺了就给一句轻提示，不抛 adb 原始报错。
  const goShortcut = async (path: string): Promise<void> => {
    setEditing(false)
    if (!(await goTo(path, true))) notify('手机上还没有这个目录', 'warning')
  }

  const submitPath = async (): Promise<void> => {
    const target = draft.trim()
    if (!target) return
    const moved = await goTo(target)
    if (moved) setEditing(false)
  }

  const exitSelection = (): void => {
    setSelecting(false)
    setSelected(new Set())
  }

  const togglePath = (path: string): void => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(path)) next.delete(path)
      else next.add(path)
      return next
    })
  }

  const enter = (entry: PhoneEntry): void => {
    if (entry.kind === 'folder') {
      setEditing(false)
      setCurrent(entry.path)
      exitSelection()
    } else if (selecting) {
      togglePath(entry.path)
    }
  }

  const doDownloadAdb = async (): Promise<void> => {
    setProgress({ received: 0, total: 0 })
    try {
      const next = await window.xt.adbDownload()
      setStatus(next)
      notify('ADB 已就绪')
    } catch (error) {
      notify(errorText(error), 'error')
    } finally {
      setProgress(null)
    }
  }

  const doPull = async (paths: string[]): Promise<void> => {
    if (!serial || paths.length === 0) return
    setBusy(true)
    setPull({
      running: true,
      done: 0,
      total: 0,
      name: '',
      received: 0,
      size: 0,
      doneBytes: 0,
      totalBytes: 0
    })
    setPullOpen(true)
    try {
      const count = await window.xt.adbPull(serial, paths)
      if (count > 0) notify(`已下载 ${count} 项`)
      else setPull(null)
      exitSelection()
    } catch (error) {
      notify(errorText(error), 'error')
      setPull((prev) => (prev ? { ...prev, running: false } : prev))
    } finally {
      setBusy(false)
      loadHistory()
    }
  }

  const doPush = async (): Promise<void> => {
    if (!serial) return
    setBusy(true)
    setPushProgress({
      running: true,
      done: 0,
      total: 0,
      name: '',
      received: 0,
      size: 0,
      doneBytes: 0,
      totalBytes: 0
    })
    try {
      const count = await window.xt.adbPush(serial, current)
      if (count > 0) {
        notify(`已上传 ${count} 个文件`)
        load()
      }
    } catch (error) {
      notify(errorText(error), 'error')
    } finally {
      setBusy(false)
      setPushProgress(null)
    }
  }

  const uploadDropped = async (event: DragEvent): Promise<void> => {
    event.preventDefault()
    if (!serial || busy) return
    const files = Array.from(event.dataTransfer.files)
    if (files.length === 0) return
    const paths = files.map((file) => window.xt.filePath(file)).filter(Boolean)
    if (paths.length === 0) return
    setBusy(true)
    setPushProgress({
      running: true,
      done: 0,
      total: 0,
      name: '',
      received: 0,
      size: 0,
      doneBytes: 0,
      totalBytes: 0
    })
    try {
      const count = await window.xt.adbPushPaths(serial, paths, current)
      if (count > 0) {
        notify(`已上传 ${count} 个文件`)
        load()
      }
    } catch (error) {
      notify(errorText(error), 'error')
    } finally {
      setBusy(false)
      setPushProgress(null)
    }
  }

  // 只有接收目录里已有的文件能拖出：交给系统拖拽，用户放到哪个文件夹就复制到哪。
  const dragOut = async (entry: PhoneEntry): Promise<void> => {
    if (!serial) return
    dragOutRef.current = true
    try {
      const count = await window.xt.adbDragOut(serial, [entry.path])
      if (count === 0) dragOutRef.current = false
    } catch (error) {
      dragOutRef.current = false
      notify(errorText(error), 'error')
    }
  }

  const openRecvDir = async (): Promise<void> => {
    try {
      await window.xt.adbOpenRecvDir()
    } catch (error) {
      notify(errorText(error), 'error')
    }
  }

  const cancelPull = async (id: string): Promise<void> => {
    try {
      await window.xt.adbPullCancel(id)
    } catch (error) {
      notify(errorText(error), 'error')
    }
  }

  const deleteRecord = async (id: string): Promise<void> => {
    try {
      await window.xt.adbPullDelete(id)
      loadHistory()
    } catch (error) {
      notify(errorText(error), 'error')
    }
  }

  const doRemove = async (): Promise<void> => {
    if (!serial || !deleteTargets) return
    setBusy(true)
    try {
      await window.xt.adbRemove(serial, deleteTargets)
      setDeleteTargets(null)
      exitSelection()
      load()
      notify('已删除')
    } catch (error) {
      notify(errorText(error), 'error')
    } finally {
      setBusy(false)
    }
  }

  const doRename = async (): Promise<void> => {
    if (!serial || !renameTarget) return
    const name = renameDraft.trim()
    if (!name) return
    try {
      await window.xt.adbRename(serial, renameTarget.path, name)
      setRenameTarget(null)
      load()
    } catch (error) {
      notify(errorText(error), 'error')
    }
  }

  const doMkdir = async (): Promise<void> => {
    if (!serial) return
    const name = mkdirDraft.trim()
    if (!name) return
    try {
      await window.xt.adbMkdir(serial, current, name)
      setMkdirOpen(false)
      setMkdirDraft('')
      load()
    } catch (error) {
      notify(errorText(error), 'error')
    }
  }

  const menuEntry = menu?.entry ?? null
  const canBrowse = Boolean(serial) && device?.state === 'device'
  const busyNow = busy || loading
  const pullPercent = pull
    ? pull.totalBytes > 0
      ? Math.min(100, ((pull.doneBytes + pull.received) / pull.totalBytes) * 100)
      : pull.total > 0
        ? Math.min(100, (pull.done / pull.total) * 100)
        : 0
    : 0
  const pushPercent =
    pushProgress && pushProgress.totalBytes > 0
      ? Math.min(
          100,
          ((pushProgress.doneBytes + pushProgress.received) / pushProgress.totalBytes) * 100
        )
      : 0

  if (!status) {
    return (
      <Box sx={{ flex: 1, display: 'grid', placeItems: 'center' }}>
        <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>正在检查 ADB</Typography>
      </Box>
    )
  }

  if (!canBrowse) {
    const unauthorized = devices.some((item) => item.state === 'unauthorized')
    return (
      <Box sx={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2.5, pt: 2, pb: 1 }}>
          {onBack && (
            <IconButton size="small" onClick={onBack} sx={{ ml: -1 }}>
              <ArrowBackRounded fontSize="small" />
            </IconButton>
          )}
          <Typography sx={{ fontSize: 18, fontWeight: 600, color: 'text.primary' }}>
            手机文件
          </Typography>
        </Box>
        <Box sx={{ flex: 1, minHeight: 0 }}>
          {unauthorized ? (
            <Box
              sx={{
                height: '100%',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 2,
                px: 4
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
                <SmartphoneRounded sx={{ fontSize: 44, color: 'text.disabled' }} />
              </Box>
              <Typography sx={{ fontSize: 15, fontWeight: 600, color: 'text.primary' }}>
                设备未授权
              </Typography>
              <GuideList
                steps={[
                  '解锁手机，在弹出的「允许 USB 调试」里点允许',
                  '勾选「一律允许使用这台计算机进行调试」',
                  '没看到弹窗就拔插一次数据线，连接方式选「传输文件」'
                ]}
              />
            </Box>
          ) : (
            <DeviceHint
              installed={status.installed}
              progress={progress}
              onInstall={() => void doDownloadAdb()}
              onRefresh={() => {
                window.xt
                  .adbStatus()
                  .then((next) => {
                    setStatus(next)
                    if (next.installed) return window.xt.adbDevices().then(setDevices)
                  })
                  .catch((error) => notify(errorText(error), 'error'))
              }}
            />
          )}
        </Box>
      </Box>
    )
  }

  return (
    <Box
      sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}
      onDragOver={(event) => event.preventDefault()}
      onDrop={(event) => void uploadDropped(event)}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, px: 2.5, pt: 2, pb: 1 }}>
        {onBack && (
          <IconButton size="small" onClick={onBack} sx={{ ml: -1 }}>
            <ArrowBackRounded fontSize="small" />
          </IconButton>
        )}
        <Typography sx={{ fontSize: 18, fontWeight: 600, color: 'text.primary' }}>手机文件</Typography>
        {selecting && (
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
            已选 {selected.size}
          </Typography>
        )}
        <Box sx={{ flex: 1 }} />
        {selecting ? (
          <>
            <Tooltip title="全选">
              <IconButton
                size="small"
                onClick={() => setSelected(new Set(entries.map((entry) => entry.path)))}
              >
                <DoneAllRounded fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title="下载">
              <span>
                <IconButton
                  size="small"
                  disabled={selected.size === 0 || busy}
                  onClick={() => void doPull([...selected])}
                >
                  <DownloadRounded fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip title="删除">
              <span>
                <IconButton
                  size="small"
                  disabled={selected.size === 0 || busy}
                  onClick={() => setDeleteTargets([...selected])}
                  sx={{ color: 'error.main' }}
                >
                  <DeleteRounded fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip title="退出多选">
              <IconButton size="small" onClick={exitSelection}>
                <CloseRounded fontSize="small" />
              </IconButton>
            </Tooltip>
          </>
        ) : (
          <>
            {devices.length > 0 && (
              <Tooltip title="切换设备">
                <ButtonBase
                  onClick={(event) => setDeviceMenuAnchor(event.currentTarget)}
                  sx={{
                    height: 30,
                    pl: 1.05,
                    pr: 1.25,
                    gap: 0.75,
                    borderRadius: '999px',
                    bgcolor: 'action.hover',
                    '&:hover': { bgcolor: 'action.selected' }
                  }}
                >
                  <Box
                    sx={{
                      width: 8,
                      height: 8,
                      borderRadius: '50%',
                      flexShrink: 0,
                      bgcolor: device?.state === 'device' ? 'success.main' : 'error.main'
                    }}
                  />
                  <Typography
                    noWrap
                    sx={{ fontSize: 12.5, color: 'text.primary', maxWidth: 150 }}
                  >
                    {device?.model || serial}
                  </Typography>
                </ButtonBase>
              </Tooltip>
            )}
            <Tooltip title="刷新">
              <span>
                <IconButton size="small" disabled={busyNow} onClick={load}>
                  <RefreshRounded fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip title="新建文件夹">
              <span>
                <IconButton
                  size="small"
                  disabled={busyNow}
                  onClick={() => {
                    setMkdirDraft('')
                    setMkdirOpen(true)
                  }}
                >
                  <CreateNewFolderRounded fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip title="上传">
              <span>
                <IconButton size="small" disabled={busyNow} onClick={() => void doPush()}>
                  <UploadRounded fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip
              title={
                pull?.running ? `下载中 ${Math.floor(pullPercent)}%` : '下载管理'
              }
            >
              <Box
                sx={{
                  position: 'relative',
                  width: 38,
                  height: 38,
                  display: 'grid',
                  placeItems: 'center',
                  flexShrink: 0
                }}
              >
                {pull?.running && (
                  <CircularProgress
                    variant="determinate"
                    value={pullPercent}
                    size={38}
                    thickness={2.5}
                    sx={{ position: 'absolute', color: 'primary.main' }}
                  />
                )}
                <IconButton size="small" onClick={() => setPullOpen(true)} sx={{ p: 0.75 }}>
                  <CloudDownloadRounded fontSize="small" />
                </IconButton>
              </Box>
            </Tooltip>
            <Tooltip title="多选">
              <span>
                <IconButton
                  size="small"
                  disabled={entries.length === 0}
                  onClick={() => setSelecting(true)}
                >
                  <ChecklistRounded fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
          </>
        )}
      </Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, px: 2, pb: 1 }}>
        <IconButton
          size="small"
          disabled={current === '/' || busyNow}
          onClick={() => {
            setEditing(false)
            setCurrent(parentOf(current))
          }}
        >
          <ArrowUpwardRounded fontSize="small" />
        </IconButton>
        {editing ? (
          <TextField
            size="small"
            fullWidth
            autoFocus
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') void submitPath()
              else if (event.key === 'Escape') setEditing(false)
            }}
            onBlur={() => setEditing(false)}
            sx={{ '& .MuiInputBase-input': { fontSize: 12.5 } }}
          />
        ) : (
          <Box
            onDoubleClick={() => {
              setDraft(current)
              setEditing(true)
            }}
            title={current}
            sx={{
              flex: 1,
              minWidth: 0,
              display: 'flex',
              alignItems: 'center',
              overflow: 'hidden',
              borderRadius: 1,
              px: 0.5,
              py: 0.5,
              cursor: 'text',
              '&:hover': { bgcolor: 'action.hover' }
            }}
          >
            {crumbs.map((crumb, index) => (
              <Fragment key={crumb.path}>
                {index > 0 && (
                  <ChevronRightRounded
                    sx={{ fontSize: 16, color: 'text.disabled', flexShrink: 0 }}
                  />
                )}
                <Typography
                  noWrap
                  onClick={() => {
                    setEditing(false)
                    setCurrent(crumb.path)
                  }}
                  sx={{
                    px: 1,
                    py: 0.6,
                    display: 'flex',
                    alignItems: 'center',
                    borderRadius: 1,
                    fontSize: 12.5,
                    flexShrink: index === crumbs.length - 1 ? 1 : 0,
                    color: index === crumbs.length - 1 ? 'text.primary' : 'text.secondary',
                    cursor: 'pointer',
                    userSelect: 'none',
                    bgcolor: index === crumbs.length - 1 ? 'action.selected' : 'action.hover',
                    '&:hover': { bgcolor: 'action.selected' }
                  }}
                >
                  {crumb.label}
                </Typography>
              </Fragment>
            ))}
          </Box>
        )}
      </Box>
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 0.75,
          px: 2,
          pb: 1,
          flexShrink: 0,
          overflowX: 'auto',
          '&::-webkit-scrollbar': { height: 0 }
        }}
      >
        {SHORTCUTS.map((item) => {
          const active = current === item.path
          const Icon = item.icon
          return (
            <ButtonBase
              key={item.path}
              disabled={busyNow}
              onClick={() => {
                if (!active) void goShortcut(item.path)
              }}
              sx={{
                flexShrink: 0,
                height: 28,
                pl: 0.9,
                pr: 1.25,
                gap: 0.6,
                borderRadius: '999px',
                bgcolor: active ? 'action.selected' : 'action.hover',
                color: active ? 'primary.main' : 'text.secondary',
                '&:hover': { bgcolor: 'action.selected' },
                '&.Mui-disabled': { opacity: 0.5 }
              }}
            >
              <Icon sx={{ fontSize: 16 }} />
              <Typography sx={{ fontSize: 12.5, fontWeight: active ? 600 : 400 }}>
                {item.label}
              </Typography>
            </ButtonBase>
          )
        })}
      </Box>
      {pushProgress?.running && (
        <Box sx={{ px: 2.5, pb: 1 }}>
          {pushProgress.totalBytes > 0 ? (
            <LinearProgress
              variant="determinate"
              value={pushPercent}
              sx={{ height: 6, borderRadius: '999px' }}
            />
          ) : (
            <LinearProgress sx={{ height: 6, borderRadius: '999px' }} />
          )}
        </Box>
      )}
      <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto', px: 1.5, pb: 2 }}>
        {entries.length === 0 ? (
          <Box
            sx={{
              height: '100%',
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
              <FolderRounded sx={{ fontSize: 44, color: 'text.disabled' }} />
            </Box>
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
              {loading ? '正在读取' : '空文件夹'}
            </Typography>
          </Box>
        ) : (
          entries.map((entry) => (
            <ButtonBase
              key={entry.path}
              disableRipple
              draggable
              onClick={() => enter(entry)}
              onDragStart={(event) => {
                event.preventDefault()
                if (!serial) return
                if (window.xt.adbDragPrepared(entry.path, entry.size)) void dragOut(entry)
                else notify('先下载才能拖出', 'warning')
              }}
              onContextMenu={(event) => {
                event.preventDefault()
                if (selecting) return
                setMenu({ x: event.clientX, y: event.clientY, entry })
              }}
              sx={{
                width: '100%',
                justifyContent: 'flex-start',
                px: 1,
                py: 0.9,
                borderRadius: 3,
                textAlign: 'left',
                bgcolor: selecting && selected.has(entry.path) ? 'action.selected' : undefined,
                '&:hover': { bgcolor: 'action.hover' },
                '&:active': { bgcolor: 'action.selected' }
              }}
            >
              {selecting && (
                <Checkbox
                  size="small"
                  checked={selected.has(entry.path)}
                  tabIndex={-1}
                  onClick={(event) => {
                    event.stopPropagation()
                    togglePath(entry.path)
                  }}
                  sx={{ p: 0.5, ml: -0.25, mr: 0.5 }}
                />
              )}
              <Box
                sx={{
                  width: 40,
                  height: 40,
                  borderRadius: '12px',
                  display: 'grid',
                  placeItems: 'center',
                  flexShrink: 0,
                  bgcolor: 'action.hover',
                  color: 'text.secondary'
                }}
              >
                {entry.kind === 'folder' ? (
                  <FolderRounded fontSize="small" />
                ) : (
                  <InsertDriveFileRounded fontSize="small" />
                )}
              </Box>
              <Box sx={{ minWidth: 0, flex: 1, ml: 1.25 }}>
                <Typography noWrap sx={{ fontSize: 13.5, fontWeight: 500 }}>
                  {entry.name}
                </Typography>
                {entry.kind === 'file' && (
                  <Typography sx={{ mt: 0.25, fontSize: 12, color: 'text.secondary' }}>
                    {formatSize(entry.size)}
                  </Typography>
                )}
              </Box>
            </ButtonBase>
          ))
        )}
      </Box>
      <Menu
        open={deviceMenuAnchor !== null}
        anchorEl={deviceMenuAnchor}
        onClose={() => setDeviceMenuAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ paper: { sx: { minWidth: 190, borderRadius: '12px' } } }}
      >
        {devices.map((item) => (
          <MenuItem
            key={item.serial}
            selected={item.serial === serial}
            disabled={item.state !== 'device'}
            onClick={() => {
              setDeviceMenuAnchor(null)
              if (item.serial === serial) return
              setCurrent('/')
              setEditing(false)
              setSerial(item.serial)
            }}
          >
            <Box
              sx={{
                width: 8,
                height: 8,
                borderRadius: '50%',
                mr: 1.25,
                flexShrink: 0,
                bgcolor: item.state === 'device' ? 'success.main' : 'error.main'
              }}
            />
            <Typography noWrap sx={{ fontSize: 13 }}>
              {item.model || item.serial}
            </Typography>
          </MenuItem>
        ))}
      </Menu>
      <Menu
        open={menu !== null}
        onClose={() => setMenu(null)}
        anchorReference="anchorPosition"
        anchorPosition={menu ? { top: menu.y, left: menu.x } : undefined}
        slotProps={{ paper: { sx: { minWidth: 168, borderRadius: '12px' } } }}
      >
        <MenuItem
          onClick={() => {
            if (menuEntry) void doPull([menuEntry.path])
            setMenu(null)
          }}
        >
          下载
        </MenuItem>
        <MenuItem
          onClick={() => {
            if (menuEntry) {
              setRenameTarget(menuEntry)
              setRenameDraft(menuEntry.name)
            }
            setMenu(null)
          }}
        >
          重命名
        </MenuItem>
        <MenuItem
          onClick={() => {
            if (menuEntry) setDeleteTargets([menuEntry.path])
            setMenu(null)
          }}
          sx={{ color: 'error.main' }}
        >
          删除
        </MenuItem>
      </Menu>
      <Dialog
        open={mkdirOpen}
        onClose={() => setMkdirOpen(false)}
        slotProps={{ paper: { sx: { width: 420, borderRadius: '16px' } } }}
      >
        <DialogTitle sx={{ fontSize: 17 }}>新建文件夹</DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            fullWidth
            value={mkdirDraft}
            onChange={(event) => setMkdirDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') void doMkdir()
            }}
            sx={{ mt: 1 }}
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setMkdirOpen(false)}>取消</Button>
          <Button variant="contained" disabled={!mkdirDraft.trim()} onClick={() => void doMkdir()}>
            确定
          </Button>
        </DialogActions>
      </Dialog>
      <Dialog
        open={renameTarget !== null}
        onClose={() => setRenameTarget(null)}
        slotProps={{ paper: { sx: { width: 420, borderRadius: '16px' } } }}
      >
        <DialogTitle sx={{ fontSize: 17 }}>重命名</DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            fullWidth
            value={renameDraft}
            onChange={(event) => setRenameDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') void doRename()
            }}
            sx={{ mt: 1 }}
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setRenameTarget(null)}>取消</Button>
          <Button
            variant="contained"
            disabled={!renameDraft.trim()}
            onClick={() => void doRename()}
          >
            确定
          </Button>
        </DialogActions>
      </Dialog>
      <Dialog
        open={deleteTargets !== null}
        onClose={() => setDeleteTargets(null)}
        slotProps={{ paper: { sx: { width: 420, borderRadius: '16px' } } }}
      >
        <DialogTitle sx={{ fontSize: 17 }}>删除</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ fontSize: 13.5 }}>
            {deleteTargets && deleteTargets.length === 1
              ? deleteTargets[0].slice(deleteTargets[0].lastIndexOf('/') + 1)
              : `删除已选的 ${deleteTargets?.length ?? 0} 项？`}
          </DialogContentText>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setDeleteTargets(null)}>取消</Button>
          <Button color="error" variant="contained" onClick={() => void doRemove()}>
            删除
          </Button>
        </DialogActions>
      </Dialog>
      <Drawer
        anchor="right"
        open={pullOpen}
        onClose={() => setPullOpen(false)}
        slotProps={{
          paper: {
            sx: {
              width: 380,
              borderTopLeftRadius: 24,
              borderBottomLeftRadius: 24,
              bgcolor: 'background.default'
            }
          }
        }}
      >
        <Box sx={{ display: 'flex', alignItems: 'center', px: 2.5, py: 1.5 }}>
          <Typography sx={{ flex: 1, fontSize: 16, fontWeight: 600 }}>下载管理</Typography>
          <Tooltip title="打开接收目录">
            <IconButton size="small" onClick={() => void openRecvDir()}>
              <FolderOpenRounded fontSize="small" />
            </IconButton>
          </Tooltip>
          <Tooltip title="关闭">
            <IconButton size="small" onClick={() => setPullOpen(false)}>
              <CloseRounded fontSize="small" />
            </IconButton>
          </Tooltip>
        </Box>
        {pull && (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, px: 2.5, pb: 1.5 }}>
            <LinearProgress
              variant="determinate"
              value={pullPercent}
              sx={{ height: 8, borderRadius: '999px' }}
            />
            <Box sx={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between' }}>
              <Typography sx={{ fontSize: 22, fontWeight: 600 }}>
                {Math.floor(pullPercent)}%
              </Typography>
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                {pull.done}/{pull.total} 个文件
              </Typography>
            </Box>
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
              {formatSize(pull.doneBytes + pull.received)} / {formatSize(pull.totalBytes)}
            </Typography>
            {pull.running ? (
              <Typography noWrap sx={{ fontSize: 12.5, color: 'text.primary' }}>
                {pull.name ? `下载中 · ${pull.name}` : '下载中'}
              </Typography>
            ) : (
              pull.total > 0 && (
                <Typography sx={{ fontSize: 12.5, color: 'success.main' }}>下载完成</Typography>
              )
            )}
          </Box>
        )}
        <Typography sx={{ px: 2.5, pt: 1, pb: 0.5, fontSize: 13, fontWeight: 600, color: 'text.secondary' }}>
          下载记录
        </Typography>
        <List sx={{ flex: 1, overflowY: 'auto', overflowX: 'hidden', px: 1 }}>
          {history.map((record) => (
            <ListItem
              key={record.id}
              disablePadding
              sx={{ borderRadius: 4, mx: 1 }}
              secondaryAction={
                <Tooltip title={record.status === 'running' ? '取消' : '删除记录'}>
                  <IconButton
                    edge="end"
                    size="small"
                    onClick={() => {
                      if (record.status === 'running') void cancelPull(record.id)
                      else void deleteRecord(record.id)
                    }}
                  >
                    {record.status === 'running' ? (
                      <CloseRounded fontSize="small" />
                    ) : (
                      <DeleteRounded fontSize="small" />
                    )}
                  </IconButton>
                </Tooltip>
              }
            >
              <ListItemIcon
                sx={{
                  minWidth: 36,
                  color:
                    record.status === 'done'
                      ? 'success.main'
                      : record.status === 'failed'
                        ? 'error.main'
                        : 'text.disabled'
                }}
              >
                {record.status === 'done' ? (
                  <DownloadDoneRounded fontSize="small" />
                ) : record.status === 'running' ? (
                  <CircularProgress size={16} thickness={5} />
                ) : record.status === 'canceled' ? (
                  <CloseRounded fontSize="small" />
                ) : (
                  <ErrorOutlineRounded fontSize="small" />
                )}
              </ListItemIcon>
              <ListItemText
                sx={{ pr: 6 }}
                primary={record.names.join('、') || record.dir}
                secondary={
                  <Box component="span" sx={{ display: 'block' }}>
                    <Box component="span" sx={{ display: 'block' }}>
                      {[
                        formatChatTime(record.time / 1000),
                        record.status === 'done'
                          ? '完成'
                          : record.status === 'running'
                            ? '下载中'
                            : record.status === 'canceled'
                              ? '已取消'
                              : '失败',
                        formatSize(record.bytes)
                      ]
                        .filter(Boolean)
                        .join(' · ')}
                    </Box>
                    <Box
                      component="span"
                      sx={{
                        display: 'block',
                        overflow: 'hidden',
                        whiteSpace: 'nowrap',
                        textOverflow: 'ellipsis',
                        color: record.status === 'failed' ? 'error.main' : 'text.secondary'
                      }}
                    >
                      {record.status === 'failed'
                        ? explainError(record.error || record.dir)
                        : record.dir}
                    </Box>
                  </Box>
                }
                slotProps={{
                  primary: { noWrap: true, sx: { fontSize: 13.5 } },
                  secondary: { sx: { fontSize: 11.5 } }
                }}
              />
            </ListItem>
          ))}
          {history.length === 0 && (
            <Typography sx={{ px: 2.5, py: 2, fontSize: 13, color: 'text.secondary' }}>
              暂无下载记录
            </Typography>
          )}
        </List>
      </Drawer>
    </Box>
  )
}
