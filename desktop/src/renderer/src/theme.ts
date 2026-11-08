import { alpha, createTheme, type Theme } from '@mui/material/styles'
import type { SeedColor } from '../../shared/types'

export type Mode = 'light' | 'dark'

type SeedTone = {
  light: { primary: string; onPrimary: string; primaryContainer: string; onPrimaryContainer: string }
  dark: { primary: string; onPrimary: string; primaryContainer: string; onPrimaryContainer: string }
}

export const seedPresets: Record<SeedColor, { name: string; tone: SeedTone }> = {
  purple: {
    name: '紫',
    tone: {
      light: { primary: '#6750A4', onPrimary: '#FFFFFF', primaryContainer: '#EADDFF', onPrimaryContainer: '#21005D' },
      dark: { primary: '#D0BCFF', onPrimary: '#381E72', primaryContainer: '#4F378B', onPrimaryContainer: '#EADDFF' }
    }
  },
  blue: {
    name: '蓝',
    tone: {
      light: { primary: '#0061A4', onPrimary: '#FFFFFF', primaryContainer: '#D1E4FF', onPrimaryContainer: '#001D36' },
      dark: { primary: '#9ECAFF', onPrimary: '#003258', primaryContainer: '#00497D', onPrimaryContainer: '#D1E4FF' }
    }
  },
  green: {
    name: '绿',
    tone: {
      light: { primary: '#386A20', onPrimary: '#FFFFFF', primaryContainer: '#B8F397', onPrimaryContainer: '#042100' },
      dark: { primary: '#9DD67B', onPrimary: '#0B3900', primaryContainer: '#1F5100', onPrimaryContainer: '#B8F397' }
    }
  },
  orange: {
    name: '橙',
    tone: {
      light: { primary: '#8B5000', onPrimary: '#FFFFFF', primaryContainer: '#FFDCBE', onPrimaryContainer: '#2D1600' },
      dark: { primary: '#FFB870', onPrimary: '#4A2800', primaryContainer: '#693C00', onPrimaryContainer: '#FFDCBE' }
    }
  }
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

export function buildTheme(mode: Mode, seed: SeedColor = 'purple', misans = true): Theme {
  const base = tones[mode]
  const seedTone = seedPresets[seed].tone[mode]
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
