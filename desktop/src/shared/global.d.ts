import type { XtApi } from './types'

declare global {
  interface Window {
    xt: XtApi
  }
}

export {}
