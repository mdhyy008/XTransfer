import { useEffect, useRef, useState, type DragEvent, type ReactNode } from 'react'
import Inventory2Rounded from '@mui/icons-material/Inventory2Rounded'
import LaunchRounded from '@mui/icons-material/LaunchRounded'

type ZoneKind = 'bucket' | 'open'

const FONT =
  '"MiSans VF", "Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", system-ui, sans-serif'

const ACCENT = '#D0BCFF'

// 拖拽窗口时浏览器也会派发 drag 事件，但 types 里没有 Files。
function isFileDrag(event: DragEvent): boolean {
  return Array.from(event.dataTransfer?.types ?? []).includes('Files')
}

function Zone({
  icon,
  label,
  hovered,
  onEnter,
  onLeave,
  onDrop
}: {
  icon: ReactNode
  label: string
  hovered: boolean
  onEnter: () => void
  onLeave: () => void
  onDrop: (event: DragEvent<HTMLDivElement>) => void
}) {
  return (
    <div
      onDragEnter={(event) => {
        event.preventDefault()
        if (!isFileDrag(event)) return
        onEnter()
      }}
      onDragLeave={() => onLeave()}
      onDragOver={(event) => {
        event.preventDefault()
        event.dataTransfer.dropEffect = 'copy'
      }}
      onDrop={onDrop}
      style={{
        flex: 1,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 6,
        borderRadius: 12,
        border: `1px solid ${hovered ? 'rgba(208,188,255,0.6)' : 'rgba(255,255,255,0.08)'}`,
        background: hovered ? 'rgba(208,188,255,0.16)' : 'rgba(255,255,255,0.06)',
        transition: 'background 140ms ease, border-color 140ms ease',
        cursor: 'copy'
      }}
    >
      <div style={{ color: ACCENT, display: 'flex', pointerEvents: 'none' }}>{icon}</div>
      <div style={{ fontSize: 13.5, fontWeight: 500, color: '#F2EFF7', pointerEvents: 'none' }}>
        {label}
      </div>
    </div>
  )
}

export default function DropPad() {
  const [active, setActive] = useState(false)
  const [zone, setZone] = useState<ZoneKind | null>(null)
  const depth = useRef(0)
  const leaveTimer = useRef<number | undefined>(undefined)
  const busy = useRef(false)

  useEffect(() => {
    document.documentElement.style.background = 'transparent'
    document.body.style.background = 'transparent'
    return () => {
      if (leaveTimer.current !== undefined) window.clearTimeout(leaveTimer.current)
    }
  }, [])

  const hide = (): void => {
    depth.current = 0
    setZone(null)
    setActive(false)
    window.xt.padDrag(false)
  }

  const onEnter = (event: DragEvent<HTMLDivElement>): void => {
    event.preventDefault()
    if (!isFileDrag(event)) return
    if (leaveTimer.current !== undefined) {
      window.clearTimeout(leaveTimer.current)
      leaveTimer.current = undefined
    }
    depth.current += 1
    if (depth.current === 1) {
      setActive(true)
      window.xt.padDrag(true)
    }
  }

  const onLeave = (): void => {
    depth.current = Math.max(0, depth.current - 1)
    if (depth.current > 0) return
    if (leaveTimer.current !== undefined) window.clearTimeout(leaveTimer.current)
    leaveTimer.current = window.setTimeout(hide, 120)
  }

  const onOver = (event: DragEvent<HTMLDivElement>): void => {
    event.preventDefault()
    if (!isFileDrag(event)) return
    event.dataTransfer.dropEffect = 'copy'
    if (leaveTimer.current !== undefined) {
      window.clearTimeout(leaveTimer.current)
      leaveTimer.current = undefined
    }
    if (depth.current === 0) {
      depth.current = 1
      setActive(true)
      window.xt.padDrag(true)
    }
  }

  const onDropZone = (kind: ZoneKind, event: DragEvent<HTMLDivElement>): void => {
    event.preventDefault()
    event.stopPropagation()
    const paths = Array.from(event.dataTransfer.files)
      .map((file) => window.xt.pathForFile(file))
      .filter(Boolean)
    hide()
    if (paths.length === 0 || busy.current) return
    busy.current = true
    window.xt
      .padReceive({ paths, open: kind === 'open' })
      .catch(() => {})
      .finally(() => {
        busy.current = false
      })
  }

  return (
    <div
      onDragEnter={onEnter}
      onDragLeave={onLeave}
      onDragOver={onOver}
      onDrop={(event) => {
        // 落在两个区域之外：吃掉默认行为，避免窗口跳去打开文件。
        event.preventDefault()
        hide()
      }}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'transparent',
        fontFamily: FONT,
        WebkitFontSmoothing: 'antialiased'
      }}
    >
      <div
        style={{
          position: 'absolute',
          inset: 5,
          display: 'flex',
          gap: 10,
          padding: 8,
          borderRadius: 16,
          background: 'rgba(22,20,28,0.94)',
          border: '1px solid rgba(255,255,255,0.1)',
          boxShadow: '0 18px 44px rgba(0,0,0,0.45)',
          backdropFilter: 'blur(18px)',
          opacity: active ? 1 : 0,
          transform: active ? 'scale(1)' : 'scale(0.96)',
          transition: 'opacity 160ms ease, transform 160ms ease',
          pointerEvents: 'none'
        }}
      >
        <div style={{ display: 'flex', flex: 1, gap: 10, pointerEvents: active ? 'auto' : 'none' }}>
          <Zone
            icon={<Inventory2Rounded sx={{ fontSize: 26 }} />}
            label="存入暂存桶"
            hovered={zone === 'bucket'}
            onEnter={() => setZone('bucket')}
            onLeave={() => setZone((current) => (current === 'bucket' ? null : current))}
            onDrop={(event) => onDropZone('bucket', event)}
          />
          <Zone
            icon={<LaunchRounded sx={{ fontSize: 26 }} />}
            label="存入并打开软件"
            hovered={zone === 'open'}
            onEnter={() => setZone('open')}
            onLeave={() => setZone((current) => (current === 'open' ? null : current))}
            onDrop={(event) => onDropZone('open', event)}
          />
        </div>
      </div>
    </div>
  )
}
