# 从雷电模拟器抓取屏幕截图（不依赖 adb）：ld.exe 进 VM 执行 screencap，再以 base64 文本带出。
# 用法: pwsh -File shot.ps1 <输出文件名(不含路径)>
param([string]$Name = "shot.png")

$ld = (Get-Item 'D:\xinjian2\LDPlayer*AndroidEmulator\leidian\LDPlayer14').FullName
$sh = Join-Path $ld 'ld.exe'
$outDir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'screenshots'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

& $sh -s 0 screencap -p /sdcard/_shot.png | Out-Null
$b64 = (& $sh -s 0 base64 -w 0 /sdcard/_shot.png) -join ''
$bytes = [Convert]::FromBase64String($b64.Trim())
$path = Join-Path $outDir $Name
[IO.File]::WriteAllBytes($path, $bytes)
Write-Host "$path ($([math]::Round($bytes.Length/1KB)) KB)"
