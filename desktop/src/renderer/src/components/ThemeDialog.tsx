import {
  Box,
  Dialog,
  DialogContent,
  DialogTitle,
  IconButton,
  Typography,
  useTheme
} from '@mui/material'
import CheckRounded from '@mui/icons-material/CheckRounded'
import CloseRounded from '@mui/icons-material/CloseRounded'
import type { SeedColor } from '../../../shared/types'
import { SEED_ORDER, seedName, seedPrimary } from '../theme'

type Props = {
  open: boolean
  seed: SeedColor
  accent: string
  onPick: (seed: SeedColor) => void
  onClose: () => void
}

// 默认的绿排在最前，其余按色相排；「系统」跟在默认色后面。
const TILES: SeedColor[] = [
  'green',
  'system',
  ...SEED_ORDER.filter((item) => item !== 'green')
]

export default function ThemeDialog({ open, seed, accent, onPick, onClose }: Props) {
  const dark = useTheme().palette.mode === 'dark'

  return (
    <Dialog
      open={open}
      onClose={onClose}
      slotProps={{
        paper: {
          sx: { width: 520, maxHeight: '84vh', borderRadius: '16px', bgcolor: 'background.default' }
        }
      }}
    >
      <DialogTitle sx={{ display: 'flex', alignItems: 'center', px: 3, py: 2 }}>
        <Typography variant="subtitle1" sx={{ flex: 1, fontWeight: 600 }}>
          主题色
        </Typography>
        <IconButton size="small" onClick={onClose}>
          <CloseRounded fontSize="small" />
        </IconButton>
      </DialogTitle>
      <DialogContent sx={{ px: 3, pb: 3 }}>
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: 'repeat(4, 1fr)',
            rowGap: 2.5,
            columnGap: 1,
            pt: 1
          }}
        >
          {TILES.map((key) => {
            const selected = seed === key
            return (
              <Box
                key={key}
                role="button"
                aria-label={seedName(key)}
                onClick={() => onPick(key)}
                sx={{
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  gap: 0.75,
                  cursor: 'pointer'
                }}
              >
                <Box
                  sx={(theme) => ({
                    width: 56,
                    height: 56,
                    borderRadius: '50%',
                    bgcolor: seedPrimary(key, dark ? 'dark' : 'light', accent),
                    border: `${selected ? 3 : 1}px solid`,
                    borderColor: selected ? theme.palette.text.primary : theme.palette.divider,
                    display: 'grid',
                    placeItems: 'center',
                    boxSizing: 'border-box',
                    transition: 'border-color 160ms ease'
                  })}
                >
                  {selected && <CheckRounded sx={{ color: '#FFFFFF', fontSize: 24 }} />}
                </Box>
                <Typography
                  variant="caption"
                  sx={{ color: selected ? 'text.primary' : 'text.secondary' }}
                >
                  {seedName(key)}
                </Typography>
              </Box>
            )
          })}
        </Box>
      </DialogContent>
    </Dialog>
  )
}
