import { useEffect, useState } from 'react'
import {
  Box,
  Button,
  Dialog,
  DialogContent,
  DialogTitle,
  Drawer,
  IconButton,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Tooltip,
  Typography
} from '@mui/material'
import AddPhotoAlternateRounded from '@mui/icons-material/AddPhotoAlternateRounded'
import CloseRounded from '@mui/icons-material/CloseRounded'
import CreateNewFolderRounded from '@mui/icons-material/CreateNewFolderRounded'
import DeleteSweepRounded from '@mui/icons-material/DeleteSweepRounded'
import DescriptionRounded from '@mui/icons-material/DescriptionRounded'
import DriveFileMoveRounded from '@mui/icons-material/DriveFileMoveRounded'
import FolderRounded from '@mui/icons-material/FolderRounded'
import HelpOutlineRounded from '@mui/icons-material/HelpOutlineRounded'
import NoteAddRounded from '@mui/icons-material/NoteAddRounded'
import ScreenshotRounded from '@mui/icons-material/ScreenshotRounded'
import type { FileEntry } from '../../../shared/types'
import { formatSize } from '../model'
import { fileKindLabel } from './ChatPane'

type Props = {
  open: boolean
  entries: FileEntry[]
  failedPaths: Set<string>
  onClose: () => void
  onAddFiles: () => void
  onAddFolder: () => void
  onOpen: (entry: FileEntry) => void
  onRemove: (path: string) => void
  onClear: () => void
}

function EntryIcon({ entry }: { entry: FileEntry }) {
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
        sx={{ width: 24, height: 24, borderRadius: '4px', objectFit: 'cover', display: 'block' }}
      />
    )
  }
  return entry.kind === 'folder' ? (
    <FolderRounded fontSize="small" />
  ) : (
    <DescriptionRounded fontSize="small" />
  )
}

// 文件夹体积要现算，算完前先只显示类型胶囊。
function EntryMeta({ entry, failed }: { entry: FileEntry; failed: boolean }) {
  const missing = !entry.exists
  const [size, setSize] = useState<number | null>(
    entry.kind === 'file' && entry.exists ? entry.size ?? 0 : null
  )
  useEffect(() => {
    let alive = true
    setSize(entry.kind === 'file' && entry.exists ? entry.size ?? 0 : null)
    if (entry.kind !== 'folder' || !entry.exists) return
    void window.xt
      .pathSize(entry.path)
      .then((value) => {
        if (alive) setSize(value)
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [entry.path, entry.kind, entry.exists, entry.size])
  const label = entry.kind === 'folder' ? '文件夹' : fileKindLabel(entry.name)
  return (
    <Box
      component="span"
      sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75 }}
    >
      <Box
        component="span"
        sx={{
          px: 0.7,
          borderRadius: '999px',
          bgcolor: 'action.hover',
          color: missing || failed ? 'error.main' : 'text.secondary',
          fontSize: 10.5,
          lineHeight: '16px'
        }}
      >
        {label}
      </Box>
      <Box component="span">
        {missing ? '不可用' : failed ? '发送失败' : size === null ? '' : formatSize(size)}
      </Box>
    </Box>
  )
}

export default function BucketDrawer({
  open,
  entries,
  failedPaths,
  onClose,
  onAddFiles,
  onAddFolder,
  onOpen,
  onRemove,
  onClear
}: Props) {
  const [helpOpen, setHelpOpen] = useState(false)
  return (
    <>
    <Drawer
      anchor="right"
      open={open}
      onClose={onClose}
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
        <Typography sx={{ flex: 1, fontSize: 16, fontWeight: 600 }}>暂存桶</Typography>
        <Tooltip title="帮助">
          <IconButton size="small" onClick={() => setHelpOpen(true)}>
            <HelpOutlineRounded fontSize="small" />
          </IconButton>
        </Tooltip>
        {entries.length > 0 && (
          <Tooltip title="清空">
            <IconButton size="small" onClick={onClear}>
              <DeleteSweepRounded fontSize="small" />
            </IconButton>
          </Tooltip>
        )}
        <Tooltip title="关闭">
          <IconButton size="small" onClick={onClose}>
            <CloseRounded fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      <Box sx={{ display: 'flex', gap: 1, px: 2.5, pb: 1 }}>
        <Button
          variant="contained"
          color="secondary"
          startIcon={<NoteAddRounded />}
          onClick={onAddFiles}
        >
          添加文件
        </Button>
        <Button
          variant="contained"
          color="secondary"
          startIcon={<CreateNewFolderRounded />}
          onClick={onAddFolder}
        >
          添加文件夹
        </Button>
      </Box>

      <List sx={{ flex: 1, overflowY: 'auto', overflowX: 'hidden', px: 1 }}>
        {entries.map((entry) => {
          const missing = !entry.exists
          const failed = !missing && failedPaths.has(entry.path)
          return (
            <ListItem
              key={entry.path}
              disablePadding
              sx={{ borderRadius: 4, mx: 1 }}
              secondaryAction={
                <Tooltip title="移除">
                  <IconButton edge="end" size="small" onClick={() => onRemove(entry.path)}>
                    <CloseRounded fontSize="small" />
                  </IconButton>
                </Tooltip>
              }
            >
              <ListItemButton
                sx={{ borderRadius: 4, pr: 7 }}
                onClick={() => onOpen(entry)}
              >
                <ListItemIcon sx={{ color: missing || failed ? 'error.main' : 'text.secondary' }}>
                  <EntryIcon entry={entry} />
                </ListItemIcon>
                <ListItemText
                  primary={entry.name}
                  secondary={<EntryMeta entry={entry} failed={failed} />}
                  slotProps={{
                    primary: {
                      noWrap: true,
                      sx: { fontSize: 14, color: missing || failed ? 'error.main' : undefined }
                    },
                    secondary: { sx: { fontSize: 11.5 } }
                  }}
                />
              </ListItemButton>
            </ListItem>
          )
        })}
        {entries.length === 0 && (
          <Typography sx={{ px: 3, py: 3, fontSize: 13, color: 'text.secondary' }}>暂无项目</Typography>
        )}
      </List>
    </Drawer>

    <Dialog open={helpOpen} onClose={() => setHelpOpen(false)} maxWidth="xs" fullWidth>
      <DialogTitle sx={{ fontSize: 16, fontWeight: 600, pb: 0.5 }}>存入暂存桶</DialogTitle>
      <DialogContent sx={{ pt: 1, pb: 2 }}>
        <List disablePadding>
          {[
            { icon: <DriveFileMoveRounded />, title: '拖进主窗口', detail: '把文件或文件夹拖到窗口任意位置' },
            { icon: <NoteAddRounded />, title: '添加文件', detail: '点击上方「添加文件」选择' },
            { icon: <CreateNewFolderRounded />, title: '添加文件夹', detail: '点击上方「添加文件夹」选择' },
            { icon: <ScreenshotRounded />, title: '截图', detail: '在对话里点击截图按钮' },
            {
              icon: <AddPhotoAlternateRounded />,
              title: '桌面接收区',
              detail: '把文件拖到屏幕右上角，选「存入暂存桶」'
            }
          ].map((item) => (
            <ListItem key={item.title} disableGutters sx={{ py: 0.75 }}>
              <ListItemIcon sx={{ minWidth: 40, color: 'text.secondary' }}>{item.icon}</ListItemIcon>
              <ListItemText
                primary={item.title}
                secondary={item.detail}
                slotProps={{
                  primary: { sx: { fontSize: 14 } },
                  secondary: { sx: { fontSize: 12 } }
                }}
              />
            </ListItem>
          ))}
        </List>
      </DialogContent>
    </Dialog>
    </>
  )
}
