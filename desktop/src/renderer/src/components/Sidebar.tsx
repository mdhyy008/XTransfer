import { useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  Avatar,
  Badge,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  IconButton,
  InputAdornment,
  List,
  ListItemButton,
  Menu,
  MenuItem,
  Switch,
  TextField,
  Tooltip,
  Typography,
  useTheme
} from '@mui/material'
import { alpha } from '@mui/material/styles'
import AddRounded from '@mui/icons-material/AddRounded'
import BluetoothRounded from '@mui/icons-material/BluetoothRounded'
import CampaignRounded from '@mui/icons-material/CampaignRounded'
import ForumRounded from '@mui/icons-material/ForumRounded'
import PublicRounded from '@mui/icons-material/PublicRounded'
import RefreshRounded from '@mui/icons-material/RefreshRounded'
import SearchRounded from '@mui/icons-material/SearchRounded'
import WifiOffRounded from '@mui/icons-material/WifiOffRounded'
import type {
  BleState,
  Conversation,
  Message,
  Peer,
  RelayState,
  Settings
} from '../../../shared/types'
import { PUBLIC_CONVERSATION_ID } from '../../../shared/types'
import { formatSize, formatWhen, lastMessageMap, previewOf } from '../model'
import PlatformIcon from './PlatformIcon'
import { useRetained } from './useRetained'
import { useRightClickDismiss } from './useRightClickDismiss'

type Props = {
  conversations: Conversation[]
  messages: Message[]
  peers: Peer[]
  selectedId: string
  onSelect: (id: string) => void
  onScan: () => void
  scanning: boolean
  serviceRunning: boolean
  settings?: Settings
  bluetooth?: BleState
  relay?: RelayState
  notify: (text: string, severity?: 'success' | 'error' | 'warning' | 'info') => void
  lanOffline: boolean
  onOpenConnections: () => void
}

const BLE_PREFIX = 'ble:'
const RELAY_PREFIX = 'relay:'

function ConvAvatar({ conv }: { conv: Conversation }) {
  const theme = useTheme()
  const relayRoom = conv.id === `${RELAY_PREFIX}public`
  const relay = conv.id.startsWith(RELAY_PREFIX)
  const avatar =
    relay ? (
      <Badge
        overlap="circular"
        variant="dot"
        invisible={!conv.online}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        sx={{
          '& .MuiBadge-badge': {
            bgcolor: 'success.main',
            boxShadow: `0 0 0 2px ${theme.palette.background.default}`
          }
        }}
      >
        <Avatar
          variant="rounded"
          sx={{
            width: 40,
            height: 40,
            borderRadius: '14px',
            bgcolor: 'secondary.main',
            color: 'secondary.contrastText'
          }}
        >
          {relayRoom ? <PublicRounded fontSize="small" /> : <ForumRounded fontSize="small" />}
        </Avatar>
      </Badge>
    ) : conv.id === PUBLIC_CONVERSATION_ID ? (
      <Avatar
        variant="rounded"
        sx={{
          width: 40,
          height: 40,
          borderRadius: '14px',
          bgcolor: 'primary.main',
          color: 'primary.contrastText'
        }}
      >
        <CampaignRounded fontSize="small" />
      </Avatar>
    ) : (
      <Badge
        overlap="circular"
        variant="dot"
        invisible={!conv.online}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        sx={{
          '& .MuiBadge-badge': {
            bgcolor: 'success.main',
            boxShadow: `0 0 0 2px ${theme.palette.background.default}`
          }
        }}
      >
        <Avatar
          variant="rounded"
          sx={{
            width: 40,
            height: 40,
            borderRadius: '14px',
            bgcolor: 'secondary.main',
            color: 'secondary.contrastText'
          }}
        >
          <PlatformIcon platform={conv.platform} />
        </Avatar>
      </Badge>
    )

  return avatar
}

// 行尾徽章：传输方式（#IP 末段 / BLE）和未读数合成一个，例如 BLE·3。
function RowBadge({ label, count }: { label?: string; count: number }) {
  if (!label && count <= 0) return null
  const text = label
    ? count > 0
      ? `${label}·${count > 99 ? '99+' : count}`
      : label
    : `${count > 99 ? '99+' : count}`
  const unread = count > 0
  return (
    <Box
      sx={{
        minWidth: 20,
        height: 20,
        px: 0.8,
        borderRadius: '10px',
        bgcolor: unread ? 'primary.main' : 'action.hover',
        color: unread ? 'primary.contrastText' : 'text.secondary',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontSize: 11,
        fontWeight: 600,
        flexShrink: 0,
        whiteSpace: 'nowrap'
      }}
    >
      {text}
    </Box>
  )
}

function ConvRow({
  conv,
  last,
  badge,
  subtitle,
  selected,
  onSelect,
  onContextMenu
}: {
  conv: Conversation
  last?: Message
  badge?: string
  subtitle?: string
  selected: boolean
  onSelect: () => void
  onContextMenu: (event: React.MouseEvent) => void
}) {
  const theme = useTheme()
  // 选中底色用主题色的半透明叠加，比头像的淡容器色明显更深，避免糊成一片。
  const selectedBg = alpha(theme.palette.primary.main, theme.palette.mode === 'light' ? 0.32 : 0.4)
  const selectedHover = alpha(
    theme.palette.primary.main,
    theme.palette.mode === 'light' ? 0.4 : 0.48
  )
  return (
    <ListItemButton
      selected={selected}
      disableRipple
      onClick={onSelect}
      onContextMenu={onContextMenu}
      sx={{
        borderRadius: '16px',
        mx: 1,
        px: 1.5,
        py: 1,
        gap: 1.5,
        minHeight: 60,
        overflow: 'hidden',
        transition: 'background-color 140ms ease',
        '&:active': { bgcolor: selected ? selectedHover : 'action.selected' },
        '&.Mui-selected': {
          bgcolor: selectedBg,
          color: 'text.primary',
          '&:hover': { bgcolor: selectedHover }
        }
      }}
    >
      <ConvAvatar conv={conv} />
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1 }}>
          <Typography noWrap sx={{ flex: 1, fontWeight: 500, fontSize: 14 }}>
            {conv.name}
            {conv.originalName && (
              <Box
                component="span"
                sx={{
                  fontWeight: 400,
                  color: selected ? 'inherit' : 'text.secondary',
                  opacity: 0.7
                }}
              >
                {`（${conv.originalName}）`}
              </Box>
            )}
          </Typography>
        </Box>
        <Typography
          noWrap
          sx={{
            fontSize: 12.5,
            color: selected ? 'inherit' : 'text.secondary',
            opacity: selected ? 0.85 : 1
          }}
        >
          {previewOf(last) ||
            subtitle ||
            (conv.id === PUBLIC_CONVERSATION_ID ? '公共频道' : conv.online ? '在线' : '离线')}
        </Typography>
      </Box>
      <RowBadge label={badge} count={conv.unread} />
    </ListItemButton>
  )
}

function BleRow({
  name,
  originalName,
  connected,
  preview,
  unread,
  selected,
  onSelect,
  onContextMenu
}: {
  name: string
  originalName?: string
  connected: boolean
  preview?: string
  unread: number
  selected: boolean
  onSelect: () => void
  onContextMenu: (event: React.MouseEvent) => void
}) {
  const theme = useTheme()
  const selectedBg = alpha(theme.palette.primary.main, theme.palette.mode === 'light' ? 0.32 : 0.4)
  return (
    <ListItemButton
      selected={selected}
      disableRipple
      onClick={onSelect}
      onContextMenu={onContextMenu}
      sx={{
        borderRadius: '16px',
        mx: 1,
        px: 1.5,
        py: 1,
        gap: 1.5,
        minHeight: 60,
        overflow: 'hidden',
        transition: 'background-color 140ms ease',
        '&.Mui-selected': { bgcolor: selectedBg, color: 'text.primary' }
      }}
    >
      <Badge
        overlap="circular"
        variant="dot"
        invisible={!connected}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        sx={{
          '& .MuiBadge-badge': {
            bgcolor: 'success.main',
            boxShadow: `0 0 0 2px ${theme.palette.background.default}`
          }
        }}
      >
        <Avatar
          variant="rounded"
          sx={{ width: 40, height: 40, borderRadius: '14px', bgcolor: 'secondary.main', color: 'secondary.contrastText' }}
        >
          <BluetoothRounded fontSize="small" />
        </Avatar>
      </Badge>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography noWrap sx={{ fontWeight: 500, fontSize: 14 }}>
          {name}
          {originalName && (
            <Box
              component="span"
              sx={{ fontWeight: 400, color: 'text.secondary', opacity: 0.7 }}
            >
              {`（${originalName}）`}
            </Box>
          )}
        </Typography>
        <Typography noWrap sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          {preview || (connected ? '已连接' : '未连接')}
        </Typography>
      </Box>
      <RowBadge label="BLE" count={unread} />
    </ListItemButton>
  )
}

function Section({
  title,
  action,
  children
}: {
  title: string
  action?: ReactNode
  children: ReactNode
}) {
  return (
    <Box sx={{ mt: 1.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', px: 2.5, pb: 0.5 }}>
        <Typography
          sx={{
            flex: 1,
            fontSize: 11.5,
            fontWeight: 600,
            letterSpacing: 0.6,
            color: 'text.secondary'
          }}
        >
          {title}
        </Typography>
        {action}
      </Box>
      <List disablePadding>{children}</List>
    </Box>
  )
}

export default function Sidebar({
  conversations,
  messages,
  peers,
  selectedId,
  onSelect,
  onScan,
  scanning,
  serviceRunning,
  settings,
  bluetooth,
  relay,
  notify,
  lanOffline,
  onOpenConnections
}: Props) {
  const [query, setQuery] = useState('')
  const [roomOpen, setRoomOpen] = useState(false)
  const [roomName, setRoomName] = useState('')
  const [roomCode, setRoomCode] = useState('')
  const [roomBusy, setRoomBusy] = useState(false)
  const [menu, setMenu] = useState<{ x: number; y: number; conv: Conversation } | null>(null)
  const [pendingClear, setPendingClear] = useState<Conversation | null>(null)
  const [pendingDelete, setPendingDelete] = useState<Conversation | null>(null)
  const [pendingInfo, setPendingInfo] = useState<Conversation | null>(null)
  const [aliasTarget, setAliasTarget] = useState<Conversation | null>(null)
  const [aliasDraft, setAliasDraft] = useState('')
  const [testing, setTesting] = useState<string | null>(null)
  const [testAvg, setTestAvg] = useState<Record<string, number>>({})
  const [liveSample, setLiveSample] = useState<{ id: string; value: number } | null>(null)
  useEffect(
    () =>
      window.xt.onSpeedProgress((progress) =>
        setLiveSample({ id: progress.id, value: progress.sample })
      ),
    []
  )
  useRightClickDismiss(menu !== null, () => setMenu(null))
  const menuShown = useRetained(menu)
  const menuIsBle = menuShown?.conv.id.startsWith(BLE_PREFIX) ?? false
  const menuIsRelay = menuShown?.conv.id.startsWith(RELAY_PREFIX) ?? false
  const menuRelayPublic = menuShown?.conv.id === `${RELAY_PREFIX}public`
  const clearShown = useRetained(pendingClear)
  const deleteShown = useRetained(pendingDelete)
  const infoShown = useRetained(pendingInfo)
  const lastByConv = useMemo(() => lastMessageMap(messages), [messages])
  const addrById = useMemo(
    () => new Map(peers.map((peer) => [peer.id, peer.addr ?? ''])),
    [peers]
  )
  const badgeFor = (conv: Conversation): string | undefined => {
    if (conv.id === PUBLIC_CONVERSATION_ID || conv.id.startsWith(RELAY_PREFIX)) return undefined
    const segment = addrById.get(conv.id)?.split('.').pop()
    return segment ? `#${segment}` : undefined
  }
  const peakByConv = useMemo(() => {
    const map = new Map<string, number>()
    for (const message of messages) {
      const speed = message.speed_bps || 0
      if (speed > (map.get(message.conversation_id) ?? 0)) map.set(message.conversation_id, speed)
    }
    return map
  }, [messages])

  const blocked = settings?.blocked ?? []
  const muted = settings?.muted ?? []
  const pinnedIds = settings?.pinned ?? []
  const aliases = settings?.aliases ?? {}

  const patchSettings = (patch: Partial<Settings>, success?: string): void => {
    window.xt
      .updateSettings(patch)
      .then(() => {
        if (success) notify(success)
      })
      .catch((error) => notify(error instanceof Error ? error.message : '操作失败', 'error'))
  }

  const toggleMember = (list: string[], id: string, on: boolean): string[] =>
    on ? (list.includes(id) ? list : [...list, id]) : list.filter((item) => item !== id)

  const openMenu = (event: React.MouseEvent, conv: Conversation): void => {
    event.preventDefault()
    // 局域网广播没有设备类操作，操作都在聊天页右上角。
    if (conv.id === PUBLIC_CONVERSATION_ID) return
    setMenu({ x: event.clientX, y: event.clientY, conv })
  }

  const createRoom = async (): Promise<void> => {
    const name = roomName.trim()
    if (!name || roomBusy) return
    setRoomBusy(true)
    try {
      const roomId = await window.xt.relayCreate(name)
      setRoomOpen(false)
      setRoomName('')
      onSelect(`${RELAY_PREFIX}${roomId}`)
    } catch (error) {
      notify(error instanceof Error ? error.message : '创建失败', 'error')
    } finally {
      setRoomBusy(false)
    }
  }

  const joinRoom = async (): Promise<void> => {
    const code = roomCode.trim()
    if (!/^\d{6}$/.test(code) || roomBusy) {
      notify('房间号是六位数字', 'warning')
      return
    }
    setRoomBusy(true)
    try {
      const roomId = await window.xt.relayJoin(code)
      setRoomOpen(false)
      setRoomCode('')
      onSelect(`${RELAY_PREFIX}${roomId}`)
    } catch (error) {
      notify(error instanceof Error ? error.message : '加入失败', 'error')
    } finally {
      setRoomBusy(false)
    }
  }

  const leaveRoom = async (conv: Conversation): Promise<void> => {
    try {
      await window.xt.relayLeave(conv.id.slice(RELAY_PREFIX.length))
      if (conv.id === selectedId) onSelect(PUBLIC_CONVERSATION_ID)
    } catch (error) {
      notify(error instanceof Error ? error.message : '退出失败', 'error')
    }
  }

  // 蓝牙设备不一定已经落过会话，按行数据拼一个对象给菜单用。
  const openBleMenu = (
    event: React.MouseEvent,
    row: { id: string; name: string; connected: boolean }
  ): void => {
    event.preventDefault()
    setMenu({
      x: event.clientX,
      y: event.clientY,
      conv: {
        id: row.id,
        name: row.name,
        online: row.connected,
        last_message_at: 0,
        last_read_at: 0,
        unread: 0,
        created_at: 0
      }
    })
  }

  const confirmClear = async (): Promise<void> => {
    const conv = pendingClear
    setPendingClear(null)
    if (!conv) return
    try {
      await window.xt.clearConversation(conv.id)
      notify('已清空')
    } catch (error) {
      notify(error instanceof Error ? error.message : '清空失败', 'error')
    }
  }

  const confirmDelete = async (): Promise<void> => {
    const conv = pendingDelete
    setPendingDelete(null)
    if (!conv) return
    try {
      await window.xt.deleteConversation(conv.id)
      if (conv.id === selectedId) onSelect(PUBLIC_CONVERSATION_ID)
      notify('已删除')
    } catch (error) {
      notify(error instanceof Error ? error.message : '删除失败', 'error')
    }
  }

  const startSpeedTest = async (id: string): Promise<void> => {
    if (testing) return
    setTesting(id)
    setLiveSample(null)
    try {
      const result = await window.xt.speedTest(id)
      setTestAvg((prev) => ({ ...prev, [id]: result.peak }))
    } catch (error) {
      notify(error instanceof Error ? error.message : '测试失败', 'error')
    } finally {
      setTesting(null)
      setLiveSample(null)
    }
  }

  // 搜索要覆盖整段历史消息，不能只看最后一条预览。
  const searchIndex = useMemo(() => {
    const map = new Map<string, string>()
    for (const message of messages) {
      const text = message.kind === 'text' ? message.body : message.file_name
      if (!text) continue
      const prev = map.get(message.conversation_id)
      map.set(message.conversation_id, (prev ? `${prev}\n${text}` : text).toLowerCase())
    }
    return map
  }, [messages])

  const filtered = useMemo(() => {
    const keyword = query.trim().toLowerCase()
    if (!keyword) return conversations
    return conversations.filter((conv) => {
      const preview = previewOf(lastByConv.get(conv.id))
      return (
        conv.name.toLowerCase().includes(keyword) ||
        preview.toLowerCase().includes(keyword) ||
        (searchIndex.get(conv.id)?.includes(keyword) ?? false)
      )
    })
  }, [conversations, lastByConv, query, searchIndex])

  const broadcast = filtered.find((conv) => conv.id === PUBLIC_CONVERSATION_ID)
  const relayRows = filtered.filter((conv) => conv.id.startsWith(RELAY_PREFIX))
  const relayOrder = useMemo(() => {
    const order = new Map((relay?.rooms ?? []).map((room, index) => [`${RELAY_PREFIX}${room.id}`, index]))
    return order
  }, [relay?.rooms])
  relayRows.sort(
    (a, b) =>
      (relayOrder.get(a.id) ?? Number.MAX_SAFE_INTEGER) -
      (relayOrder.get(b.id) ?? Number.MAX_SAFE_INTEGER)
  )
  const dms = filtered.filter(
    (conv) =>
      conv.id !== PUBLIC_CONVERSATION_ID &&
      !conv.id.startsWith(BLE_PREFIX) &&
      !conv.id.startsWith(RELAY_PREFIX)
  )
  const pinnedDms = dms.filter((conv) => pinnedIds.includes(conv.id))
  const rest = dms.filter((conv) => !pinnedIds.includes(conv.id))
  const online = rest.filter((conv) => conv.online)
  const offline = rest.filter((conv) => !conv.online)
  const searching = query.trim() !== ''

  const bleRows = useMemo(() => {
    const map = new Map<
      string,
      {
        id: string
        name: string
        originalName?: string
        connected: boolean
        preview: string
        unread: number
      }
    >()
    for (const conv of conversations) {
      if (!conv.id.startsWith(BLE_PREFIX)) continue
      map.set(conv.id, {
        id: conv.id,
        name: conv.name,
        originalName: conv.originalName,
        connected: false,
        preview: previewOf(lastByConv.get(conv.id)),
        unread: conv.unread ?? 0
      })
    }
    for (const device of bluetooth?.devices ?? []) {
      const id = `${BLE_PREFIX}${device.id}`
      const existing = map.get(id)
      const alias = aliases[id]
      const deviceName = device.name || device.id
      map.set(id, {
        id,
        name: existing?.name || alias || deviceName,
        originalName: existing?.originalName ?? (alias ? deviceName : undefined),
        connected: device.connected === true,
        preview: existing?.preview ?? '',
        unread: existing?.unread ?? 0
      })
    }
    return [...map.values()].sort(
      (a, b) => Number(b.connected) - Number(a.connected) || a.name.localeCompare(b.name)
    )
  }, [conversations, lastByConv, bluetooth?.devices, aliases])

  const hasBleDevices = (bluetooth?.devices.length ?? 0) > 0
  const bleKeyword = query.trim().toLowerCase()
  const shownBleRows = bleKeyword
    ? bleRows.filter(
        (row) =>
          row.name.toLowerCase().includes(bleKeyword) ||
          row.preview.toLowerCase().includes(bleKeyword) ||
          (searchIndex.get(row.id)?.includes(bleKeyword) ?? false)
      )
    : bleRows

  const pinnedBleRows = shownBleRows.filter((row) => pinnedIds.includes(row.id))
  const normalBleRows = shownBleRows.filter((row) => !pinnedIds.includes(row.id))

  const bleList = (
    rows: {
      id: string
      name: string
      originalName?: string
      connected: boolean
      preview: string
      unread: number
    }[]
  ): ReactNode => (
    <Section title="蓝牙设备">
      {rows.map((row) => (
        <BleRow
          key={row.id}
          name={row.name}
          originalName={row.originalName}
          connected={row.connected}
          preview={row.preview}
          unread={row.unread}
          selected={row.id === selectedId}
          onSelect={() => onSelect(row.id)}
          onContextMenu={(event) => openBleMenu(event, row)}
        />
      ))}
    </Section>
  )

  const infoBle = infoShown?.id.startsWith(BLE_PREFIX)
    ? (bluetooth?.devices ?? []).find((device) => `${BLE_PREFIX}${device.id}` === infoShown.id)
    : undefined
  const infoPeer = infoShown ? peers.find((item) => item.id === infoShown.id) : undefined
  const infoRows: { label: string; value: string }[] = []
  if (infoShown) {
    infoRows.push({
      label: '名称',
      value: infoBle?.name || infoPeer?.name || infoShown.name
    })
    infoRows.push({ label: '设备 ID', value: infoBle?.id || infoShown.id })
    if (infoBle?.platform || infoPeer?.platform) {
      infoRows.push({ label: '平台', value: (infoBle?.platform || infoPeer?.platform) ?? '' })
    }
    infoRows.push({
      label: '状态',
      value: infoBle
        ? infoBle.connected
          ? '已连接'
          : '未连接'
        : infoShown.online
          ? '在线'
          : '离线'
    })
    if (infoBle?.address) infoRows.push({ label: '蓝牙地址', value: infoBle.address })
    if (infoBle) infoRows.push({ label: '信号强度', value: `${infoBle.rssi} dBm` })
    if (infoPeer?.addr) {
      infoRows.push({ label: '地址', value: `${infoPeer.addr}:${infoPeer.port ?? 0}` })
    }
    if (infoPeer) {
      infoRows.push({ label: '自动接受', value: infoPeer.trusted ? '已开启' : '未开启' })
    }
    const seenAt = infoBle?.lastSeen || infoPeer?.last_seen
    if (seenAt) infoRows.push({ label: '最近发现', value: formatWhen(seenAt) })
  }
  const infoPeak = infoShown
    ? Math.max(peakByConv.get(infoShown.id) ?? 0, testAvg[infoShown.id] ?? 0)
    : 0
  const liveValue =
    testing && liveSample && testing === liveSample.id ? liveSample.value : 0
  const shownPeakText = testing
    ? liveValue > 0
      ? `${formatSize(liveValue)}/s`
      : '测试中'
    : infoPeak > 0
      ? `${formatSize(infoPeak)}/s`
      : '未进行测试'

  return (
    <Box
      sx={{
        width: 320,
        flexShrink: 0,
        display: 'flex',
        flexDirection: 'column',
        borderRight: 1,
        borderColor: 'divider',
        bgcolor: 'background.default',
        minHeight: 0
      }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2, py: 1.5 }}>
        <TextField
          fullWidth
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="搜索消息"
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchRounded fontSize="small" sx={{ color: 'text.secondary' }} />
                </InputAdornment>
              )
            }
          }}
        />
        <Tooltip title="扫描附近设备">
          <span>
            <IconButton onClick={onScan} disabled={!serviceRunning || scanning}>
              <RefreshRounded
                fontSize="small"
                sx={
                  scanning
                    ? {
                        animation: 'xt-spin 1s linear infinite',
                        '@keyframes xt-spin': {
                          from: { transform: 'rotate(0deg)' },
                          to: { transform: 'rotate(360deg)' }
                        }
                      }
                    : undefined
                }
              />
            </IconButton>
          </span>
        </Tooltip>
      </Box>

      {lanOffline && (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            mx: 2,
            mb: 1,
            px: 1.5,
            py: 1,
            borderRadius: '12px',
            bgcolor: 'error.main',
            color: 'error.contrastText'
          }}
        >
          <WifiOffRounded sx={{ fontSize: 18, flexShrink: 0 }} />
          <Typography sx={{ flex: 1, minWidth: 0, fontSize: 12.5, fontWeight: 500 }}>
            当前设备未连接到局域网
          </Typography>
          <Button
            size="small"
            onClick={onOpenConnections}
            sx={{
              flexShrink: 0,
              minWidth: 0,
              px: 0.5,
              fontSize: 12.5,
              fontWeight: 600,
              color: 'inherit',
              textDecoration: 'underline'
            }}
          >
            查看解决方法
          </Button>
        </Box>
      )}

      <Box sx={{ flex: 1, overflowY: 'auto', pb: 2 }}>
        {searching ? (
          <>
            <Section title="搜索结果">
              {filtered
                .filter((conv) => !conv.id.startsWith(BLE_PREFIX))
                .map((conv) => (
                  <ConvRow
                    key={conv.id}
                    conv={conv}
                    last={lastByConv.get(conv.id)}
                    badge={badgeFor(conv)}
                    selected={conv.id === selectedId}
                    onSelect={() => onSelect(conv.id)}
                    onContextMenu={(event) => openMenu(event, conv)}
                  />
                ))}
              {filtered.filter((conv) => !conv.id.startsWith(BLE_PREFIX)).length === 0 &&
                shownBleRows.length === 0 && (
                  <Typography sx={{ px: 2.5, py: 2, fontSize: 13, color: 'text.secondary' }}>
                    没有匹配的会话
                  </Typography>
                )}
            </Section>
            {shownBleRows.length > 0 && bleList(shownBleRows)}
          </>
        ) : (
          <>
            {(pinnedDms.length > 0 || pinnedBleRows.length > 0) && (
              <Section title="置顶">
                {pinnedDms.map((conv) => (
                  <ConvRow
                    key={`pinned-${conv.id}`}
                    conv={conv}
                    last={lastByConv.get(conv.id)}
                    badge={badgeFor(conv)}
                    selected={conv.id === selectedId}
                    onSelect={() => onSelect(conv.id)}
                    onContextMenu={(event) => openMenu(event, conv)}
                  />
                ))}
                {pinnedBleRows.map((row) => (
                  <BleRow
                    key={`pinned-${row.id}`}
                    name={row.name}
                    originalName={row.originalName}
                    connected={row.connected}
                    preview={row.preview}
                    unread={row.unread}
                    selected={row.id === selectedId}
                    onSelect={() => onSelect(row.id)}
                    onContextMenu={(event) => openBleMenu(event, row)}
                  />
                ))}
              </Section>
            )}
            {(broadcast || online.length > 0 || offline.length > 0) && (
              <Section title="局域网">
                {[...(broadcast ? [broadcast] : []), ...online, ...offline]
                  // 公共频道不固定置顶，和其他设备一样在线优先、再按最近消息排。
                  .sort(
                    (a, b) =>
                      Number(b.online) - Number(a.online) ||
                      (b.last_message_at || 0) - (a.last_message_at || 0)
                  )
                  .map((conv) => (
                    <ConvRow
                      key={`lan-${conv.id}`}
                      conv={conv}
                      last={lastByConv.get(conv.id)}
                      badge={badgeFor(conv)}
                      selected={conv.id === selectedId}
                      onSelect={() => onSelect(conv.id)}
                      onContextMenu={(event) => openMenu(event, conv)}
                    />
                  ))}
              </Section>
            )}
            {(relay?.connected || relayRows.length > 0) && (
              <Section
                title="公网"
                action={
                  <Tooltip title="创建或加入房间">
                    <span>
                      <IconButton
                        size="small"
                        disabled={relay?.connected !== true}
                        onClick={() => setRoomOpen(true)}
                      >
                        <AddRounded fontSize="small" />
                      </IconButton>
                    </span>
                  </Tooltip>
                }
              >
                {relayRows.map((conv) => (
                  <ConvRow
                    key={`relay-${conv.id}`}
                    conv={conv}
                    last={lastByConv.get(conv.id)}
                    subtitle={
                      relay?.connected ? '已连接' : relay?.connecting ? '正在连接' : '未连接'
                    }
                    selected={conv.id === selectedId}
                    onSelect={() => onSelect(conv.id)}
                    onContextMenu={(event) => openMenu(event, conv)}
                  />
                ))}
              </Section>
            )}
            {hasBleDevices && normalBleRows.length > 0 && bleList(normalBleRows)}
            {dms.length === 0 && !hasBleDevices && relayRows.length === 0 && (
              <Typography sx={{ px: 2.5, py: 2, fontSize: 13, color: 'text.secondary' }}>
                暂无设备
              </Typography>
            )}
          </>
        )}
      </Box>

      <Menu
        open={menu !== null}
        onClose={() => setMenu(null)}
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
        {menuIsRelay ? (
          <>
            <MenuItem
              onClick={() => {
                setPendingClear(menu?.conv ?? null)
                setMenu(null)
              }}
            >
              清空聊天记录
            </MenuItem>
            {!menuRelayPublic && (
              <>
                <Divider />
                <MenuItem
                  sx={{ color: 'error.main' }}
                  onClick={() => {
                    const conv = menu?.conv
                    setMenu(null)
                    if (conv) void leaveRoom(conv)
                  }}
                >
                  退出房间
                </MenuItem>
              </>
            )}
          </>
        ) : (
          <>
            <MenuItem
              disabled={menuShown?.conv.id === PUBLIC_CONVERSATION_ID}
              onClick={() => {
                setPendingInfo(menu?.conv ?? null)
                setMenu(null)
              }}
            >
              查看设备信息
            </MenuItem>
            <Divider />
            <MenuItem
              disabled={menuShown?.conv.id === PUBLIC_CONVERSATION_ID}
              onClick={() => {
                const conv = menu?.conv
                setMenu(null)
                if (!conv) return
                setAliasDraft(aliases[conv.id] ?? '')
                setAliasTarget(conv)
              }}
            >
              备注名
            </MenuItem>
            {!menuIsBle && (
              <>
                <Divider />
                <MenuItem
                  disabled={menuShown?.conv.id === PUBLIC_CONVERSATION_ID}
                  onClick={() => {
                    const conv = menu?.conv
                    setMenu(null)
                    if (!conv) return
                    const isBlocked = blocked.includes(conv.id)
                    patchSettings(
                      { blocked: toggleMember(blocked, conv.id, !isBlocked) },
                      isBlocked ? '已移出黑名单' : '已添加黑名单'
                    )
                  }}
                >
                  {menuShown && blocked.includes(menuShown.conv.id) ? '移出黑名单' : '添加黑名单'}
                </MenuItem>
              </>
            )}
            <Divider />
            <MenuItem
              disabled={menuShown?.conv.id === PUBLIC_CONVERSATION_ID}
              sx={{ gap: 2, pr: 1 }}
              // 开关项点文字、点开关都只切换状态，都不关菜单。
              onClick={() => {
                const conv = menu?.conv
                if (!conv) return
                patchSettings({ muted: toggleMember(muted, conv.id, !muted.includes(conv.id)) })
              }}
            >
              <Typography sx={{ flex: 1, fontSize: 14 }}>免打扰</Typography>
              <Switch
                size="small"
                edge="end"
                checked={!!menuShown && muted.includes(menuShown.conv.id)}
                onClick={(event) => event.stopPropagation()}
                onChange={(event) => {
                  const conv = menu?.conv
                  if (!conv) return
                  patchSettings({ muted: toggleMember(muted, conv.id, event.target.checked) })
                }}
              />
            </MenuItem>
            <Divider />
            <MenuItem
              disabled={menuShown?.conv.id === PUBLIC_CONVERSATION_ID}
              sx={{ gap: 2, pr: 1 }}
              onClick={() => {
                const conv = menu?.conv
                if (!conv) return
                patchSettings({
                  pinned: toggleMember(pinnedIds, conv.id, !pinnedIds.includes(conv.id))
                })
              }}
            >
              <Typography sx={{ flex: 1, fontSize: 14 }}>置顶会话</Typography>
              <Switch
                size="small"
                edge="end"
                checked={!!menuShown && pinnedIds.includes(menuShown.conv.id)}
                onClick={(event) => event.stopPropagation()}
                onChange={(event) => {
                  const conv = menu?.conv
                  if (!conv) return
                  patchSettings({ pinned: toggleMember(pinnedIds, conv.id, event.target.checked) })
                }}
              />
            </MenuItem>
            <Divider />
            <MenuItem
              onClick={() => {
                setPendingClear(menu?.conv ?? null)
                setMenu(null)
              }}
            >
              清空聊天记录
            </MenuItem>
            {!menuIsBle && (
              <>
                <Divider />
                <MenuItem
                  sx={{ color: 'error.main' }}
                  onClick={() => {
                    setPendingDelete(menu?.conv ?? null)
                    setMenu(null)
                  }}
                >
                  删除此设备
                </MenuItem>
              </>
            )}
          </>
        )}
      </Menu>

      <Dialog open={pendingClear !== null} onClose={() => setPendingClear(null)}>
        <DialogTitle>清空聊天记录</DialogTitle>
        <DialogContent>
          <Typography sx={{ fontSize: 14, color: 'text.secondary' }}>
            {clearShown?.id === PUBLIC_CONVERSATION_ID
              ? '确定要清空公共频道的聊天记录吗？'
              : `确定要清空和 ${clearShown?.name ?? ''} 的聊天记录吗？`}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPendingClear(null)}>取消</Button>
          <Button color="error" variant="contained" onClick={() => void confirmClear()}>
            清空
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={pendingDelete !== null} onClose={() => setPendingDelete(null)}>
        <DialogTitle>删除此设备</DialogTitle>
        <DialogContent>
          <Typography sx={{ fontSize: 14, color: 'text.secondary' }}>
            {`确定要删除 ${deleteShown?.name ?? ''} 吗？聊天记录会一并清除。`}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPendingDelete(null)}>取消</Button>
          <Button color="error" variant="contained" onClick={() => void confirmDelete()}>
            删除
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={pendingInfo !== null} onClose={() => setPendingInfo(null)}>
        <DialogTitle>设备信息</DialogTitle>
        <DialogContent sx={{ minWidth: 360 }}>
          {infoRows.map((row) => (
            <Box key={row.label} sx={{ display: 'flex', gap: 2, py: 0.5 }}>
              <Typography sx={{ width: 72, flexShrink: 0, fontSize: 13, color: 'text.secondary' }}>
                {row.label}
              </Typography>
              <Typography sx={{ flex: 1, fontSize: 13, wordBreak: 'break-all' }}>
                {row.value}
              </Typography>
            </Box>
          ))}
          {!infoBle && (
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, py: 0.5 }}>
              <Typography sx={{ width: 72, flexShrink: 0, fontSize: 13, color: 'text.secondary' }}>
                峰值传输速度
              </Typography>
              <Typography sx={{ flex: 1, fontSize: 13 }}>
                {shownPeakText}
              </Typography>
              <Button
                size="small"
                variant="outlined"
                disabled={testing !== null || !pendingInfo || pendingInfo.online === false}
                onClick={() => {
                  if (pendingInfo) void startSpeedTest(pendingInfo.id)
                }}
              >
                {testing && testing === infoShown?.id ? '测试中' : '测试'}
              </Button>
            </Box>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPendingInfo(null)}>关闭</Button>
        </DialogActions>
      </Dialog>

      <Dialog open={aliasTarget !== null} onClose={() => setAliasTarget(null)}>
        <DialogTitle>备注名</DialogTitle>
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
          <Button onClick={() => setAliasTarget(null)}>取消</Button>
          <Button
            variant="contained"
            onClick={() => {
              const conv = aliasTarget
              setAliasTarget(null)
              if (!conv) return
              const text = aliasDraft.trim().slice(0, 15)
              const next = { ...aliases }
              if (text) next[conv.id] = text
              else delete next[conv.id]
              patchSettings({ aliases: next }, text ? '已保存' : '已清除备注名')
            }}
          >
            保存
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog
        open={roomOpen}
        onClose={() => {
          if (!roomBusy) setRoomOpen(false)
        }}
      >
        <DialogTitle>创建或加入房间</DialogTitle>
        <DialogContent sx={{ minWidth: 340 }}>
          <Box sx={{ display: 'flex', gap: 1, mt: 1 }}>
            <TextField
              fullWidth
              label="房间名称"
              value={roomName}
              onChange={(event) => setRoomName(event.target.value)}
              slotProps={{ htmlInput: { maxLength: 20 } }}
            />
            <Button
              variant="contained"
              disabled={roomBusy || roomName.trim() === ''}
              onClick={() => void createRoom()}
              sx={{ flexShrink: 0 }}
            >
              创建
            </Button>
          </Box>
          <Divider sx={{ my: 2 }} />
          <Box sx={{ display: 'flex', gap: 1 }}>
            <TextField
              fullWidth
              label="房间号"
              value={roomCode}
              onChange={(event) => setRoomCode(event.target.value.replace(/\D/g, '').slice(0, 6))}
              slotProps={{
                htmlInput: { inputMode: 'numeric', maxLength: 6 }
              }}
            />
            <Button
              variant="contained"
              disabled={roomBusy || roomCode.length !== 6}
              onClick={() => void joinRoom()}
              sx={{ flexShrink: 0 }}
            >
              加入
            </Button>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRoomOpen(false)} disabled={roomBusy}>
            关闭
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
