"""改完代码后重启 Windows 桌面客户端（electron-vite dev）。

只结束命令行走本目录的 electron.exe / node.exe，不碰其它 Electron 应用。
"""

import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
POWERSHELL = "powershell.exe"

KILL_RELATED = r"""
$root = $env:XT_RESTART_ROOT.ToLower()
Get-CimInstance Win32_Process |
  Where-Object {
    ($_.Name -eq 'electron.exe' -or $_.Name -eq 'node.exe') -and
    $_.CommandLine -and $_.CommandLine.ToLower().Contains($root)
  } |
  ForEach-Object {
    Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    $_.ProcessId
  }
"""

COUNT_ELECTRON = r"""
$root = $env:XT_RESTART_ROOT.ToLower()
@(Get-CimInstance Win32_Process |
  Where-Object {
    $_.Name -eq 'electron.exe' -and
    $_.CommandLine -and $_.CommandLine.ToLower().Contains($root)
  }).Count
"""


def run_ps(script: str) -> str:
    env = dict(os.environ, XT_RESTART_ROOT=str(ROOT))
    result = subprocess.run(
        [POWERSHELL, "-NoProfile", "-NonInteractive", "-Command", script],
        capture_output=True,
        text=True,
        env=env,
    )
    return result.stdout.strip()


def electron_running() -> bool:
    try:
        return int(run_ps(COUNT_ELECTRON) or 0) > 0
    except ValueError:
        return False


def main() -> int:
    if os.name != "nt":
        print("这个脚本只用于 Windows")
        return 1

    killed = run_ps(KILL_RELATED)
    if killed:
        print("已结束进程：" + " ".join(killed.split()))
    else:
        print("没有正在运行的实例")

    deadline = time.time() + 10
    while electron_running() and time.time() < deadline:
        time.sleep(0.3)

    node_modules = ROOT / "node_modules"
    log_path = (node_modules if node_modules.is_dir() else ROOT) / ".dev.log"
    log = open(log_path, "wb", buffering=0)
    comspec = os.environ.get("COMSPEC", "cmd.exe")
    subprocess.Popen(
        [comspec, "/c", "npm", "run", "dev"],
        cwd=str(ROOT),
        stdin=subprocess.DEVNULL,
        stdout=log,
        stderr=subprocess.STDOUT,
        creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP,
        close_fds=True,
    )
    print("启动中…")

    deadline = time.time() + 40
    while time.time() < deadline:
        if electron_running():
            print("已启动")
            return 0
        time.sleep(0.7)

    print(f"没等到 Electron 启动，请查看 {log_path}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
