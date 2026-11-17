param([string]$JavaHome = $env:JAVA_HOME, [string]$SdkRoot = $env:ANDROID_HOME, [switch]$DebugApk)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $JavaHome -or -not (Test-Path "$JavaHome/bin/java.exe")) { throw '请先安装 JDK 17，并设置 JAVA_HOME，或传入 -JavaHome 路径。' }
if (-not $SdkRoot -or -not (Test-Path $SdkRoot)) { throw '请先通过 Android Studio 安装 Android SDK 35，并设置 ANDROID_HOME，或传入 -SdkRoot 路径。' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkRoot
$env:Path = "$JavaHome/bin;" + $env:Path
$toolsRoot = Join-Path $projectRoot '.tools'
$gradleRoot = Join-Path $toolsRoot 'gradle-8.9'
if (-not (Test-Path "$gradleRoot/bin/gradle.bat")) {
    New-Item -ItemType Directory -Force $toolsRoot | Out-Null
    $archive = Join-Path $toolsRoot 'gradle-8.9-bin.zip'
    Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' -OutFile $archive
    $expected = (Invoke-RestMethod 'https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256').Trim()
    if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLower() -ne $expected.ToLower()) { throw 'Gradle 下载校验失败' }
    Expand-Archive -LiteralPath $archive -DestinationPath $toolsRoot -Force
}
Push-Location $projectRoot
try {
    $mode = if ($DebugApk) { 'Debug' } else { 'Release' }
    $module = 'app'
    $buildArguments = @('--no-daemon', ":${module}:assemble${mode}")
    & "$gradleRoot/bin/gradle.bat" @buildArguments
    if ($LASTEXITCODE -ne 0) { throw 'APK 构建失败' }
    $folder = $mode.ToLower()
    Get-ChildItem "$projectRoot/$module/build/outputs/apk/$folder/*.apk" | ForEach-Object { Write-Host "APK: $($_.FullName)" }
}
finally { Pop-Location }
