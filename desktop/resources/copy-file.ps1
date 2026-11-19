param(
    [Parameter(Mandatory = $true)][string]$Path
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    exit 4
}

Add-Type -AssemblyName System.Windows.Forms
$list = New-Object System.Collections.Specialized.StringCollection
[void]$list.Add((Resolve-Path -LiteralPath $Path).Path)
[System.Windows.Forms.Clipboard]::SetFileDropList($list)
exit 0
