param([switch]$Test)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$jdk = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '.tools') -Directory -Filter 'jdk-17*' | Select-Object -First 1
if (-not $jdk) { throw 'JDK 17 is missing from .tools. Install JDK 17 before building.' }
$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = Join-Path $PSScriptRoot '.tools\android-sdk'
$gradle = Join-Path $PSScriptRoot '.tools\gradle-8.11.1\bin\gradle.bat'
$tasks = @('assembleRelease', 'lint', '--console=plain')
if ($Test) { $tasks += 'connectedDebugAndroidTest' }
& $gradle @tasks
if ($LASTEXITCODE -ne 0) { throw 'Android build or verification failed.' }
$apksigner = Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\apksigner.bat'
& $apksigner verify 'app\build\outputs\apk\release\app-release.apk' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Release APK is not signed or signature verification failed.' }
New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot 'delivery') | Out-Null
$versionLine = Get-Content -LiteralPath 'gradle.properties' | Where-Object { $_ -match '^appVersionName=' } | Select-Object -First 1
if (-not $versionLine) { throw 'appVersionName is missing from gradle.properties.' }
$versionName = ($versionLine -split '=',2)[1].Trim()
$apkPath = Join-Path $PSScriptRoot "delivery\PaperPDF-$versionName.apk"
Copy-Item -LiteralPath 'app\build\outputs\apk\release\app-release.apk' -Destination $apkPath -Force
Get-FileHash -LiteralPath $apkPath -Algorithm SHA256
