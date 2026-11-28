import { useEffect, useState, type ReactNode } from 'react'
import QRCode from 'qrcode'
import {
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  IconButton,
  Switch,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography
} from '@mui/material'
import { styled } from '@mui/material/styles'
import BlockRounded from '@mui/icons-material/BlockRounded'
import CancelRounded from '@mui/icons-material/CancelRounded'
import CheckCircleRounded from '@mui/icons-material/CheckCircleRounded'
import ChevronRightRounded from '@mui/icons-material/ChevronRightRounded'
import CloseRounded from '@mui/icons-material/CloseRounded'
import CodeRounded from '@mui/icons-material/CodeRounded'
import DataUsageRounded from '@mui/icons-material/DataUsageRounded'
import DescriptionRounded from '@mui/icons-material/DescriptionRounded'
import DragIndicatorRounded from '@mui/icons-material/DragIndicatorRounded'
import DevicesRounded from '@mui/icons-material/DevicesRounded'
import FeedbackRounded from '@mui/icons-material/FeedbackRounded'
import FolderOpenRounded from '@mui/icons-material/FolderOpenRounded'
import FolderRounded from '@mui/icons-material/FolderRounded'
import FontDownloadRounded from '@mui/icons-material/FontDownloadRounded'
import PlayArrowRounded from '@mui/icons-material/PlayArrowRounded'
import RestartAltRounded from '@mui/icons-material/RestartAltRounded'
import SaveRounded from '@mui/icons-material/SaveRounded'
import TuneRounded from '@mui/icons-material/TuneRounded'
import ForumRounded from '@mui/icons-material/ForumRounded'
import KeyboardReturnRounded from '@mui/icons-material/KeyboardReturnRounded'
import PaletteRounded from '@mui/icons-material/PaletteRounded'
import ColorLensRounded from '@mui/icons-material/ColorLensRounded'
import NotificationsRounded from '@mui/icons-material/NotificationsRounded'
import BluetoothRounded from '@mui/icons-material/BluetoothRounded'
import DeleteSweepRounded from '@mui/icons-material/DeleteSweepRounded'
import WindowRounded from '@mui/icons-material/WindowRounded'
import PowerSettingsNewRounded from '@mui/icons-material/PowerSettingsNewRounded'
import InfoRounded from '@mui/icons-material/InfoRounded'
import LinkRounded from '@mui/icons-material/LinkRounded'
import PersonRounded from '@mui/icons-material/PersonRounded'
import PublicRounded from '@mui/icons-material/PublicRounded'
import VisibilityRounded from '@mui/icons-material/VisibilityRounded'
import VisibilityOffRounded from '@mui/icons-material/VisibilityOffRounded'
import DownloadRounded from '@mui/icons-material/DownloadRounded'
import ShareRounded from '@mui/icons-material/ShareRounded'
import type { DoctorResult, FileEntry, Snapshot, ThemeMode } from '../../../shared/types'
import { PUBLIC_CONVERSATION_ID } from '../../../shared/types'
import { seedName } from '../theme'
import { describeError, formatSize } from '../model'
import logo from '../assets/logo.png'
import zsm from '../assets/zsm.png'
import PlatformIcon from './PlatformIcon'
import ShareAppDialog from './ShareAppDialog'
import ThemeDialog from './ThemeDialog'
import { useRetained } from './useRetained'

const APP_REPOS: [string, string, string][] = [
  ['X互传', 'MIT', 'https://github.com/mdhyy008/XTransfer'],
  ['xlan', 'MIT', 'https://github.com/mdhyy008/xlan']
]
const OPEN_SOURCE: [string, string][] = [
  ['Electron', 'MIT'],
  ['React', 'MIT'],
  ['MUI', 'MIT'],
  ['better-sqlite3', 'MIT'],
  ['Vite', 'MIT'],
  ['TypeScript', 'Apache-2.0'],
  ['SQLite', 'BSD-3-Clause'],
  ['gomobile', 'BSD-3-Clause']
]

type Props = {
  open: boolean
  snapshot: Snapshot | null
  preservedPaths: string[]
  accent: string
  onClose: () => void
  onSharePackage: (entry: FileEntry) => void
  notify: (text: string, severity?: 'success' | 'error' | 'warning' | 'info') => void
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Box sx={{ mb: 3 }}>
      <Typography
        variant="subtitle2"
        color="primary.main"
        sx={{ px: 1, pt: 2.5, pb: 0.5 }}
      >
        {title}
      </Typography>
      <Box sx={{ bgcolor: 'background.paper', borderRadius: '24px', px: 2, py: 0.5 }}>
        {children}
      </Box>
    </Box>
  )
}

// 收件目录以「X互传」收尾，设置里只展示末两级，比如 ..\Downloads\X互传。
function shortRecvDir(full: string): string {
  const trimmed = full.replace(/[\\/]+$/, '')
  const sep = trimmed.includes('\\') ? '\\' : '/'
  const parts = trimmed.split(/[\\/]+/).filter(Boolean)
  if (parts.length <= 2) return trimmed
  return `..${sep}${parts.slice(-2).join(sep)}`
}

function Row({
  icon,
  title,
  description,
  trailing,
  onClick
}: {
  icon: ReactNode
  title: string
  description?: string
  trailing?: ReactNode
  onClick?: () => void
}) {
  return (
    <Box
      onClick={onClick}
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 2,
        minHeight: 56,
        px: 1,
        mx: -1,
        borderRadius: '12px',
        cursor: onClick ? 'pointer' : undefined,
        '&:hover': onClick ? { bgcolor: 'action.hover' } : undefined
      }}
    >
      <Box sx={{ width: 24, display: 'grid', placeItems: 'center', color: 'text.secondary' }}>
        {icon}
      </Box>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography variant="body1" noWrap>{title}</Typography>
        {description && (
          <Typography
            variant="caption"
            component="div"
            color="text.secondary"
            title={description}
            sx={{
              fontSize: 12,
              lineHeight: 1.35,
              display: '-webkit-box',
              WebkitLineClamp: 2,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden'
            }}
          >
            {description}
          </Typography>
        )}
      </Box>
      {trailing}
    </Box>
  )
}

function GroupDivider() {
  return <Divider sx={{ ml: '48px', mr: 1 }} />
}

function OpenRow({ project, license, url }: { project: string; license: string; url?: string }) {
  return (
    <Box
      onClick={
        url
          ? () => {
              window.xt.openExternal(url).catch(() => {})
            }
          : undefined
      }
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1.5,
        py: 0.75,
        mx: -1,
        px: 1,
        borderRadius: '8px',
        cursor: url ? 'pointer' : undefined,
        '&:hover': url ? { bgcolor: 'action.hover' } : undefined
      }}
    >
      <CodeRounded fontSize="small" sx={{ color: 'text.secondary' }} />
      <Typography variant="body2" sx={{ flex: 1 }}>
        {project}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        {license}
      </Typography>
      {url && <ChevronRightRounded fontSize="small" sx={{ color: 'text.secondary' }} />}
    </Box>
  )
}

const Md3Switch = styled(Switch)(({ theme }) => ({
  width: 52,
  height: 32,
  padding: 0,
  '& .MuiSwitch-switchBase': {
    padding: 4,
    color: theme.palette.text.secondary,
    '&.Mui-checked': {
      transform: 'translateX(20px)',
      color: theme.palette.primary.contrastText,
      '& + .MuiSwitch-track': {
        backgroundColor: theme.palette.primary.main,
        borderColor: 'transparent',
        opacity: 1
      }
    },
    '&.Mui-disabled + .MuiSwitch-track': { opacity: 0.4 }
  },
  '& .MuiSwitch-thumb': {
    width: 24,
    height: 24,
    boxShadow: 'none',
    transform: 'scale(0.6667)',
    transition: theme.transitions.create('transform', {
      duration: theme.transitions.duration.shortest
    })
  },
  '& .MuiSwitch-track': {
    borderRadius: 16,
    border: `2px solid ${theme.palette.divider}`,
    boxSizing: 'border-box',
    backgroundColor: theme.palette.action.selected,
    opacity: 1
  }
}))

function ToggleSwitch({
  checked,
  onChange,
  disabled
}: {
  checked: boolean
  onChange: (checked: boolean) => void
  disabled?: boolean
}) {
  return (
    <Md3Switch
      checked={checked}
      disabled={disabled}
      onChange={(event) => onChange(event.target.checked)}
    />
  )
}

const THEMES: { value: ThemeMode; label: string }[] = [
  { value: 'system', label: '跟随系统' },
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' }
]

export default function SettingsDialog({
  open,
  snapshot,
  preservedPaths,
  accent,
  onClose,
  onSharePackage,
  notify
}: Props) {
  const [name, setName] = useState('')
  const [busy, setBusy] = useState('')
  const [message, setMessage] = useState('')
  const [doctor, setDoctor] = useState<DoctorResult | null>(null)
  // 关闭时退出过渡还没结束，内容不能立刻清空，否则会闪一下空弹窗。
  const doctorShown = useRetained(doctor)
  const [version, setVersion] = useState({ app: '', xlan: '' })
  const [cacheBytes, setCacheBytes] = useState(0)
  const [blockedOpen, setBlockedOpen] = useState(false)
  const [clearChatsOpen, setClearChatsOpen] = useState(false)
  const [clearOfflineOpen, setClearOfflineOpen] = useState(false)
  const [aboutOpen, setAboutOpen] = useState(false)
  const [shareAppOpen, setShareAppOpen] = useState(false)
  const [cacheConfirmOpen, setCacheConfirmOpen] = useState(false)
  const [themeOpen, setThemeOpen] = useState(false)
  const [feedbackOpen, setFeedbackOpen] = useState(false)
  const [feedbackQr, setFeedbackQr] = useState('')
  const [chatChecked, setChatChecked] = useState<string[]>([])
  const [bleBusy, setBleBusy] = useState(false)
  const [relayOpen, setRelayOpen] = useState(false)
  const [relayDraft, setRelayDraft] = useState('')
  const [relayTesting, setRelayTesting] = useState(false)
  const [relayTestMsg, setRelayTestMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const [relayPendingConnect, setRelayPendingConnect] = useState(false)
  const [relayPendingAddress, setRelayPendingAddress] = useState('')
  const [relayError, setRelayError] = useState<{ title: string; text: string } | null>(null)

  useEffect(() => {
    if (!feedbackOpen) return
    QRCode.toDataURL('https://pd.qq.com/s/gtvdapxfn?b=9', { width: 320, margin: 1 })
      .then(setFeedbackQr)
      .catch(() => {})
  }, [feedbackOpen])

  const settings = snapshot?.settings
  const config = snapshot?.config
  const stats = {
    sent: (snapshot?.messages ?? []).filter(
      (message) => message.direction === 'out' && message.kind !== 'text'
    ).length,
    received: (snapshot?.messages ?? []).filter(
      (message) => message.direction === 'in' && message.kind !== 'text'
    ).length,
    devices: (snapshot?.conversations ?? []).filter(
      (conversation) => conversation.id !== PUBLIC_CONVERSATION_ID
    ).length
  }

  // 只有真正存过消息的对话才有记录可清。
  const chatConversations = (() => {
    const ids = new Set((snapshot?.messages ?? []).map((m) => m.conversation_id))
    return (snapshot?.conversations ?? []).filter((conv) => ids.has(conv.id))
  })()

  const toggleBle = (on: boolean): void => {
    setBleBusy(true)
    const pending = on ? window.xt.bleStart() : window.xt.bleStop()
    pending
      .catch((error) => notify(error instanceof Error ? error.message : '蓝牙操作失败', 'error'))
      .finally(() => setBleBusy(false))
  }


  useEffect(() => {
    if (!open) {
      setThemeOpen(false)
      return
    }
    setName(config?.deviceName ?? '')
    setMessage('')
    setDoctor(null)
    window.xt
      .version()
      .then(setVersion)
      .catch(() => {})
    window.xt
      .cacheSize()
      .then((res) => setCacheBytes(res.total))
      .catch(() => {})
  }, [open, config?.deviceName])

  useEffect(() => {
    if (!relayPendingConnect) return
    const state = snapshot?.relay
    if (!state) return
    if (state.address !== relayPendingAddress) return
    if (state.connected) {
      setRelayPendingConnect(false)
      return
    }
    if (!state.connecting && state.error) {
      setRelayPendingConnect(false)
      setRelayError({ title: '连接失败', text: state.error })
    }
  }, [relayPendingConnect, relayPendingAddress, snapshot?.relay])

  const run = async (
    key: string,
    task: () => Promise<void>,
    success?: string
  ): Promise<void> => {
    if (busy) return
    setBusy(key)
    setMessage('')
    try {
      await task()
      if (success) notify(success)
    } catch (error) {
      const text = error instanceof Error ? describeError(error.message) : '操作失败'
      setMessage(text)
      notify(text, 'error')
    } finally {
      setBusy('')
    }
  }

  const doCleanCache = async (): Promise<void> => {
    setCacheConfirmOpen(false)
    await run('cache', async () => {
      const res = await window.xt.cleanCache(preservedPaths)
      const text = `已清理 ${res.parts} 个分片，释放 ${formatSize(res.freedBytes) || '0 B'}`
      setMessage(text)
      notify(text)
      window.xt
        .cacheSize()
        .then((next) => setCacheBytes(next.total))
        .catch(() => {})
    })
  }

  const patch = (value: Partial<NonNullable<typeof settings>>): void => {
    window.xt.updateSettings(value).catch(() => {})
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      slotProps={{
        paper: {
          sx: { width: 680, maxHeight: '84vh', borderRadius: '16px', bgcolor: 'background.default' }
        }
      }}
    >
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', px: 3, py: 2 }}>
        <Typography variant="subtitle1" sx={{ flex: 1, fontWeight: 600 }}>
          设置
        </Typography>
        <IconButton size="small" onClick={onClose}>
          <CloseRounded fontSize="small" />
        </IconButton>
      </DialogTitle>
      <DialogContent sx={{ px: 3, pb: 3 }}>
        <Section title="本机信息">
          <Row
            icon={<DevicesRounded fontSize="small" />}
            title="设备名"
            trailing={
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <TextField
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  slotProps={{ htmlInput: { maxLength: 15 } }}
                  sx={{ width: 220 }}
                />
                <Button
                  variant="contained"
                  startIcon={<SaveRounded />}
                  disabled={busy !== '' || name.trim() === '' || name.trim() === config?.deviceName}
                  onClick={() =>
                    void run('name', () => window.xt.setDeviceName(name.trim()), '已保存')
                  }
                >
                  保存
                </Button>
              </Box>
            }
          />
          <GroupDivider />
          <Row
            icon={<DataUsageRounded fontSize="small" />}
            title="统计"
            trailing={
              <Box sx={{ display: 'flex', gap: 3 }}>
                {[
                  { label: '发送文件', value: stats.sent },
                  { label: '接收文件', value: stats.received },
                  { label: '连接设备', value: stats.devices }
                ].map((item) => (
                  <Box key={item.label} sx={{ textAlign: 'center', minWidth: 56 }}>
                    <Typography
                      sx={{ fontSize: 16, fontWeight: 600, lineHeight: 1.25 }}
                    >
                      {item.value}
                    </Typography>
                    <Typography sx={{ fontSize: 11, color: 'text.secondary' }}>
                      {item.label}
                    </Typography>
                  </Box>
                ))}
              </Box>
            }
          />
        </Section>

        <Section title="连接">
          <Row
            icon={
              snapshot?.service.running ? (
                <CheckCircleRounded fontSize="small" sx={{ color: 'success.main' }} />
              ) : (
                <CancelRounded fontSize="small" sx={{ color: 'error.main' }} />
              )
            }
            title="xlan 服务"
            description={
              snapshot?.service.running ? '局域网发现与文件传输' : snapshot?.service.warning
            }
            trailing={
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexShrink: 0 }}>
                <Button
                  size="small"
                  variant="contained"
                  color="secondary"
                  startIcon={<PlayArrowRounded />}
                  disabled={busy !== ''}
                  onClick={() =>
                    void run('doctor', async () => setDoctor(await window.xt.runDoctor()))
                  }
                >
                  {busy === 'doctor' ? '检测中' : '自检'}
                </Button>
                <Button
                  size="small"
                  variant="contained"
                  color="secondary"
                  startIcon={<RestartAltRounded />}
                  disabled={busy !== ''}
                  onClick={() =>
                    void run('service', () => window.xt.restartService(), '服务已重启')
                  }
                >
                  重启
                </Button>
              </Box>
            }
          />
          {snapshot?.bluetooth.supported && (
            <>
              <GroupDivider />
              <Row
                icon={<BluetoothRounded fontSize="small" />}
                title="蓝牙广播"
                description={`无局域网时通过蓝牙发现附近设备 · 蓝牙 ${snapshot?.bluetooth.version || '未知'}${snapshot?.bluetooth.address ? ` · MAC ${snapshot.bluetooth.address}` : ''}`}
                trailing={
                  <ToggleSwitch
                    checked={snapshot?.bluetooth.on === true}
                    disabled={bleBusy}
                    onChange={toggleBle}
                  />
                }
              />
            </>
          )}
          <GroupDivider />
          <Row
            icon={<PublicRounded fontSize="small" />}
            title="中继服务器"
            description={`不在同一局域网时通过中继收发消息 · ${snapshot?.relay.connected ? '已连接' : '未连接'}`}
            trailing={
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexShrink: 0 }}>
                <Button
                  size="small"
                  variant="contained"
                  color="secondary"
                  startIcon={<DeleteSweepRounded />}
                  disabled={snapshot?.relay.connected !== true}
                  onClick={() => {
                    setRelayDraft('')
                    patch({ relayUrl: '' })
                    notify('已清除')
                  }}
                >
                  清除
                </Button>
                <Button
                  size="small"
                  variant="contained"
                  color="secondary"
                  startIcon={<LinkRounded />}
                  onClick={() => {
                    setRelayDraft(settings?.relayUrl ?? '')
                    setRelayTestMsg(null)
                    setRelayOpen(true)
                  }}
                >
                  连接
                </Button>
              </Box>
            }
          />
        </Section>

        <Section title="外观">
          <Row
            icon={<PaletteRounded fontSize="small" />}
            title="主题模式"
            description="跟随系统，或固定使用浅色 / 深色"
            trailing={
              <ToggleButtonGroup
                size="small"
                exclusive
                value={settings?.theme ?? 'system'}
                onChange={(_, value) => {
                  if (value) patch({ theme: value as ThemeMode })
                }}
              >
                {THEMES.map((item) => (
                  <ToggleButton
                    key={item.value}
                    value={item.value}
                    sx={{ textTransform: 'none', px: 2 }}
                  >
                    {item.label}
                  </ToggleButton>
                ))}
              </ToggleButtonGroup>
            }
          />
          <GroupDivider />
          <Row
            icon={<ColorLensRounded fontSize="small" />}
            title="主题色"
            description="应用整体的配色方案"
            onClick={() => setThemeOpen(true)}
            trailing={
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                <Typography variant="body2" color="text.secondary">
                  {seedName(settings?.seed ?? 'green')}
                </Typography>
                <ChevronRightRounded fontSize="small" sx={{ color: 'text.secondary' }} />
              </Box>
            }
          />
          <GroupDivider />
          <Row
            icon={<FontDownloadRounded fontSize="small" />}
            title="MiSans 字体"
            description="使用小米 MiSans 字体显示界面文字"
            trailing={
              <ToggleSwitch
                checked={settings?.misans ?? true}
                onChange={(checked) => patch({ misans: checked })}
              />
            }
          />
        </Section>

        <Section title="通用">
          <Row
            icon={<NotificationsRounded fontSize="small" />}
            title="系统通知"
            description="收到文件或消息时在系统通知中心提醒"
            trailing={
              <ToggleSwitch
                checked={settings?.notifications ?? true}
                onChange={(checked) => patch({ notifications: checked })}
              />
            }
          />
          <GroupDivider />
          <Row
            icon={<KeyboardReturnRounded fontSize="small" />}
            title="回车发送"
            description="按回车发送消息，Ctrl + 回车换行"
            trailing={
              <ToggleSwitch
                checked={settings?.enterToSend ?? true}
                onChange={(checked) => patch({ enterToSend: checked })}
              />
            }
          />
          <GroupDivider />
          <Row
            icon={<VisibilityRounded fontSize="small" />}
            title="快速预览"
            description="点击文件先在应用内预览，不用外部程序打开"
            trailing={
              <ToggleSwitch
                checked={settings?.quickPreview ?? true}
                onChange={(checked) => patch({ quickPreview: checked })}
              />
            }
          />
          <GroupDivider />
          <Row
            icon={<DownloadRounded fontSize="small" />}
            title="自动接收所有文件"
            description="收到文件自动保存到收件目录，不再逐个确认"
            trailing={
              <ToggleSwitch
                checked={settings?.autoAcceptAll ?? false}
                onChange={(checked) => patch({ autoAcceptAll: checked })}
              />
            }
          />
          <GroupDivider />
          <Row
            icon={<VisibilityOffRounded fontSize="small" />}
            title="隐藏离线设备"
            description="设备列表只显示在线的设备"
            trailing={
              <ToggleSwitch
                checked={settings?.hideOffline ?? true}
                onChange={(checked) => patch({ hideOffline: checked })}
              />
            }
          />
          <GroupDivider />
          <Row
            icon={<DragIndicatorRounded fontSize="small" />}
            title="拖入暂存桶（Beta）"
            description="屏幕右上角显示拖入区域，拖文件上去可直接放入暂存桶"
            trailing={
              <ToggleSwitch
                checked={settings?.dropPad ?? false}
                onChange={(checked) => patch({ dropPad: checked })}
              />
            }
          />
        </Section>

        <Section title="数据">
          <Row
            icon={<FolderRounded fontSize="small" />}
            title="收件目录"
            description={config?.recvDir ? shortRecvDir(config.recvDir) : undefined}
            trailing={
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexShrink: 0 }}>
                <Button
                  size="small"
                  variant="contained"
                  color="secondary"
                  startIcon={<FolderOpenRounded />}
                  disabled={busy !== ''}
                  onClick={() =>
                    void run('recv', async () => {
                      const dir = await window.xt.pickRecvDir()
                      if (dir) notify('已保存')
                    })
                  }
                >
                  更换
                </Button>
                <Button
                  size="small"
                  variant="outlined"
                  disabled={busy !== ''}
                  onClick={() =>
                    void run(
                      'recvReset',
                      async () => {
                        await window.xt.resetRecvDir()
                      },
                      '已恢复默认'
                    )
                  }
                >
                  恢复默认
                </Button>
              </Box>
            }
          />
          <GroupDivider />
          <Row
            icon={<BlockRounded fontSize="small" />}
            title="设备黑名单"
            description={
              (settings?.blocked ?? []).length > 0
                ? `已拉黑 ${(settings?.blocked ?? []).length} 台设备，不再显示它们的消息`
                : '拉黑后不再显示该设备的会话'
            }
            trailing={
              <Button
                size="small"
                variant="outlined"
                startIcon={<TuneRounded />}
                onClick={() => setBlockedOpen(true)}
              >
                管理
              </Button>
            }
          />
          <GroupDivider />
          <Row
            icon={<DeleteSweepRounded fontSize="small" />}
            title="清理缓存"
            description={`临时文件、缩略图与中继缓存 · 当前 ${formatSize(cacheBytes) || '0 B'}`}
            trailing={
              <Button
                size="small"
                variant="outlined"
                disabled={busy !== ''}
                onClick={() => setCacheConfirmOpen(true)}
              >
                清理
              </Button>
            }
          />
          <GroupDivider />
          <Row
            icon={<ForumRounded fontSize="small" />}
            title="清理聊天记录"
            description="删除选定设备的聊天记录，不影响设备列表"
            trailing={
              <Button
                size="small"
                variant="outlined"
                disabled={busy !== ''}
                onClick={() => {
                  setChatChecked([])
                  setClearChatsOpen(true)
                }}
              >
                清理
              </Button>
            }
          />
          <GroupDivider />
          <Row
            icon={<DevicesRounded fontSize="small" />}
            title="清理离线设备"
            description="删除所有离线设备及其聊天记录"
            trailing={
              <Button
                size="small"
                variant="outlined"
                disabled={busy !== ''}
                onClick={() => setClearOfflineOpen(true)}
              >
                清理
              </Button>
            }
          />
        </Section>

        <Section title="窗口">
          <Row
            icon={<WindowRounded fontSize="small" />}
            title="关闭到托盘"
            description="点关闭按钮时留在托盘后台继续接收文件"
            trailing={
              <ToggleSwitch
                checked={settings?.closeToTray ?? true}
                onChange={(checked) => patch({ closeToTray: checked })}
              />
            }
          />
          <GroupDivider />
          <Row
            icon={<PowerSettingsNewRounded fontSize="small" />}
            title="开机自启"
            description="开机后自动在后台启动，随时可以接收文件"
            trailing={
              <ToggleSwitch
                checked={config?.autostart ?? false}
                onChange={(checked) =>
                  void run(
                    'autostart',
                    () => window.xt.setAutostart(checked),
                    checked ? '已开启' : '已关闭'
                  )
                }
              />
            }
          />
        </Section>

        <Section title="其他">
          <Row
            icon={<DescriptionRounded fontSize="small" />}
            title="运行日志"
            description="查看软件的运行日志，排查问题时使用"
            onClick={() => {
              window.xt.openLogWindow().catch(() => {})
            }}
            trailing={<ChevronRightRounded fontSize="small" sx={{ color: 'text.secondary' }} />}
          />
        </Section>

        <Section title="关于">
          <Row
            icon={<FeedbackRounded fontSize="small" />}
            title="建议与反馈"
            onClick={() => setFeedbackOpen(true)}
            trailing={<ChevronRightRounded fontSize="small" sx={{ color: 'text.secondary' }} />}
          />
          <GroupDivider />
          <Row
            icon={<LinkRounded fontSize="small" />}
            title="软件发布地址"
            onClick={() => {
              window.xt.openExternal('https://github.com/mdhyy008/XTransfer/releases').catch(() => {})
            }}
            trailing={<ChevronRightRounded fontSize="small" sx={{ color: 'text.secondary' }} />}
          />
          <GroupDivider />
          <Row
            icon={<InfoRounded fontSize="small" />}
            title="关于"
            onClick={() => setAboutOpen(true)}
            trailing={
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                <Typography variant="body2" color="text.secondary">
                  {version.app}
                </Typography>
                <ChevronRightRounded fontSize="small" sx={{ color: 'text.secondary' }} />
              </Box>
            }
          />
        </Section>

        {message && (
          <Typography
            variant="body2"
            color="text.secondary"
            data-selectable="true"
            sx={{ px: 1, pb: 1, userSelect: 'text', whiteSpace: 'pre-wrap', cursor: 'text' }}
          >
            {message}
          </Typography>
        )}
      </DialogContent>

      <ThemeDialog
        open={themeOpen}
        seed={settings?.seed ?? 'green'}
        accent={accent}
        onPick={(next) => patch({ seed: next })}
        onClose={() => setThemeOpen(false)}
      />

      <Dialog open={relayOpen} onClose={() => setRelayOpen(false)}>
        <DialogTitle sx={{ fontSize: 18 }}>中继服务器</DialogTitle>
        <DialogContent sx={{ minWidth: 380 }}>
          <TextField
            autoFocus
            fullWidth
            value={relayDraft}
            onChange={(event) => {
              setRelayDraft(event.target.value)
              setRelayTestMsg(null)
            }}
            label="服务器地址"
            sx={{ mt: 1 }}
          />
          {relayTestMsg && (
            <Typography
              variant="body2"
              sx={{ mt: 1.5, color: relayTestMsg.ok ? 'success.main' : 'error.main' }}
            >
              {relayTestMsg.text}
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button
            disabled={relayTesting || !relayDraft.trim()}
            onClick={() => {
              setRelayTesting(true)
              setRelayTestMsg(null)
              window.xt
                .relayTest(relayDraft.trim())
                .then((result) => {
                  if (result.ok) {
                    setRelayTestMsg({ ok: true, text: '测试成功' })
                  } else {
                    setRelayTestMsg(null)
                    setRelayError({ title: '测试失败', text: result.error || '地址无效' })
                  }
                })
                .catch(() => {
                  setRelayTestMsg(null)
                  setRelayError({ title: '测试失败', text: '地址无效' })
                })
                .finally(() => setRelayTesting(false))
            }}
          >
            {relayTesting ? '测试中' : '测试'}
          </Button>
          <Button
            variant="contained"
            disabled={!relayDraft.trim()}
            onClick={() => {
              const next = relayDraft.trim()
              patch({ relayUrl: next })
              setRelayPendingAddress(next.replace(/\/+$/, ''))
              setRelayPendingConnect(true)
              setRelayOpen(false)
              notify('已保存')
            }}
          >
            保存
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={relayError !== null} onClose={() => setRelayError(null)}>
        <DialogTitle sx={{ fontSize: 18 }}>{relayError?.title}</DialogTitle>
        <DialogContent sx={{ minWidth: 340 }}>
          <Typography
            variant="body2"
            data-selectable="true"
            sx={{
              userSelect: 'text',
              whiteSpace: 'pre-wrap',
              cursor: 'text',
              wordBreak: 'break-all'
            }}
          >
            {relayError?.text}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRelayError(null)}>确定</Button>
        </DialogActions>
      </Dialog>

      <Dialog open={blockedOpen} onClose={() => setBlockedOpen(false)}>
        <DialogTitle>设备黑名单</DialogTitle>
        <DialogContent sx={{ minWidth: 360 }}>
          {(settings?.blocked ?? []).length === 0 ? (
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>暂无</Typography>
          ) : (
            (settings?.blocked ?? []).map((id, index) => {
              const blockedPeer = snapshot?.peers.find((peer) => peer.id === id)
              return (
                <Box key={id}>
                  {index > 0 && <Divider />}
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 1 }}>
                    <Box
                      sx={{
                        width: 36,
                        height: 36,
                        flexShrink: 0,
                        borderRadius: '12px',
                        bgcolor: 'secondary.main',
                        color: 'secondary.contrastText',
                        display: 'grid',
                        placeItems: 'center'
                      }}
                    >
                      <PlatformIcon platform={blockedPeer?.platform} />
                    </Box>
                    <Typography sx={{ flex: 1, fontSize: 13, wordBreak: 'break-all' }}>
                      {blockedPeer?.name || id}
                    </Typography>
                    <Button
                      size="small"
                      onClick={() =>
                        patch({
                          blocked: (settings?.blocked ?? []).filter((item) => item !== id)
                        })
                      }
                    >
                      移除
                    </Button>
                  </Box>
                </Box>
              )
            })
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setBlockedOpen(false)}>关闭</Button>
        </DialogActions>
      </Dialog>

      <Dialog open={clearChatsOpen} onClose={() => setClearChatsOpen(false)}>
        <DialogTitle sx={{ fontSize: 18 }}>清理聊天记录</DialogTitle>
        <DialogContent sx={{ minWidth: 380 }}>
          {chatConversations.length === 0 ? (
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>暂无聊天记录</Typography>
          ) : (
            chatConversations.map((conv) => (
              <Box
                key={conv.id}
                component="label"
                sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 0.25, cursor: 'pointer' }}
              >
                <Checkbox
                  size="small"
                  checked={chatChecked.includes(conv.id)}
                  onChange={(event) =>
                    setChatChecked((prev) =>
                      event.target.checked
                        ? [...prev, conv.id]
                        : prev.filter((id) => id !== conv.id)
                    )
                  }
                />
                <Typography sx={{ fontSize: 13, flex: 1, wordBreak: 'break-all' }}>
                  {conv.name}
                </Typography>
              </Box>
            ))
          )}
        </DialogContent>
        <DialogActions>
          <Button
            color="error"
            disabled={busy !== '' || chatChecked.length === 0}
            onClick={() =>
              void run('chats', async () => {
                for (const id of chatChecked) await window.xt.clearConversation(id)
                setClearChatsOpen(false)
                notify(`已清理 ${chatChecked.length} 个对话`)
              })
            }
          >
            删除
          </Button>
          <Button onClick={() => setClearChatsOpen(false)}>取消</Button>
        </DialogActions>
      </Dialog>

      <Dialog open={clearOfflineOpen} onClose={() => setClearOfflineOpen(false)}>
        <DialogTitle sx={{ fontSize: 18 }}>清理离线设备</DialogTitle>
        <DialogContent sx={{ minWidth: 380 }}>
          <Typography sx={{ fontSize: 14, color: 'text.secondary' }}>
            将删除所有离线设备的会话和聊天记录。
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setClearOfflineOpen(false)}>取消</Button>
          <Button
            color="error"
            disabled={busy !== ''}
            onClick={() =>
              void run('offline', async () => {
                const count = await window.xt.deleteOfflineDevices()
                setClearOfflineOpen(false)
                notify(count > 0 ? `已清理 ${count} 个离线设备` : '没有离线设备')
              })
            }
          >
            清理
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={feedbackOpen} onClose={() => setFeedbackOpen(false)}>
        <DialogTitle sx={{ fontSize: 18 }}>建议与反馈</DialogTitle>
        <DialogContent sx={{ minWidth: 360 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
            腾讯频道（QQ 频道）
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            反馈使用中遇到的问题、提出功能建议，新版本也会在频道里发布。
          </Typography>
          <Box sx={{ mt: 1.5 }}>
            <Typography variant="body2">频道号：pd40502482</Typography>
            <Typography variant="body2" color="text.secondary" sx={{ fontSize: 12 }}>
              频道链接：pd.qq.com/s/gtvdapxfn
            </Typography>
          </Box>
          {feedbackQr && (
            <Box sx={{ mt: 2, display: 'flex', justifyContent: 'center' }}>
              <Box
                component="img"
                src={feedbackQr}
                alt="频道二维码"
                sx={{ width: 160, height: 160, borderRadius: 2 }}
              />
            </Box>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setFeedbackOpen(false)}>关闭</Button>
          <Button
            variant="contained"
            onClick={() => {
              window.xt
                .openExternal('https://pd.qq.com/s/gtvdapxfn?b=9')
                .then(() => setFeedbackOpen(false))
                .catch(() => {})
            }}
          >
            加入频道
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={aboutOpen} onClose={() => setAboutOpen(false)}>
        <DialogTitle sx={{ fontSize: 18 }}>关于</DialogTitle>
        <DialogContent sx={{ minWidth: 360 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, pb: 1.5 }}>
            <Box
              component="img"
              src={logo}
              alt="X互传"
              sx={{ width: 64, height: 64, borderRadius: '16px' }}
            />
            <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
              X互传
            </Typography>
          </Box>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 1 }}>
            <InfoRounded fontSize="small" sx={{ color: 'text.secondary' }} />
            <Typography variant="body2" sx={{ flex: 1 }}>
              版本
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {version.app} / xlan {version.xlan}
            </Typography>
          </Box>
          <Divider />
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 1 }}>
            <PersonRounded fontSize="small" sx={{ color: 'text.secondary' }} />
            <Typography variant="body2" sx={{ flex: 1 }}>
              开发者
            </Typography>
            <Typography variant="body2" color="text.secondary">
              明灯花月夜
            </Typography>
          </Box>
          <Divider />
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 1 }}>
            <LinkRounded fontSize="small" sx={{ color: 'text.secondary' }} />
            <Typography variant="body2" sx={{ flexShrink: 0, whiteSpace: 'nowrap' }}>
              软件官网
            </Typography>
            <Typography
              variant="body2"
              color="text.secondary"
              sx={{
                flex: 1,
                minWidth: 0,
                ml: 1,
                textAlign: 'right',
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                cursor: 'pointer'
              }}
              onClick={() => {
                window.xt.openExternal('https://mdhyy.cn/tools/page/x-transfer/').catch(() => {})
              }}
            >
              mdhyy.cn/tools/page/x-transfer
            </Typography>
          </Box>
          <Divider />
          <Box
            onClick={() => {
              setAboutOpen(false)
              setShareAppOpen(true)
            }}
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 1.5,
              py: 1,
              mt: 0.5,
              mx: -1,
              px: 1,
              borderRadius: '8px',
              cursor: 'pointer',
              '&:hover': { bgcolor: 'action.hover' }
            }}
          >
            <ShareRounded fontSize="small" sx={{ color: 'text.secondary' }} />
            <Typography variant="body2" sx={{ flex: 1 }}>
              分享本软件
            </Typography>
            <ChevronRightRounded fontSize="small" sx={{ color: 'text.secondary' }} />
          </Box>
          <Typography
            variant="caption"
            color="primary"
            sx={{ display: 'block', pt: 1.5, pb: 0.5 }}
          >
            本软件开源地址
          </Typography>
          {APP_REPOS.map(([project, license, url]) => (
            <OpenRow key={project} project={project} license={license} url={url} />
          ))}
          <Typography
            variant="caption"
            color="primary"
            sx={{ display: 'block', pt: 1.5, pb: 0.5 }}
          >
            引用的开源项目
          </Typography>
          {OPEN_SOURCE.map(([project, license]) => (
            <OpenRow key={project} project={project} license={license} />
          ))}
          <Divider sx={{ mt: 1.5 }} />
          <Typography variant="body2" sx={{ display: 'block', pt: 1.5, pb: 1 }}>
            微信赞赏
          </Typography>
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ display: 'block', mb: 1, textAlign: 'center' }}
          >
            微信扫码
          </Typography>
          <Box
            component="img"
            src={zsm}
            alt="微信赞赏"
            sx={{ display: 'block', width: 180, height: 180, mx: 'auto', borderRadius: 2 }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAboutOpen(false)}>关闭</Button>
        </DialogActions>
      </Dialog>

      <Dialog open={cacheConfirmOpen} onClose={() => setCacheConfirmOpen(false)}>
        <DialogTitle sx={{ fontSize: 18 }}>清理缓存</DialogTitle>
        <DialogContent sx={{ minWidth: 380 }}>
          <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.5 }}>
            将清除
          </Typography>
          <Typography variant="body2" color="text.secondary">
            临时文件与传输临时片、缩略图缓存、中继缓存，以及服务器上 30 天前的传输记录。
          </Typography>
          <Typography variant="body2" sx={{ fontWeight: 600, mt: 1.5, mb: 0.5 }}>
            会保留
          </Typography>
          <Typography variant="body2" color="text.secondary">
            暂存桶里的文件、已收到的文件。
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setCacheConfirmOpen(false)}>取消</Button>
          <Button variant="contained" disabled={busy !== ''} onClick={() => void doCleanCache()}>
            清理
          </Button>
        </DialogActions>
      </Dialog>

      <ShareAppDialog
        open={shareAppOpen}
        onClose={() => setShareAppOpen(false)}
        onSharePackage={onSharePackage}
        notify={notify}
      />

      <Dialog open={doctor !== null} onClose={() => setDoctor(null)}>
        <DialogTitle sx={{ fontSize: 18 }}>自检</DialogTitle>
        <DialogContent sx={{ minWidth: 380 }}>
          {doctorShown && doctorShown.checks.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              {doctorShown.ok ? '正常' : '未通过'}
            </Typography>
          ) : (
            doctorShown?.checks.map((check) => (
              <Box key={check.name} sx={{ display: 'flex', gap: 1, py: 0.5 }}>
                <Typography variant="body2" sx={{ width: 90, color: 'text.secondary' }}>
                  {check.name}
                </Typography>
                <Typography
                  variant="body2"
                  sx={{ flex: 1, wordBreak: 'break-all' }}
                  color={check.ok ? 'text.primary' : 'error.main'}
                >
                  {check.ok ? check.detail || '正常' : check.detail || '失败'}
                </Typography>
              </Box>
            ))
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDoctor(null)}>关闭</Button>
        </DialogActions>
      </Dialog>
    </Dialog>
  )
}
