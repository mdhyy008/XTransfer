import { alpha, createTheme, type Theme } from '@mui/material/styles'
import type { SeedColor } from '../../shared/types'

export type Mode = 'light' | 'dark'

type SeedToneColors = {
  primary: string
  onPrimary: string
  primaryContainer: string
  onPrimaryContainer: string
}

type SeedTone = { light: SeedToneColors; dark: SeedToneColors }

export const SEED_ORDER: SeedColor[] = [
  'green',
  'purple',
  'blue',
  'orange',
  'teal',
  'pink',
  'red',
  'indigo',
  'yellow',
  'grey'
]

const seedLabels: Record<SeedColor, string> = {
  system: '系统',
  green: '绿',
  purple: '紫',
  blue: '蓝',
  orange: '橙',
  teal: '青',
  pink: '粉',
  red: '红',
  indigo: '靛',
  yellow: '黄',
  grey: '灰'
}

export function seedName(seed: SeedColor): string {
  return seedLabels[seed] ?? '绿'
}

const presetTones: Record<string, SeedTone> = {
  purple: {
    light: { primary: '#6750A4', onPrimary: '#FFFFFF', primaryContainer: '#EADDFF', onPrimaryContainer: '#21005D' },
    dark: { primary: '#D0BCFF', onPrimary: '#381E72', primaryContainer: '#4F378B', onPrimaryContainer: '#EADDFF' }
  },
  blue: {
    light: { primary: '#0061A4', onPrimary: '#FFFFFF', primaryContainer: '#D1E4FF', onPrimaryContainer: '#001D36' },
    dark: { primary: '#9ECAFF', onPrimary: '#003258', primaryContainer: '#00497D', onPrimaryContainer: '#D1E4FF' }
  },
  green: {
    light: { primary: '#386A20', onPrimary: '#FFFFFF', primaryContainer: '#B8F397', onPrimaryContainer: '#042100' },
    dark: { primary: '#9DD67B', onPrimary: '#0B3900', primaryContainer: '#1F5100', onPrimaryContainer: '#B8F397' }
  },
  orange: {
    light: { primary: '#8B5000', onPrimary: '#FFFFFF', primaryContainer: '#FFDCBE', onPrimaryContainer: '#2D1600' },
    dark: { primary: '#FFB870', onPrimary: '#4A2800', primaryContainer: '#693C00', onPrimaryContainer: '#FFDCBE' }
  }
}

const extraSeeds: Record<string, string> = {
  teal: '#006A6A',
  pink: '#984061',
  red: '#BA1A1A',
  indigo: '#4355B9',
  yellow: '#6C5D00',
  grey: '#5D5F5F'
}

function hexToHsv(hex: string): [number, number, number] {
  const clean = hex.replace('#', '')
  const full =
    clean.length === 3
      ? clean
          .split('')
          .map((c) => c + c)
          .join('')
      : clean.padStart(6, '0').slice(-6)
  const r = parseInt(full.slice(0, 2), 16) / 255
  const g = parseInt(full.slice(2, 4), 16) / 255
  const b = parseInt(full.slice(4, 6), 16) / 255
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const d = max - min
  let h = 0
  if (d !== 0) {
    if (max === r) h = ((g - b) / d) % 6
    else if (max === g) h = (b - r) / d + 2
    else h = (r - g) / d + 4
    h *= 60
    if (h < 0) h += 360
  }
  return [h, max === 0 ? 0 : d / max, max]
}

function hsvToHex(h: number, s: number, v: number): string {
  const c = v * s
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1))
  const m = v - c
  let r = 0
  let g = 0
  let b = 0
  if (h < 60) [r, g, b] = [c, x, 0]
  else if (h < 120) [r, g, b] = [x, c, 0]
  else if (h < 180) [r, g, b] = [0, c, x]
  else if (h < 240) [r, g, b] = [0, x, c]
  else if (h < 300) [r, g, b] = [x, 0, c]
  else [r, g, b] = [c, 0, x]
  const to = (n: number): string =>
    Math.round(Math.min(Math.max(n + m, 0), 1) * 255)
      .toString(16)
      .padStart(2, '0')
      .toUpperCase()
  return `#${to(r)}${to(g)}${to(b)}`
}

function derivedTone(seed: string): SeedTone {
  const [h, rawS] = hexToHsv(seed)
  const s = Math.min(Math.max(rawS, 0.02), 1)
  const tone = (saturation: number, value: number): string =>
    hsvToHex(h, Math.min(Math.max(saturation, 0), 1), value)
  return {
    light: {
      primary: tone(s * 0.85, 0.46),
      onPrimary: '#FFFFFF',
      primaryContainer: tone(s * 0.45, 0.93),
      onPrimaryContainer: tone(s * 0.9, 0.16)
    },
    dark: {
      primary: tone(s * 0.55, 0.82),
      onPrimary: tone(s * 0.9, 0.16),
      primaryContainer: tone(s * 0.5, 0.3),
      onPrimaryContainer: tone(s * 0.5, 0.92)
    }
  }
}

const derivedCache = new Map<string, SeedTone>()

function toneForSeed(seed: SeedColor, accent: string): SeedTone {
  if (seed === 'system') return derivedTone(accent)
  const preset = presetTones[seed]
  if (preset) return preset
  const base = extraSeeds[seed] ?? '#386A20'
  let tone = derivedCache.get(base)
  if (!tone) {
    tone = derivedTone(base)
    derivedCache.set(base, tone)
  }
  return tone
}

export function seedPrimary(seed: SeedColor, mode: Mode, accent = '#0078D4'): string {
  return toneForSeed(seed, accent)[mode].primary
}

const light = {
  primary: '#6750A4',
  onPrimary: '#FFFFFF',
  primaryContainer: '#EADDFF',
  onPrimaryContainer: '#21005D',
  secondaryContainer: '#E8DEF8',
  onSecondaryContainer: '#1D192B',
  surface: '#FEF7FF',
  surfaceContainerLowest: '#FFFFFF',
  surfaceContainerLow: '#F7F2FA',
  surfaceContainer: '#F3EDF7',
  surfaceContainerHigh: '#ECE6F0',
  onSurface: '#1D1B20',
  onSurfaceVariant: '#49454F',
  outline: '#79747E',
  outlineVariant: '#CAC4D0',
  error: '#B3261E',
  errorContainer: '#F9DEDC',
  onErrorContainer: '#410E0B'
}

const dark = {
  primary: '#D0BCFF',
  onPrimary: '#381E72',
  primaryContainer: '#4F378B',
  onPrimaryContainer: '#EADDFF',
  secondaryContainer: '#4A4458',
  onSecondaryContainer: '#E8DEF8',
  surface: '#141218',
  surfaceContainerLowest: '#0F0D13',
  surfaceContainerLow: '#1D1B20',
  surfaceContainer: '#211F26',
  surfaceContainerHigh: '#2B2930',
  onSurface: '#E6E0E9',
  onSurfaceVariant: '#CAC4D0',
  outline: '#938F99',
  outlineVariant: '#49454F',
  error: '#F2B8B5',
  errorContainer: '#8C1D18',
  onErrorContainer: '#F9DEDC'
}

export const tones: Record<Mode, typeof light> = { light, dark }

export function buildTheme(
  mode: Mode,
  seed: SeedColor = 'green',
  misans = true,
  accent = '#0078D4'
): Theme {
  const base = tones[mode]
  const seedTone = toneForSeed(seed, accent)[mode]
  const c = {
    ...base,
    primary: seedTone.primary,
    onPrimary: seedTone.onPrimary,
    primaryContainer: seedTone.primaryContainer,
    onPrimaryContainer: seedTone.onPrimaryContainer
  }
  return createTheme({
    palette: {
      mode,
      primary: { main: c.primary, contrastText: c.onPrimary },
      // 选中态容器色跟着主题色走，不能再用固定的紫色。
      secondary: { main: seedTone.primaryContainer, contrastText: seedTone.onPrimaryContainer },
      background: { default: c.surface, paper: c.surfaceContainerLow },
      text: { primary: c.onSurface, secondary: c.onSurfaceVariant },
      divider: c.outlineVariant,
      error: { main: c.error, contrastText: mode === 'light' ? '#FFFFFF' : '#601410' },
      success: { main: mode === 'light' ? '#2E7D32' : '#81C784' }
    },
    shape: { borderRadius: 16 },
    typography: {
      fontFamily: misans
        ? '"MiSans VF", "Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", system-ui, sans-serif'
        : '"Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", system-ui, sans-serif',
      h6: { fontSize: '1.5rem', lineHeight: 1.33, fontWeight: 400 },
      subtitle1: { fontSize: '1rem', lineHeight: 1.5, fontWeight: 500 },
      subtitle2: { fontSize: '0.875rem', lineHeight: 1.43, fontWeight: 500 },
      // MiSans 的 400 在小字号下偏细，正文抬到 450 看着更实。
      body1: { fontSize: '1rem', lineHeight: 1.5, fontWeight: misans ? 450 : undefined },
      body2: { fontSize: '0.875rem', lineHeight: 1.43, fontWeight: misans ? 450 : undefined },
      button: { fontSize: '0.875rem', lineHeight: 1.43, fontWeight: 500 },
      caption: { fontSize: '0.75rem', lineHeight: 1.33, fontWeight: misans ? 450 : undefined },
      overline: { fontSize: '0.6875rem', lineHeight: 1.45, fontWeight: 500, letterSpacing: '0.5px' }
    },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          'html, body, #root': { height: '100%' },
          body: {
            overflow: 'hidden',
            backgroundColor: c.surface,
            // 界面文字默认不可选中，也不需要文本光标；输入框和消息正文单独放开。
            userSelect: 'none',
            cursor: 'default'
          },
          'input, textarea, [contenteditable="true"]': {
            userSelect: 'text',
            cursor: 'text'
          },
          '*::-webkit-scrollbar': { width: 8, height: 8 },
          '*::-webkit-scrollbar-thumb': {
            borderRadius: 8,
            backgroundColor: alpha(c.onSurfaceVariant, 0.35)
          },
          '*::-webkit-scrollbar-track': { backgroundColor: 'transparent' }
        }
      },
      MuiTooltip: {
        defaultProps: { arrow: true },
        styleOverrides: {
          tooltip: {
            backgroundColor: mode === 'light' ? '#313033' : c.surfaceContainerHigh,
            color: mode === 'light' ? '#F4EFF4' : c.onSurface,
            borderRadius: 8,
            fontSize: 12
          }
        }
      },
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: { root: { textTransform: 'none', borderRadius: 999 } }
      },
      MuiIconButton: {
        styleOverrides: { root: { borderRadius: 999 } }
      },
      MuiTextField: {
        defaultProps: { size: 'small' },
        styleOverrides: {
          root: {
            '& .MuiOutlinedInput-root': {
              borderRadius: 999,
              backgroundColor: c.surfaceContainerHigh
            },
            '& .MuiOutlinedInput-notchedOutline': { borderColor: 'transparent' }
          }
        }
      }
    }
  })
}
