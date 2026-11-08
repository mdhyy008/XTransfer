import ComputerRounded from '@mui/icons-material/ComputerRounded'
import DesktopWindowsRounded from '@mui/icons-material/DesktopWindowsRounded'
import DevicesRounded from '@mui/icons-material/DevicesRounded'
import LaptopMacRounded from '@mui/icons-material/LaptopMacRounded'
import PhoneAndroidRounded from '@mui/icons-material/PhoneAndroidRounded'

export default function PlatformIcon({ platform }: { platform?: string }) {
  const value = (platform ?? '').toLowerCase()
  if (value.includes('android')) return <PhoneAndroidRounded fontSize="small" />
  if (value.includes('windows') || value.includes('win32')) {
    return <DesktopWindowsRounded fontSize="small" />
  }
  if (value.includes('darwin') || value.includes('mac') || value.includes('ios')) {
    return <LaptopMacRounded fontSize="small" />
  }
  if (value.includes('linux')) return <ComputerRounded fontSize="small" />
  return <DevicesRounded fontSize="small" />
}
