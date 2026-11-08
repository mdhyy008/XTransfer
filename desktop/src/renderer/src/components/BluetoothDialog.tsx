import {
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogContent,
  DialogTitle,
  IconButton,
  Typography
} from '@mui/material'
import CloseRounded from '@mui/icons-material/CloseRounded'
import type { BleState, DeviceInfo } from '../../../shared/types'

type Props = {
  open: boolean
  info: DeviceInfo | null
  bluetooth?: BleState
  busy: boolean
  onClose: () => void
  onToggle: (on: boolean) => void
}

export default function BluetoothDialog({
  open,
  info,
  bluetooth,
  busy,
  onClose,
  onToggle
}: Props) {
  const on = bluetooth?.on === true
  const rows = [
    { label: '名称', value: info?.name ?? '' },
    { label: '设备 ID', value: info?.id ?? '' },
    { label: '状态', value: on ? '已开启' : '未开启' },
    {
      label: '广播',
      value: bluetooth?.advertising ? '广播中' : '未广播'
    },
    {
      label: '扫描',
      value: bluetooth?.scanning ? `扫描中（${bluetooth?.devices.length ?? 0}）` : '未扫描'
    }
  ]

  return (
    <Dialog
      open={open}
      onClose={onClose}
      slotProps={{
        paper: {
          sx: { width: 400, borderRadius: '16px', bgcolor: 'background.default' }
        }
      }}
    >
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', px: 3, py: 2 }}>
        <Typography variant="subtitle1" sx={{ flex: 1, fontWeight: 600 }}>
          蓝牙
        </Typography>
        <IconButton size="small" onClick={onClose}>
          <CloseRounded fontSize="small" />
        </IconButton>
      </DialogTitle>
      <DialogContent sx={{ px: 3, pb: 3 }}>
        <Box sx={{ width: '100%', bgcolor: 'background.paper', borderRadius: '20px', px: 2.5 }}>
          {rows.map((row) => (
            <Box key={row.label} sx={{ display: 'flex', alignItems: 'center', py: 1.25 }}>
              <Typography sx={{ flex: 1, fontSize: 13, color: 'text.secondary' }}>
                {row.label}
              </Typography>
              <Typography noWrap sx={{ fontSize: 14, fontWeight: 600, maxWidth: 220 }}>
                {row.value}
              </Typography>
            </Box>
          ))}
        </Box>
        {bluetooth?.error && (
          <Typography sx={{ mt: 1.5, fontSize: 12.5, color: 'error.main' }}>
            {bluetooth.error}
          </Typography>
        )}
        <Button
          fullWidth
          variant="contained"
          disabled={busy || !bluetooth?.supported}
          onClick={() => onToggle(!on)}
          sx={{ mt: 2, height: 44, borderRadius: '14px', textTransform: 'none' }}
        >
          {busy ? (
            <CircularProgress size={20} color="inherit" />
          ) : on ? (
            '停止蓝牙广播'
          ) : (
            '开始蓝牙广播'
          )}
        </Button>
        {!bluetooth?.supported && (
          <Typography sx={{ mt: 1.5, fontSize: 12.5, color: 'text.secondary' }}>
            当前系统不支持蓝牙通道
          </Typography>
        )}
      </DialogContent>
    </Dialog>
  )
}
