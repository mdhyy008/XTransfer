import { useEffect, useState } from 'react'
import QRCode from 'qrcode'
import { Box, Button, Dialog, DialogActions, DialogContent, DialogTitle } from '@mui/material'
import type { FileEntry } from '../../../shared/types'

const SITE_URL = 'https://mdhyy.cn/tools/page/x-transfer/'
const SHARE_TEXT = `X互传 · 点对点文件互传，手机和电脑之间传文件不压缩、不限速。同一局域网内直连高速传输。\n官网：${SITE_URL}`

export default function ShareAppDialog({
  open,
  onClose,
  onSharePackage,
  notify
}: {
  open: boolean
  onClose: () => void
  onSharePackage: (entry: FileEntry) => void
  notify: (text: string, severity?: 'success' | 'error' | 'warning' | 'info') => void
}) {
  const [qr, setQr] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!open) return
    let alive = true
    setQr('')
    QRCode.toDataURL(SITE_URL, { width: 420, margin: 1, errorCorrectionLevel: 'M' })
      .then((data) => {
        if (alive) setQr(data)
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [open])

  const copySite = (): void => {
    navigator.clipboard
      .writeText(SITE_URL)
      .then(() => notify('已复制官网链接'))
      .catch((error: unknown) =>
        notify(error instanceof Error ? error.message : '复制失败', 'error')
      )
  }

  const copyShareText = (): void => {
    navigator.clipboard
      .writeText(SHARE_TEXT)
      .then(() => notify('分享文案已复制'))
      .catch((error: unknown) =>
        notify(error instanceof Error ? error.message : '复制失败', 'error')
      )
  }

  const sharePackage = async (): Promise<void> => {
    if (busy) return
    setBusy(true)
    try {
      const entry = await window.xt.sharePackage()
      if (!entry) return
      onSharePackage(entry)
      onClose()
    } catch (error) {
      notify(error instanceof Error ? error.message : '打包失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onClose={busy ? undefined : onClose}>
      <DialogTitle sx={{ fontSize: 18 }}>分享本软件</DialogTitle>
      <DialogContent sx={{ minWidth: 340 }}>
        {qr && (
          <Box
            component="img"
            src={qr}
            alt="软件官网"
            sx={{ display: 'block', width: 200, height: 200, mx: 'auto', my: 1, borderRadius: 2 }}
          />
        )}
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, mt: 1 }}>
          <Button variant="contained" onClick={copySite}>
            复制软件官网
          </Button>
          <Button variant="contained" onClick={copyShareText}>
            分享网址
          </Button>
          <Button variant="contained" disabled={busy} onClick={() => void sharePackage()}>
            {busy ? '正在打包…' : '通过文件分发'}
          </Button>
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>
          关闭
        </Button>
      </DialogActions>
    </Dialog>
  )
}
