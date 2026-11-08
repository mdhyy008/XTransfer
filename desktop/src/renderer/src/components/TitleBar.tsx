import { Badge, Box, IconButton, Tooltip, Typography } from '@mui/material'
import { alpha } from '@mui/material/styles'
import CloseRounded from '@mui/icons-material/CloseRounded'
import CropSquareRounded from '@mui/icons-material/CropSquareRounded'
import MinimizeRounded from '@mui/icons-material/MinimizeRounded'
import Inventory2Rounded from '@mui/icons-material/Inventory2Rounded'
import SettingsRounded from '@mui/icons-material/SettingsRounded'
import WifiTetheringRounded from '@mui/icons-material/WifiTetheringRounded'
import BluetoothRounded from '@mui/icons-material/BluetoothRounded'
import type { CSSProperties } from 'react'
import type { LanInfo } from '../../../shared/types'
import logo from '../assets/logo.png'

const drag: CSSProperties = { WebkitAppRegion: 'drag' } as CSSProperties
const noDrag: CSSProperties = { WebkitAppRegion: 'no-drag' } as CSSProperties

type Props = {
  lan: LanInfo | null
  bucketCount: number
  notice: string
  noticeVisible: boolean
  hotspotVisible: boolean
  hotspotRunning: boolean
  bluetoothSupported: boolean
  bluetoothOn: boolean
  onOpenHotspot: () => void
  onOpenBluetooth: () => void
  onOpenBucket: () => void
  onOpenSettings: () => void
}

export default function TitleBar({
  lan,
  bucketCount,
  notice,
  noticeVisible,
  hotspotVisible,
  hotspotRunning,
  bluetoothSupported,
  bluetoothOn,
  onOpenHotspot,
  onOpenBluetooth,
  onOpenBucket,
  onOpenSettings
}: Props) {
  return (
    <Box
      style={drag}
      sx={{
        position: 'relative',
        height: 48,
        flexShrink: 0,
        display: 'flex',
        alignItems: 'center',
        gap: 1,
        pl: 2,
        pr: 0.5,
        bgcolor: 'background.default',
        userSelect: 'none'
      }}
    >
      <Box
        component="img"
        src={logo}
        alt="X互传"
        sx={{ width: 26, height: 26, display: 'block', borderRadius: '8px' }}
      />
      <Typography
        variant="subtitle2"
        noWrap
        sx={{ fontWeight: 600, letterSpacing: 0.2, minWidth: 0 }}
      >
        X互传
        <Box component="span" sx={{ fontWeight: 400, color: 'text.secondary' }}>
          {lan ? ` - ${lan.name} ${lan.cidr}` : ' - 未连接到局域网'}
        </Box>
      </Typography>
      {hotspotVisible && (
        <Box
          style={noDrag}
          onClick={onOpenHotspot}
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 0.5,
            flexShrink: 0,
            height: 24,
            px: 1,
            borderRadius: 999,
            bgcolor: (theme) => alpha(theme.palette.primary.main, 0.12),
            color: 'primary.main',
            cursor: 'pointer',
            transition: (theme) => theme.transitions.create('background-color'),
            '&:hover': { bgcolor: (theme) => alpha(theme.palette.primary.main, 0.22) }
          }}
        >
          <WifiTetheringRounded sx={{ fontSize: 15 }} />
          <Typography sx={{ fontSize: 12, fontWeight: 500, lineHeight: 1 }}>
            {hotspotRunning ? '查看热点' : '热点共享'}
          </Typography>
        </Box>
      )}
      {bluetoothSupported && (
        <Box
          style={noDrag}
          onClick={onOpenBluetooth}
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 0.5,
            flexShrink: 0,
            height: 24,
            px: 1,
            borderRadius: 999,
            bgcolor: (theme) =>
              bluetoothOn
                ? alpha(theme.palette.primary.main, 0.12)
                : alpha(theme.palette.text.primary, 0.08),
            color: bluetoothOn ? 'primary.main' : 'text.secondary',
            cursor: 'pointer',
            transition: (theme) => theme.transitions.create('background-color'),
            '&:hover': {
              bgcolor: (theme) =>
                bluetoothOn
                  ? alpha(theme.palette.primary.main, 0.22)
                  : alpha(theme.palette.text.primary, 0.16)
            }
          }}
        >
          <BluetoothRounded sx={{ fontSize: 15 }} />
          <Typography sx={{ fontSize: 12, fontWeight: 500, lineHeight: 1 }}>
            蓝牙连接
          </Typography>
        </Box>
      )}
      <Box
        style={noDrag}
        onClick={onOpenBucket}
        sx={{
          position: 'absolute',
          left: '50%',
          top: '50%',
          transform: 'translate(-50%, -50%)',
          display: 'flex',
          alignItems: 'center',
          gap: 0.75,
          height: 30,
          maxWidth: noticeVisible ? 340 : 0,
          opacity: noticeVisible ? 1 : 0,
          px: noticeVisible ? 1.5 : 0,
          borderRadius: 999,
          border: 1,
          borderColor: 'primary.main',
          bgcolor: 'primary.main',
          color: 'primary.contrastText',
          overflow: 'hidden',
          whiteSpace: 'nowrap',
          cursor: noticeVisible ? 'pointer' : undefined,
          pointerEvents: noticeVisible ? 'auto' : 'none',
          transition: (theme) =>
            theme.transitions.create(['max-width', 'opacity', 'padding'], {
              duration: 260,
              easing: 'cubic-bezier(0.05, 0.7, 0.1, 1)'
            })
        }}
      >
        <Inventory2Rounded sx={{ fontSize: 15 }} />
        <Typography sx={{ fontSize: 12.5 }}>{notice}</Typography>
      </Box>
      <Box sx={{ flex: 1 }} />
      <Box style={noDrag} sx={{ display: 'flex', alignItems: 'center' }}>
        <Tooltip title="暂存桶">
          <IconButton size="small" onClick={onOpenBucket} sx={{ mx: 0.25 }}>
            <Badge badgeContent={bucketCount} color="primary" max={99} invisible={bucketCount === 0}>
              <Inventory2Rounded fontSize="small" />
            </Badge>
          </IconButton>
        </Tooltip>
        <Tooltip title="设置">
          <IconButton size="small" onClick={onOpenSettings} sx={{ mx: 0.25 }}>
            <SettingsRounded fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title="最小化">
          <IconButton size="small" onClick={() => window.xt.minimize()} sx={{ mx: 0.25 }}>
            <MinimizeRounded fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title="最大化">
          <IconButton size="small" onClick={() => window.xt.toggleMaximize()} sx={{ mx: 0.25 }}>
            <CropSquareRounded sx={{ fontSize: 15 }} />
          </IconButton>
        </Tooltip>
        <Tooltip title="关闭">
          <IconButton
            size="small"
            onClick={() => window.xt.close()}
            sx={{
              mx: 0.25,
              '&:hover': { bgcolor: 'error.main', color: 'error.contrastText' }
            }}
          >
            <CloseRounded fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>
    </Box>
  )
}
