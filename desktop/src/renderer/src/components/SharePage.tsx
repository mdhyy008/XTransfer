import { useEffect, useState } from 'react'
import {
  Box,
  Button,
  ButtonBase,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  Tooltip,
  Typography
} from '@mui/material'
import CreateNewFolderRounded from '@mui/icons-material/CreateNewFolderRounded'
import FolderRounded from '@mui/icons-material/FolderRounded'
import NoteAddRounded from '@mui/icons-material/NoteAddRounded'
import OpenInNewRounded from '@mui/icons-material/OpenInNewRounded'
import PlayArrowRounded from '@mui/icons-material/PlayArrowRounded'
import QrCode2Rounded from '@mui/icons-material/QrCode2Rounded'
import StopCircleRounded from '@mui/icons-material/StopCircleRounded'
import AndroidRounded from '@mui/icons-material/AndroidRounded'
import ImageRounded from '@mui/icons-material/ImageRounded'
import DevicesOtherRounded from '@mui/icons-material/DevicesOtherRounded'
import CloseRounded from '@mui/icons-material/CloseRounded'
import type { FileEntry, ShareStatus } from '../../../shared/types'
import { formatSize } from '../model'
import { fileKindLabel, typeIcon } from './ChatPane'

function ShareThumb({ entry }: { entry: FileEntry }) {
  const [thumb, setThumb] = useState<string | null>(null)
  useEffect(() => {
    let alive = true
    setThumb(null)
    if (entry.kind === 'folder' || !entry.exists) return
    void window.xt
      .fileThumb(entry.path)
      .then((url) => {
        if (alive) setThumb(url)
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [entry.path, entry.kind, entry.exists])
  if (thumb) {
    return (
      <Box
        component="img"
        src={thumb}
        alt=""
        sx={{
          width: 40,
          height: 40,
          borderRadius: '12px',
          objectFit: 'cover',
          flexShrink: 0,
          display: 'block'
        }}
      />
    )
  }
  return (
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
      {entry.kind === 'folder' ? <FolderRounded fontSize="small" /> : typeIcon(entry.name)}
    </Box>
  )
}

export default function SharePage({
  bucket,
  onAddFiles,
  onAddImages,
  onAddApks,
  onAddFolder,
  onRemove,
  onOpen,
  notify
}: {
  bucket: FileEntry[]
  onAddFiles: () => void
  onAddImages: () => void
  onAddApks: () => void
  onAddFolder: () => void
  onRemove: (path: string) => void
  onOpen: (entry: FileEntry) => void
  notify: (text: string, severity?: 'success' | 'error' | 'warning' | 'info') => void
}) {
  const [status, setStatus] = useState<ShareStatus | null>(null)
  const [busy, setBusy] = useState(false)
  const [qr, setQr] = useState('')
  const [qrOpen, setQrOpen] = useState(false)
  const running = status?.running === true
  const url = status?.url ?? ''

  useEffect(() => {
    window.xt
      .shareStatus()
      .then(setStatus)
      .catch(() => {})
  }, [])

  // 分发中往暂存桶里加东西/删东西时，让服务端立即换成新的文件列表。
  useEffect(() => {
    if (!running) return
    window.xt
      .shareStart(bucket)
      .then(setStatus)
      .catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bucket, running])

  useEffect(() => {
    if (!qrOpen || !url) return
    let alive = true
    setQr('')
    void import('qrcode')
      .then((mod) =>
        mod.toDataURL(url, { width: 480, margin: 1, errorCorrectionLevel: 'M' })
      )
      .then((data) => {
        if (alive) setQr(data)
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [qrOpen, url])

  const start = async (): Promise<void> => {
    if (busy) return
    setBusy(true)
    try {
      const next = await window.xt.shareStart(bucket)
      setStatus(next)
      if (next.error) notify(next.error, 'warning')
      else notify('已启动分发')
    } catch (error) {
      notify(error instanceof Error ? error.message : '启动失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  const stop = async (): Promise<void> => {
    if (busy) return
    setBusy(true)
    try {
      setStatus(await window.xt.shareStop())
      notify('已停止分发')
    } catch (error) {
      notify(error instanceof Error ? error.message : '操作失败', 'error')
    } finally {
      setBusy(false)
    }
  }

  const localFiles = bucket.filter((entry) => entry.kind === 'file')
  const count = running ? (status?.count ?? 0) : localFiles.length
  const bytes = running
    ? (status?.bytes ?? 0)
    : localFiles.reduce((sum, entry) => sum + entry.size, 0)

  return (
    <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
      <Typography sx={{ px: 2.5, pt: 2, pb: 1, fontSize: 18, fontWeight: 600 }}>
        内网分发
      </Typography>
      <Box sx={{ px: 2.5, pb: 1.5 }}>
        <Box
          sx={{
            position: 'relative',
            overflow: 'hidden',
            bgcolor: 'background.paper',
            borderRadius: '24px',
            px: 2.5,
            py: 2.25,
            border: 1,
            borderColor: running ? 'success.main' : 'divider'
          }}
        >
          <Box
            sx={{
              position: 'absolute',
              inset: 0,
              pointerEvents: 'none',
              background: (theme) =>
                `linear-gradient(135deg, ${theme.palette.primary.main}14 0%, transparent 55%)`
            }}
          />
          <Box
            sx={{
              position: 'relative',
              display: 'flex',
              alignItems: 'center',
              gap: 1.25
            }}
          >
            <Box
              sx={{
                width: 38,
                height: 38,
                borderRadius: '12px',
                display: 'grid',
                placeItems: 'center',
                bgcolor: running ? 'success.main' : 'action.hover',
                color: running ? '#fff' : 'text.secondary',
                transition: 'background-color 200ms ease'
              }}
            >
              <DevicesOtherRounded sx={{ fontSize: 21 }} />
            </Box>
            <Box sx={{ minWidth: 0, flex: 1 }}>
              <Typography sx={{ fontSize: 14.5, fontWeight: 600, lineHeight: 1.3 }}>
                {running ? '分发中' : '未启动'}
              </Typography>
              <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                {`${count} 个文件 · ${formatSize(bytes) || '0 B'}`}
              </Typography>
            </Box>
          </Box>
          <Box
            sx={{
              position: 'relative',
              mt: 1.75,
              px: 1.5,
              py: 1.1,
              borderRadius: '14px',
              bgcolor: 'action.hover',
              minHeight: 42,
              display: 'flex',
              alignItems: 'center'
            }}
          >
            {running && url ? (
              <Box
                component="a"
                href={url}
                onClick={(event) => {
                  event.preventDefault()
                  void window.xt.openExternal(url).catch(() => {})
                }}
                sx={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 0.75,
                  minWidth: 0,
                  textDecoration: 'none',
                  color: 'primary.main',
                  '&:hover .MuiTypography-root': { textDecoration: 'underline' }
                }}
              >
                <Typography
                  sx={{
                    fontSize: 13,
                    fontWeight: 500,
                    wordBreak: 'break-all',
                    userSelect: 'text',
                    cursor: 'pointer'
                  }}
                >
                  {url}
                </Typography>
                <OpenInNewRounded sx={{ fontSize: 15, flexShrink: 0 }} />
              </Box>
            ) : (
              <Typography
                component="span"
                data-selectable="true"
                sx={{ fontSize: 13, color: 'text.secondary', userSelect: 'text' }}
              >
                —
              </Typography>
            )}
          </Box>
          <Box sx={{ position: 'relative', mt: 1.75, display: 'flex', gap: 1 }}>
            <Button
              variant="outlined"
              size="small"
              startIcon={<QrCode2Rounded />}
              disabled={!running || !url}
              onClick={() => setQrOpen(true)}
            >
              二维码
            </Button>
            {running ? (
              <Button
                variant="contained"
                size="small"
                startIcon={<StopCircleRounded />}
                disabled={busy}
                onClick={() => void stop()}
              >
                停止分发
              </Button>
            ) : (
              <Button
                variant="contained"
                size="small"
                startIcon={<PlayArrowRounded />}
                disabled={busy}
                onClick={() => void start()}
              >
                启动分发
              </Button>
            )}
          </Box>
        </Box>
      </Box>
      <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto', px: 1.5, pb: 1 }}>
        {bucket.length === 0 ? (
          <Box
            sx={{
              height: '100%',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 1,
              color: 'text.secondary'
            }}
          >
            <DevicesOtherRounded sx={{ fontSize: 72, opacity: 0.35 }} />
            <Typography sx={{ fontSize: 13 }}>暂存桶为空</Typography>
          </Box>
        ) : (
          bucket.map((entry) => (
            <Box
              key={entry.path}
              sx={{ display: 'flex', alignItems: 'center', pr: 0.5, borderRadius: 3 }}
            >
              <ButtonBase
                disableRipple
                onClick={() => onOpen(entry)}
                sx={{
                  flex: 1,
                  minWidth: 0,
                  justifyContent: 'flex-start',
                  px: 1,
                  py: 0.9,
                  borderRadius: 3,
                  textAlign: 'left',
                  '&:hover': { bgcolor: 'action.hover' },
                  '&:active': { bgcolor: 'action.selected' }
                }}
              >
                <ShareThumb entry={entry} />
                <Box sx={{ minWidth: 0, flex: 1, ml: 1.25 }}>
                  <Typography noWrap sx={{ fontSize: 13.5, fontWeight: 500 }}>
                    {entry.name}
                  </Typography>
                  <Box sx={{ mt: 0.25, display: 'flex', alignItems: 'center', gap: 0.75 }}>
                    <Box
                      component="span"
                      sx={{
                        px: 0.7,
                        borderRadius: '999px',
                        bgcolor: 'action.hover',
                        color: 'text.secondary',
                        fontSize: 10.5,
                        lineHeight: '16px'
                      }}
                    >
                      {entry.kind === 'folder' ? '文件夹' : fileKindLabel(entry.name)}
                    </Box>
                    {entry.kind === 'file' && (
                      <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                        {formatSize(entry.size)}
                      </Typography>
                    )}
                  </Box>
                </Box>
              </ButtonBase>
              <Tooltip title="移除">
                <IconButton
                  size="small"
                  sx={{ flexShrink: 0 }}
                  onClick={() => onRemove(entry.path)}
                >
                  <CloseRounded fontSize="small" />
                </IconButton>
              </Tooltip>
            </Box>
          ))
        )}
      </Box>
      <Box
        sx={{
          flexShrink: 0,
          borderTop: 1,
          borderColor: 'divider',
          px: 2,
          py: 1.25,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'flex-end',
          gap: 1,
          overflowX: 'auto'
        }}
      >
        <Button
          size="small"
          variant="outlined"
          startIcon={<NoteAddRounded />}
          onClick={onAddFiles}
          sx={{ flexShrink: 0 }}
        >
          添加文件
        </Button>
        <Button
          size="small"
          variant="outlined"
          startIcon={<CreateNewFolderRounded />}
          onClick={onAddFolder}
          sx={{ flexShrink: 0 }}
        >
          添加文件夹
        </Button>
        <Button
          size="small"
          variant="outlined"
          startIcon={<ImageRounded />}
          onClick={onAddImages}
          sx={{ flexShrink: 0 }}
        >
          添加图片
        </Button>
        <Button
          size="small"
          variant="outlined"
          startIcon={<AndroidRounded />}
          onClick={onAddApks}
          sx={{ flexShrink: 0 }}
        >
          添加安装包
        </Button>
      </Box>
      <Dialog
        open={qrOpen}
        onClose={() => setQrOpen(false)}
        slotProps={{ paper: { sx: { borderRadius: '20px', p: 1 } } }}
      >
        <DialogTitle sx={{ fontSize: 17 }}>二维码</DialogTitle>
        <DialogContent>
          <Box
            sx={{
              p: 2,
              bgcolor: '#fff',
              borderRadius: '16px',
              width: 300,
              height: 300,
              display: 'grid',
              placeItems: 'center'
            }}
          >
            {qr ? (
              <Box
                component="img"
                src={qr}
                alt=""
                sx={{ width: '100%', height: '100%', display: 'block' }}
              />
            ) : (
              <Typography sx={{ color: '#666', fontSize: 13 }}>生成中</Typography>
            )}
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setQrOpen(false)}>关闭</Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
