import { useRef } from 'react'

/** 弹层关闭动画期间继续用上一次的数据渲染，避免先变空壳再消失。 */
export function useRetained<T>(value: T | null): T | null {
  const ref = useRef<T | null>(null)
  if (value !== null) ref.current = value
  return value ?? ref.current
}
