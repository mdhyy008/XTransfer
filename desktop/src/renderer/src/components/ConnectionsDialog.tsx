import type { ReactNode } from 'react'
import {
  Box,
  Button,
  ButtonBase,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Typography
} from '@mui/material'
import BluetoothRounded from '@mui/icons-material/BluetoothRounded'
import WifiTetheringRounded from '@mui/icons-material/WifiTetheringRounded'

type MethodProps = {
  icon: ReactNode
  title: string
  description: string
  onClick?: () => void
}

function Method({ icon, title, description, onClick }: MethodProps) {
  return (
    <ButtonBase
      disabled={!onClick}
      onClick={onClick}
      sx={{
        width: '100%',
        display: 'flex',
        alignItems: 'flex-start',
        gap: 1.5,
        p: 1.5,
        borderRadius: '14px',
        textAlign: 'left',
        justifyContent: 'flex-start',
        transition: 'background-color 140ms ease',
        '&:hover': onClick ? { bgcolor: 'action.hover' } : undefined
      }}
    >
      <Box
        sx={{
          flexShrink: 0,
          width: 38,
          height: 38,
          borderRadius: '12px',
          display: 'grid',
          placeItems: 'center',
          bgcolor: 'secondary.main',
          color: 'secondary.contrastText'
        }}
      >
        {icon}
      </Box>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography sx={{ fontSize: 14, fontWeight: 600, lineHeight: 1.4 }}>
          {title}
        </Typography>
        <Typography
          sx={{
            mt: 0.5,
            fontSize: 12.5,
            lineHeight: 1.6,
            color: 'text.secondary',
            whiteSpace: 'pre-line'
          }}
        >
          {description}
        </Typography>
      </Box>
    </ButtonBase>
  )
}

export default function ConnectionsDialog({
  open,
  hotspotSupported,
  bluetoothSupported,
  onOpenHotspot,
  onOpenBluetooth,
  onClose
}: {
  open: boolean
  hotspotSupported: boolean
  bluetoothSupported: boolean
  onOpenHotspot: () => void
  onOpenBluetooth: () => void
  onClose: () => void
}) {
  return (
    <Dialog
      open={open}
      onClose={onClose}
      slotProps={{
        paper: {
          sx: {
            width: 520,
            maxWidth: 'calc(100vw - 80px)',
            borderRadius: '16px',
            bgcolor: 'background.default'
          }
        }
      }}
    >
      <DialogTitle sx={{ fontSize: 18 }}>查看解决方法</DialogTitle>
      <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 0.5, pt: 1 }}>
        {hotspotSupported && (
          <Method
            icon={<WifiTetheringRounded />}
            title="热点共享"
            description="用法：打开热点，另一台设备扫码或输入密码连上后，两台设备组成一个临时局域网，可以互传文件、使用网页聊天。"
            onClick={onOpenHotspot}
          />
        )}
        {bluetoothSupported && (
          <Method
            icon={<BluetoothRounded />}
            title="蓝牙连接"
            description="用法：打开蓝牙连接，另一台设备搜索并连接，连上后通过蓝牙直接传文件，不需要网络。"
            onClick={onOpenBluetooth}
          />
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>关闭</Button>
      </DialogActions>
    </Dialog>
  )
}
