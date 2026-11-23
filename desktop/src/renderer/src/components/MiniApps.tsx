import { Box, ButtonBase, Typography } from '@mui/material'
import SmartphoneRounded from '@mui/icons-material/SmartphoneRounded'

export default function MiniApps({ onOpenPhone }: { onOpenPhone: () => void }) {
  return (
    <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
      <Box sx={{ px: 2.5, pt: 2, pb: 1 }}>
        <Typography sx={{ fontSize: 18, fontWeight: 600, color: 'text.primary' }}>小程序</Typography>
      </Box>
      <Box
        sx={{
          flex: 1,
          minHeight: 0,
          overflow: 'auto',
          px: 2.5,
          pt: 1,
          pb: 2,
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))',
          alignContent: 'start',
          gap: 1.5
        }}
      >
        <ButtonBase
          disableRipple
          onClick={onOpenPhone}
          sx={{
            flexDirection: 'column',
            gap: 1,
            py: 2.5,
            borderRadius: 3,
            border: 1,
            borderColor: 'divider',
            transition: 'background-color 140ms ease',
            '&:hover': { bgcolor: 'action.hover' },
            '&:active': { bgcolor: 'action.selected' }
          }}
        >
          <Box
            sx={{
              width: 52,
              height: 52,
              borderRadius: '16px',
              display: 'grid',
              placeItems: 'center',
              bgcolor: 'action.hover',
              color: 'text.secondary'
            }}
          >
            <SmartphoneRounded />
          </Box>
          <Box component="span" sx={{ fontSize: 13, color: 'text.primary' }}>
            手机文件
          </Box>
        </ButtonBase>
      </Box>
    </Box>
  )
}
