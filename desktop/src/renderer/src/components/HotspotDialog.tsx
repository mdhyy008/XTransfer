import { useEffect, useState } from 'react'
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
import type { HotspotStatus } from '../../../shared/types'

type Props = {
  open: boolean
  status: HotspotStatus | null
  busy: boolean
  onClose: () => void
  onStart: () => void
  onStop: () => void
  onOpenSettings: () => void
}

function qrEscape(value: string): string {
  return value.replace(/[\\;,:"']/g, (char) => `\\${char}`)
}

function wifiPayload(ssid: string, password: string): string {
  return `WIFI:T:WPA;S:${qrEscape(ssid)};P:${qrEscape(password)};;`
}

export default function HotspotDialog({
  open,
  status,
  busy,
  onClose,
  onStart,
  onStop,
  onOpenSettings
}: Props) {
  const running = status?.running === true
  const [qr, setQr] = useState('')

  useEffect(() => {
    if (!open || !running || !status) {
      setQr('')
      return
    }
    let alive = true
    void import('qrcode')
      .then((mod) =>
        mod.toDataURL(wifiPayload(status.ssid, status.password), {
          width: 480,
          margin: 1,
          errorCorrectionLevel: 'M'
        })
      )
      .then((url) => {
        if (alive) setQr(url)
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [open, running, status])

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
          热点
        </Typography>
        <IconButton size="small" onClick={onClose}>
          <CloseRounded fontSize="small" />
        </IconButton>
      </DialogTitle>
      <DialogContent sx={{ px: 3, pb: 3 }}>
        {running ? (
          <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2 }}>
            <Box
              sx={{
                width: 236,
                height: 236,
                borderRadius: '28px',
                bgcolor: '#fff',
                display: 'grid',
                placeItems: 'center',
                overflow: 'hidden'
              }}
            >
              {qr ? (
                <Box component="img" src={qr} alt="" sx={{ width: 216, height: 216 }} />
              ) : (
                <CircularProgress size={26} />
              )}
            </Box>
            <Box sx={{ width: '100%', bgcolor: 'background.paper', borderRadius: '20px', px: 2.5 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', py: 1.25 }}>
                <Typography sx={{ flex: 1, fontSize: 13, color: 'text.secondary' }}>名称</Typography>
                <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{status.ssid}</Typography>
              </Box>
              <Box sx={{ display: 'flex', alignItems: 'center', py: 1.25, borderTop: 1, borderColor: 'divider' }}>
                <Typography sx={{ flex: 1, fontSize: 13, color: 'text.secondary' }}>密码</Typography>
                <Typography sx={{ fontSize: 14, fontWeight: 600, fontFamily: 'monospace' }}>
                  {status.password}
                </Typography>
              </Box>
            </Box>
            <Button
              fullWidth
              variant="contained"
              color="error"
              disabled={busy}
              onClick={onStop}
              sx={{ borderRadius: '999px' }}
            >
              关闭热点
            </Button>
          </Box>
        ) : busy ? (
          <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2, py: 5 }}>
            <CircularProgress size={30} />
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>正在开启热点</Typography>
          </Box>
        ) : (
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, py: 1 }}>
            <Typography sx={{ fontSize: 13, color: 'text.secondary', lineHeight: 1.7 }}>
              把电脑当前的网络通过 Wi-Fi 共享出去，没有网络或连不上同一局域网的手机、电脑，连上这个热点后也能互传文件。
            </Typography>
            {status?.message && (
              <Typography sx={{ fontSize: 13, color: 'error.main', whiteSpace: 'pre-wrap' }}>
                {status.message}
              </Typography>
            )}
            <Box sx={{ display: 'flex', gap: 1 }}>
              <Button
                fullWidth
                variant="contained"
                onClick={onStart}
                sx={{ borderRadius: '999px' }}
              >
                开启
              </Button>
              <Button
                fullWidth
                variant="outlined"
                onClick={onOpenSettings}
                sx={{ borderRadius: '999px' }}
              >
                系统热点设置
              </Button>
            </Box>
          </Box>
        )}
      </DialogContent>
    </Dialog>
  )
}
