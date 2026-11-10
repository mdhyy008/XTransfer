import { execFile } from 'node:child_process'
import os from 'node:os'
import type { HotspotStatus } from '../shared/types'

// 软件内开启的热点统一用这个密码，方便对方直接输入。
const HOTSPOT_PASSWORD = '33554488'

const POWERSHELL = String.raw`
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$result = @{ supported = $true; running = $false; ssid = ""; password = ""; clients = 0; message = "" }
$manager = $null
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  $null = [Windows.Networking.Connectivity.NetworkInformation, Windows.Networking.Connectivity, ContentType=WindowsRuntime]
  $null = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager, Windows.Networking.NetworkOperators, ContentType=WindowsRuntime]
  $null = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult, Windows.Networking.NetworkOperators, ContentType=WindowsRuntime]
  $null = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringAccessPointConfiguration, Windows.Networking.NetworkOperators, ContentType=WindowsRuntime]
  $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq "AsTask" -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -like "IAsyncOperation*" })[0]
  $asTaskAction = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq "AsTask" -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq "IAsyncAction" })[0]
  function Await($task, $type) {
    $netTask = $asTask.MakeGenericMethod($type).Invoke($null, @($task))
    $netTask.Wait(-1) | Out-Null
    $netTask.Result
  }
  function AwaitAction($action) {
    $netTask = $asTaskAction.Invoke($null, @($action))
    $netTask.Wait(-1) | Out-Null
  }
  $profile = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
  if ($null -eq $profile) { throw "没有可共享的网络连接" }
  $manager = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)
} catch {
  $result.message = $_.Exception.Message
}
if ($null -ne $manager) {
  try {
    $mode = $env:XLAN_HOTSPOT_MODE
    if ($mode -eq "start" -and $manager.TetheringOperationalState.ToString() -ne "On") {
      $wanted = $env:XLAN_HOTSPOT_PASSWORD
      if ($wanted) {
        $current = $manager.GetCurrentAccessPointConfiguration()
        if ($current.Passphrase -ne $wanted) {
          $next = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringAccessPointConfiguration]::new()
          $next.Ssid = $current.Ssid
          $next.Passphrase = $wanted
          AwaitAction ($manager.ConfigureAccessPointAsync($next))
        }
      }
      $op = Await $manager.StartTetheringAsync() ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])
      if ($op.Status.ToString() -ne "Success") { throw ("开启失败：" + $op.Status.ToString()) }
    }
    if ($mode -eq "stop" -and $manager.TetheringOperationalState.ToString() -eq "On") {
      $op = Await $manager.StopTetheringAsync() ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])
      if ($op.Status.ToString() -ne "Success") { throw ("关闭失败：" + $op.Status.ToString()) }
    }
    $result.running = $manager.TetheringOperationalState.ToString() -eq "On"
    if ($result.running) {
      $config = $manager.GetCurrentAccessPointConfiguration()
      $result.ssid = [string]$config.Ssid
      $result.password = [string]$config.Passphrase
      try { $result.clients = @($manager.GetTetheringClients()).Count } catch { $result.clients = 0 }
    }
  } catch {
    $result.message = $_.Exception.Message
  }
}
$result | ConvertTo-Json -Compress
`

// 系统移动热点的网卡（中文「本地连接* 10」，英文「Local Area Connection* 10」）。
const HOTSPOT_IFACE = /^(本地连接|local area connection)\s*\*/i

function prefixOf(netmask: string): number {
  return netmask.split('.').reduce((sum, part) => {
    const ones = Number(part).toString(2).replace(/0/g, '').length
    return sum + ones
  }, 0)
}

function hotspotAddress(): string {
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    if (!HOTSPOT_IFACE.test(name.trim())) continue
    for (const info of list ?? []) {
      if (info.family === 'IPv4' && !info.internal) {
        return `${info.address}/${prefixOf(info.netmask)}`
      }
    }
  }
  return ''
}

function supportedPlatform(): boolean {
  if (process.platform !== 'win32') return false
  const build = Number(os.release().split('.')[2] ?? 0)
  // 移动热点接口从 Windows 10 1803 开始提供。
  return build >= 17134
}

function empty(message?: string): HotspotStatus {
  return { supported: supportedPlatform(), running: false, ssid: '', password: '', message }
}

// 记录本次运行中观察到热点在跑的时间点，用来展示开启时间和已开启时长。
let runningSince: number | null = null

function run(mode: 'status' | 'start' | 'stop'): Promise<HotspotStatus> {
  if (!supportedPlatform()) {
    return Promise.resolve({ supported: false, running: false, ssid: '', password: '' })
  }
  return new Promise((resolve) => {
    execFile(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', POWERSHELL],
      {
        env: { ...process.env, XLAN_HOTSPOT_MODE: mode, XLAN_HOTSPOT_PASSWORD: HOTSPOT_PASSWORD },
        timeout: mode === 'start' ? 45000 : 20000,
        windowsHide: true,
        maxBuffer: 1024 * 1024
      },
      (error, stdout, stderr) => {
        const line = String(stdout ?? '')
          .trim()
          .split(/\r?\n/)
          .pop()
        if (!line) {
          resolve(empty(error ? String(stderr ?? error.message).trim() || '热点操作失败' : undefined))
          return
        }
        try {
          const data = JSON.parse(line) as {
            supported?: boolean
            running?: boolean
            ssid?: string
            password?: string
            clients?: number
            message?: string
          }
          const running = data.running === true
          if (running) {
            runningSince ??= Date.now()
          } else {
            runningSince = null
          }
          resolve({
            supported: data.supported !== false,
            running,
            ssid: String(data.ssid ?? ''),
            password: String(data.password ?? ''),
            message: data.message ? String(data.message) : undefined,
            address: running ? hotspotAddress() : undefined,
            clients: running ? Number(data.clients ?? 0) : undefined,
            startedAt: running ? (runningSince ?? undefined) : undefined
          })
        } catch {
          resolve(empty('热点操作失败'))
        }
      }
    )
  })
}

let queue: Promise<unknown> = Promise.resolve()

function serialized<T>(task: () => Promise<T>): Promise<T> {
  const next = queue.then(task, task)
  queue = next.catch(() => undefined)
  return next
}

export function hotspotStatus(): Promise<HotspotStatus> {
  return serialized(() => run('status'))
}

export function hotspotStart(): Promise<HotspotStatus> {
  return serialized(() => run('start'))
}

export function hotspotStop(): Promise<HotspotStatus> {
  return serialized(() => run('stop'))
}
