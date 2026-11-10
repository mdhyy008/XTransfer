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

function clockText(timestamp: number): string {
  return new Date(timestamp).toLocaleTimeString('zh-CN', {
    timeZone: 'Asia/Shanghai',
    hour12: false
  })
}

function elapsedText(millis: number): string {
  const total = Math.max(0, Math.floor(millis / 1000))
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${pad(Math.floor(total / 3600))}:${pad(Math.floor((total % 3600) / 60))}:${pad(total % 60)}`
}

function InfoRow({
  label,
  value,
  divider,
  mono
}: {
  label: string
  value: string
  divider?: boolean
  mono?: boolean
}) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        py: 1.25,
        borderTop: divider ? 1 : 0,
        borderColor: 'divider'
      }}
    >
      <Typography sx={{ flex: 1, fontSize: 13, color: 'text.secondary' }}>{label}</Typography>
      <Typography sx={{ fontSize: 14, fontWeight: 600, fontFamily: mono ? 'monospace' : undefined }}>
        {value}
      </Typography>
    </Box>
  )
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
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (!open || !running) return
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [open, running])

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
              <InfoRow label="名称" value={status.ssid} />
              <InfoRow label="密码" value={status.password} divider mono />
              {status.startedAt !== undefined && (
                <InfoRow label="开启时间" value={clockText(status.startedAt)} divider />
              )}
              {status.startedAt !== undefined && (
                <InfoRow label="已开启" value={elapsedText(now - status.startedAt)} divider />
              )}
              {status.address && <InfoRow label="本机地址" value={status.address} divider />}
              {status.clients !== undefined && (
                <InfoRow label="已连接" value={`${status.clients} 台`} divider />
              )}
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
