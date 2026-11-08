import { useEffect } from 'react'

/** 菜单打开时，右键点在菜单外面也收起菜单（左键由 MUI 自己处理）。 */
export function useRightClickDismiss(open: boolean, dismiss: () => void): void {
  useEffect(() => {
    if (!open) return
    const onContextMenu = (event: MouseEvent): void => {
      const target = event.target as Element | null
      if (target?.closest('[role="menu"]')) return
      dismiss()
    }
    window.addEventListener('contextmenu', onContextMenu, true)
    return () => window.removeEventListener('contextmenu', onContextMenu, true)
  }, [open, dismiss])
}
