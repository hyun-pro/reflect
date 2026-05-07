# Reflect 릴리즈 자동화
#
# 사용:
#   pwsh scripts/release.ps1 0.1.1
#   pwsh scripts/release.ps1 0.2.0 -Notes "새 메신저 추가"
#
# 동작:
#   1. android/app/build.gradle.kts 의 versionName + versionCode 업데이트
#   2. 안드로이드 빌드 (assembleDebug)
#   3. APK 두 가지 이름으로 복사 (reflect-vX.Y.Z.apk + reflect.apk)
#   4. git commit + tag + push
#   5. gh release create + APK 두 개 업로드
#   6. (옵션) wrangler deploy
param(
    [Parameter(Mandatory=$true)] [string] $Version,
    [string] $Notes = "",
    [switch] $SkipBackend
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$gradleFile = Join-Path $root "android/app/build.gradle.kts"
$apkSrc = Join-Path $root "android/app/build/outputs/apk/debug/app-debug.apk"
$apkOut1 = Join-Path $root "android/app/build/outputs/apk/debug/reflect-v$Version.apk"
$apkOut2 = Join-Path $root "android/app/build/outputs/apk/debug/reflect.apk"

# 1. 버전 bump
Write-Host "▸ versionName/versionCode 업데이트..."
$parts = $Version.Split('.')
$versionCode = ([int]$parts[0]) * 10000 + ([int]$parts[1]) * 100 + ([int]$parts[2])
$content = Get-Content $gradleFile -Raw
$content = $content -replace 'versionCode\s*=\s*\d+', "versionCode = $versionCode"
$content = $content -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$Version`""
$content | Set-Content $gradleFile -Encoding utf8 -NoNewline
Write-Host "  versionName=$Version, versionCode=$versionCode"

# 2. 빌드
Write-Host "▸ 안드로이드 빌드..."
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
Push-Location (Join-Path $root "android")
try {
    & "$env:JAVA_HOME\bin\java.exe" "-Dorg.gradle.appname=gradlew" `
        -classpath "gradle\wrapper\gradle-wrapper.jar" `
        "org.gradle.wrapper.GradleWrapperMain" assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally {
    Pop-Location
}

# 3. APK 두 가지 이름으로 복사
Copy-Item $apkSrc $apkOut1 -Force
Copy-Item $apkSrc $apkOut2 -Force
Write-Host "  -> $apkOut1"
Write-Host "  -> $apkOut2"

# 4. git commit + push
Push-Location $root
try {
    git add android/app/build.gradle.kts
    git commit -m "Release v$Version"
    git tag "v$Version"
    git push origin main --tags
} finally {
    Pop-Location
}

# 5. GitHub release
$notesText = if ($Notes) { $Notes } else { "v$Version" }
$notesFile = Join-Path $env:TEMP "release-notes-$Version.md"
$notesText | Out-File $notesFile -Encoding utf8 -NoNewline
Push-Location $root
try {
    gh release create "v$Version" $apkOut1 $apkOut2 --title "v$Version" --notes-file $notesFile
} finally {
    Pop-Location
    Remove-Item $notesFile -ErrorAction SilentlyContinue
}

# 6. 백엔드 재배포 (옵션)
if (-not $SkipBackend) {
    Write-Host "▸ Cloudflare 재배포..."
    if (-not $env:CLOUDFLARE_API_TOKEN) {
        $envFile = Join-Path $root ".env"
        Get-Content $envFile -Encoding UTF8 | ForEach-Object {
            if ($_ -match '^CLOUDFLARE_API_TOKEN=(.+)$') {
                $env:CLOUDFLARE_API_TOKEN = $Matches[1]
            }
        }
    }
    Push-Location (Join-Path $root "backend")
    try {
        npx wrangler deploy
    } finally {
        Pop-Location
    }
}

Write-Host ""
Write-Host "=========================================="
Write-Host "  v$Version 배포 완료"
Write-Host "  APK URL: https://github.com/hyun-pro/reflect/releases/latest/download/reflect.apk"
Write-Host "=========================================="
