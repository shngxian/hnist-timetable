# 湖理课表 - 构建脚本
# AGP/aapt2 不支持含中文的项目路径，因此先把源码镜像到 ASCII 目录再编译，
# 编译完成后把 APK 拷回工作区。用 PowerShell 7 运行： pwsh -File build.ps1

$ErrorActionPreference = 'Stop'

$Workspace = Split-Path -Parent $MyInvocation.MyCommand.Path
$Source    = Join-Path $Workspace 'HNISTTimetable'
$BuildDir  = 'D:\hnist_build\HNISTTimetable'
$Gradle    = Join-Path $Workspace 'build-tools\gradle-8.7\bin\gradle.bat'
$JavaHome  = 'D:\xinjian2\jave\b'   # Microsoft OpenJDK 17（完整 JDK，含 jlink）
$Variant   = if ($args.Count -gt 0) { $args[0] } else { 'Release' }

New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

Write-Host "==> 同步源码到 $BuildDir"
# /MIR 镜像；排除构建产物目录。robocopy 返回码 < 8 都表示成功。
robocopy $Source $BuildDir /MIR /XD build .gradle .idea .kotlin /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy 失败，返回码 $LASTEXITCODE" }

# 镜像里的旧编译产物/增量缓存会导致“源码换了两版、个别类还是上一版编译的”
# （v1.5.4/v1.6.0 的闪退就是这么来的），所以每次都把编译产物整个清掉再编。
Write-Host "==> 清理镜像里的旧编译产物"
foreach ($stale in @('app\build', 'app\.gradle', 'app\.kotlin', 'build', '.gradle', '.kotlin')) {
    $p = Join-Path $BuildDir $stale
    if (Test-Path $p) { Remove-Item $p -Recurse -Force }
}

Write-Host "==> Gradle 编译 assemble$Variant"
$env:JAVA_HOME = $JavaHome
& $Gradle -p $BuildDir ":app:assemble$Variant" --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle 编译失败，返回码 $LASTEXITCODE" }

$apkName = if ($Variant -eq 'Release') { 'app-release.apk' } else { 'app-debug.apk' }
$apk = Join-Path $BuildDir "app\build\outputs\apk\$($Variant.ToLower())\$apkName"
$dest = if ($Variant -eq 'Release') { Join-Path $Workspace '湖理课表.apk' } else { Join-Path $Workspace '湖理课表-debug.apk' }
Copy-Item $apk $dest -Force

$size = [math]::Round((Get-Item $dest).Length / 1MB, 2)
Write-Host "==> 完成：$dest ($size MB)"
