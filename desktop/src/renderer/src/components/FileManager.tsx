import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Box,
  Button,
  ButtonBase,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  InputAdornment,
  Menu,
  MenuItem,
  TextField,
  Typography
} from '@mui/material'
import FolderRounded from '@mui/icons-material/FolderRounded'
import SearchRounded from '@mui/icons-material/SearchRounded'
import type { FileEntry } from '../../../shared/types'
import { formatSize } from '../model'
import { fileKindLabel, typeIcon } from './ChatPane'

const ALL = '全部'
const ORDER = ['图片', '视频', '音频', '文档', 'PDF', '压缩包', '安装包', '文件', '文件夹']

const dayFormat = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit'
})

// 分组和「今天/昨天」都按中国时间的自然日算，不跟设备时区走。
function dayKey(ms: number): string {
  return dayFormat.format(new Date(ms))
}

function dayLabel(key: string): string {
  if (key === dayKey(Date.now())) return '今天'
  if (key === dayKey(Date.now() - 86_400_000)) return '昨天'
  const [year, month, day] = key.split('-').map(Number)
  const thisYear = Number(dayKey(Date.now()).slice(0, 4))
  return year === thisYear ? `${month}月${day}日` : `${year}年${month}月${day}日`
}

function categoryOf(entry: FileEntry): string {
  return entry.kind === 'folder' ? '文件夹' : fileKindLabel(entry.name)
}

// 重命名只改主名，后缀固定不动。
function splitName(entry: FileEntry): { base: string; ext: string } {
  if (entry.kind === 'folder') return { base: entry.name, ext: '' }
  const dot = entry.name.lastIndexOf('.')
  if (dot <= 0) return { base: entry.name, ext: '' }
  return { base: entry.name.slice(0, dot), ext: entry.name.slice(dot) }
}

type MenuState = { x: number; y: number; entry: FileEntry } | null

function FileThumb({ entry }: { entry: FileEntry }) {
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

export default function FileManager({
  recvDir,
  refreshKey,
  onOpen,
  notify
}: {
  recvDir: string
  refreshKey: number
  onOpen: (entry: FileEntry) => void
  notify: (text: string, severity?: 'success' | 'error' | 'warning' | 'info') => void
}) {
  const [entries, setEntries] = useState<FileEntry[]>([])
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState(ALL)
  const [menu, setMenu] = useState<MenuState>(null)
  const [renameTarget, setRenameTarget] = useState<FileEntry | null>(null)
  const [renameDraft, setRenameDraft] = useState('')
  const [deleteTarget, setDeleteTarget] = useState<FileEntry | null>(null)
  const aliveRef = useRef(true)

  const load = useCallback((): void => {
    if (!recvDir) {
      setEntries([])
      return
    }
    window.xt
      .listDir(recvDir)
      .then((list) => {
        if (aliveRef.current) setEntries(list)
      })
      .catch(() => {})
  }, [recvDir])

  useEffect(() => {
    aliveRef.current = true
    load()
    return () => {
      aliveRef.current = false
    }
  }, [load, refreshKey])

  useEffect(() => window.xt.onFilesChanged(load), [load])

  const categories = useMemo(() => {
    const present = new Set(entries.map(categoryOf))
    return [ALL, ...ORDER.filter((name) => present.has(name))]
  }, [entries])

  useEffect(() => {
    if (!categories.includes(category)) setCategory(ALL)
  }, [categories, category])

  const groups = useMemo(() => {
    const keyword = query.trim().toLowerCase()
    const visible = entries.filter(
      (entry) =>
        (category === ALL || categoryOf(entry) === category) &&
        (keyword === '' || entry.name.toLowerCase().includes(keyword))
    )
    const map = new Map<string, FileEntry[]>()
    for (const entry of visible) {
      const key = dayKey(entry.mtime ?? Date.now())
      const list = map.get(key)
      if (list) list.push(entry)
      else map.set(key, [entry])
    }
    return [...map.entries()]
  }, [entries, category, query])

  const doRename = async (): Promise<void> => {
    if (!renameTarget) return
    const base = renameDraft.trim()
    if (!base) return
    const name = base + splitName(renameTarget).ext
    try {
      await window.xt.renamePath(renameTarget.path, name)
      setRenameTarget(null)
      load()
    } catch (error) {
      notify(error instanceof Error ? error.message : '重命名失败', 'error')
    }
  }

  const doDelete = async (): Promise<void> => {
    if (!deleteTarget) return
    try {
      await window.xt.trashPath(deleteTarget.path)
      setDeleteTarget(null)
      load()
      notify('已移到回收站')
    } catch (error) {
      notify(error instanceof Error ? error.message : '删除失败', 'error')
    }
  }

  const menuEntry = menu?.entry ?? null

  return (
    <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, px: 2.5, pt: 2, pb: 1 }}>
        <Typography sx={{ flex: 1, fontSize: 18, fontWeight: 600, color: 'text.primary' }}>
          文件管理
        </Typography>
        {entries.length > 0 && (
          <TextField
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="搜索"
            slotProps={{
              input: {
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchRounded sx={{ fontSize: 18, color: 'text.secondary' }} />
                  </InputAdornment>
                )
              }
            }}
            sx={{ width: 220 }}
          />
        )}
      </Box>
      {entries.length > 0 && (
        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, px: 2.5, pb: 1.5 }}>
          {categories.map((name) => {
            const selected = name === category
            return (
              <Chip
                key={name}
                label={name}
                size="small"
                clickable
                onClick={() => setCategory(name)}
                sx={{
                  height: 28,
                  fontSize: 12.5,
                  borderRadius: '999px',
                  bgcolor: selected ? 'secondary.main' : 'action.hover',
                  color: selected ? 'secondary.contrastText' : 'text.secondary',
                  '&:hover': { bgcolor: selected ? 'secondary.main' : 'action.selected' }
                }}
              />
            )
          })}
        </Box>
      )}
      <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto', px: 1.5, pb: 2 }}>
        {groups.length === 0 ? (
          <Box
            sx={{
              height: '100%',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 1.5
            }}
          >
            <Box
              sx={{
                width: 104,
                height: 104,
                borderRadius: '50%',
                bgcolor: 'action.hover',
                display: 'grid',
                placeItems: 'center'
              }}
            >
              <FolderRounded sx={{ fontSize: 44, color: 'text.disabled' }} />
            </Box>
            <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>暂无文件</Typography>
          </Box>
        ) : (
          groups.map(([key, list]) => (
            <Box key={key}>
              <Typography
                sx={{
                  position: 'sticky',
                  top: 0,
                  zIndex: 1,
                  px: 1,
                  py: 0.75,
                  fontSize: 12,
                  fontWeight: 500,
                  color: 'text.secondary',
                  bgcolor: 'background.default'
                }}
              >
                {dayLabel(key)}
              </Typography>
              {list.map((entry) => (
                <ButtonBase
                  key={entry.path}
                  disableRipple
                  onClick={() => onOpen(entry)}
                  onContextMenu={(event) => {
                    event.preventDefault()
                    setMenu({ x: event.clientX, y: event.clientY, entry })
                  }}
                  sx={{
                    width: '100%',
                    justifyContent: 'flex-start',
                    px: 1,
                    py: 0.9,
                    borderRadius: 3,
                    textAlign: 'left',
                    '&:hover': { bgcolor: 'action.hover' },
                    '&:active': { bgcolor: 'action.selected' }
                  }}
                >
                  <FileThumb entry={entry} />
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
                        {categoryOf(entry)}
                      </Box>
                      {entry.kind === 'file' && (
                        <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                          {formatSize(entry.size)}
                        </Typography>
                      )}
                    </Box>
                  </Box>
                </ButtonBase>
              ))}
            </Box>
          ))
        )}
      </Box>
      <Menu
        open={menu !== null}
        onClose={() => setMenu(null)}
        anchorReference="anchorPosition"
        anchorPosition={menu ? { top: menu.y, left: menu.x } : undefined}
        slotProps={{ paper: { sx: { minWidth: 168, borderRadius: '12px' } } }}
      >
        <MenuItem
          onClick={() => {
            if (menuEntry) onOpen(menuEntry)
            setMenu(null)
          }}
        >
          打开
        </MenuItem>
        <MenuItem
          onClick={() => {
            if (menuEntry) {
              window.xt.revealPath(menuEntry.path).catch(() => {})
            }
            setMenu(null)
          }}
        >
          在文件夹中显示
        </MenuItem>
        <MenuItem
          onClick={() => {
            if (menuEntry) {
              void navigator.clipboard
                .writeText(menuEntry.path)
                .then(() => notify('已复制路径'))
                .catch(() => notify('复制失败', 'error'))
            }
            setMenu(null)
          }}
        >
          复制路径
        </MenuItem>
        <MenuItem
          onClick={() => {
            if (menuEntry) {
              setRenameTarget(menuEntry)
              setRenameDraft(splitName(menuEntry).base)
            }
            setMenu(null)
          }}
        >
          重命名
        </MenuItem>
        <MenuItem
          onClick={() => {
            if (menuEntry) setDeleteTarget(menuEntry)
            setMenu(null)
          }}
          sx={{ color: 'error.main' }}
        >
          删除
        </MenuItem>
      </Menu>
      <Dialog
        open={renameTarget !== null}
        onClose={() => setRenameTarget(null)}
        slotProps={{ paper: { sx: { width: 420, borderRadius: '16px' } } }}
      >
        <DialogTitle sx={{ fontSize: 17 }}>重命名</DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            fullWidth
            value={renameDraft}
            onChange={(event) => setRenameDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') void doRename()
            }}
            slotProps={{
              input: renameTarget && splitName(renameTarget).ext
                ? {
                    endAdornment: (
                      <InputAdornment position="end">
                        {splitName(renameTarget).ext}
                      </InputAdornment>
                    )
                  }
                : undefined
            }}
            sx={{ mt: 1 }}
          />
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setRenameTarget(null)}>取消</Button>
          <Button variant="contained" onClick={() => void doRename()} disabled={!renameDraft.trim()}>
            确定
          </Button>
        </DialogActions>
      </Dialog>
      <Dialog
        open={deleteTarget !== null}
        onClose={() => setDeleteTarget(null)}
        slotProps={{ paper: { sx: { width: 420, borderRadius: '16px' } } }}
      >
        <DialogTitle sx={{ fontSize: 17 }}>删除</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ fontSize: 13.5 }}>
            {deleteTarget?.name}
          </DialogContentText>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setDeleteTarget(null)}>取消</Button>
          <Button color="error" variant="contained" onClick={() => void doDelete()}>
            删除
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
