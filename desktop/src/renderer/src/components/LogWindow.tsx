import { useEffect, useRef, useState, type CSSProperties } from 'react'
import CloseRounded from '@mui/icons-material/CloseRounded'

const FONT =
  '"MiSans VF", "Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", system-ui, sans-serif'
const MONO = '"Cascadia Mono", "Consolas", "JetBrains Mono", ui-monospace, monospace'

const drag: CSSProperties = { WebkitAppRegion: 'drag' } as CSSProperties
const noDrag: CSSProperties = { WebkitAppRegion: 'no-drag' } as CSSProperties

export default function LogWindow() {
  const [lines, setLines] = useState<string[]>([])
  const bodyRef = useRef<HTMLDivElement>(null)
  // 用户往上翻看历史时不要被新日志拽回底部。
  const stick = useRef(true)

  useEffect(() => {
    document.documentElement.style.background = '#141218'
    document.body.style.background = '#141218'
    let alive = true
    const tick = async (): Promise<void> => {
      const res = await window.xt.readLogs().catch(() => null)
      if (!alive || !res) return
      setLines(res.lines)
    }
    void tick()
    const timer = window.setInterval(() => void tick(), 1000)
    return () => {
      alive = false
      window.clearInterval(timer)
    }
  }, [])

  useEffect(() => {
    const el = bodyRef.current
    if (el && stick.current) el.scrollTop = el.scrollHeight
  }, [lines])

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        display: 'flex',
        flexDirection: 'column',
        background: '#141218',
        color: '#E9E5F0',
        fontFamily: FONT
      }}
    >
      <div
        style={{
          ...drag,
          display: 'flex',
          alignItems: 'center',
          height: 46,
          padding: '0 6px 0 16px',
          borderBottom: '1px solid rgba(255,255,255,0.08)',
          flexShrink: 0
        }}
      >
        <div style={{ flex: 1, fontSize: 14, fontWeight: 600 }}>日志</div>
        <div
          onClick={() => window.close()}
          style={{
            ...noDrag,
            width: 34,
            height: 34,
            display: 'grid',
            placeItems: 'center',
            borderRadius: 8,
            cursor: 'pointer',
            color: '#CFC9DC'
          }}
          onMouseEnter={(event) => {
            event.currentTarget.style.background = 'rgba(255,255,255,0.08)'
          }}
          onMouseLeave={(event) => {
            event.currentTarget.style.background = 'transparent'
          }}
        >
          <CloseRounded sx={{ fontSize: 18 }} />
        </div>
      </div>

      <div
        ref={bodyRef}
        onScroll={(event) => {
          const el = event.currentTarget
          stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 24
        }}
        style={{
          flex: 1,
          minHeight: 0,
          overflow: 'auto',
          padding: '10px 14px 16px',
          fontFamily: MONO,
          fontSize: 11.5,
          lineHeight: 1.65,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-all',
          userSelect: 'text',
          cursor: 'text'
        }}
      >
        {lines.length === 0 ? (
          <div style={{ color: '#9A94A8' }}>暂无日志</div>
        ) : (
          lines.map((line, index) => <div key={index}>{line}</div>)
        )}
      </div>
    </div>
  )
}
