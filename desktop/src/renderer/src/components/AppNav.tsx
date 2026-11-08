import type { ReactNode } from 'react'
import { Box, ButtonBase } from '@mui/material'
import AppsRounded from '@mui/icons-material/AppsRounded'
import ChatBubbleOutlineRounded from '@mui/icons-material/ChatBubbleOutlineRounded'
import DeviceHubRounded from '@mui/icons-material/DeviceHubRounded'
import FolderOutlined from '@mui/icons-material/FolderOutlined'

export type AppPage = 'chat' | 'files' | 'lan' | 'apps'

const ITEMS: { id: AppPage; label: string; icon: ReactNode }[] = [
  { id: 'chat', label: '会话', icon: <ChatBubbleOutlineRounded fontSize="small" /> },
  { id: 'files', label: '文件管理', icon: <FolderOutlined fontSize="small" /> },
  { id: 'lan', label: '内网分发', icon: <DeviceHubRounded fontSize="small" /> },
  { id: 'apps', label: '小程序', icon: <AppsRounded fontSize="small" /> }
]

export default function AppNav({
  value,
  onChange
}: {
  value: AppPage
  onChange: (page: AppPage) => void
}) {
  return (
    <Box
      sx={{
        width: 72,
        flexShrink: 0,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: 0.5,
        py: 1,
        borderRight: 1,
        borderColor: 'divider',
        bgcolor: 'background.default'
      }}
    >
      {ITEMS.map((item) => {
        const selected = item.id === value
        return (
          <ButtonBase
            key={item.id}
            disableRipple
            onClick={() => onChange(item.id)}
            sx={{
              width: 64,
              flexDirection: 'column',
              gap: 0.5,
              py: 1,
              borderRadius: '16px',
              overflow: 'hidden',
              transition: 'background-color 140ms ease',
              '&:hover': { bgcolor: 'action.hover' },
              '&:active': { bgcolor: 'action.selected' }
            }}
          >
            <Box
              sx={{
                width: 36,
                height: 32,
                borderRadius: '10px',
                display: 'grid',
                placeItems: 'center',
                bgcolor: selected ? 'secondary.main' : 'transparent',
                color: selected ? 'secondary.contrastText' : 'text.secondary',
                transition: 'background-color 160ms ease, color 160ms ease'
              }}
            >
              {item.icon}
            </Box>
            <Box
              component="span"
              sx={{
                fontSize: 11,
                color: selected ? 'text.primary' : 'text.secondary',
                transition: 'color 160ms ease'
              }}
            >
              {item.label}
            </Box>
          </ButtonBase>
        )
      })}
    </Box>
  )
}
