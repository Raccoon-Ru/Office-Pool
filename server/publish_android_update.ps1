$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$releaseDirectory = Join-Path $PSScriptRoot 'releases'
$manifest = Join-Path $releaseDirectory 'android.json'
$publishedApk = Join-Path $releaseDirectory 'cue-club.apk'

function Get-ApkSignerDigest($signer, $path) {
    $lines = & $signer verify --print-certs $path
    if ($LASTEXITCODE -ne 0) { throw "Could not verify the APK signature at $path." }
    $digest = $lines | Select-String '^Signer #\d+ certificate SHA-256 digest:'
    if (-not $digest) { throw "No signing certificate was found in $path." }
    return ($digest.Line | Sort-Object) -join '|'
}

Push-Location $repository
try {
    & .\gradlew.bat assembleDebug -PcueClubServerUrl=https://office-pool.onrender.com
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
    $buildDirectory = Join-Path $repository 'app/build/outputs/apk/debug'
    $buildMetadata = Join-Path $buildDirectory 'output-metadata.json'
    $build = (Get-Content -LiteralPath $buildMetadata -Raw | ConvertFrom-Json).elements
    if ($build.Count -ne 1) { throw 'Expected exactly one APK.' }
    $apk = $build[0]
    if (Test-Path -LiteralPath $manifest) {
        $publishedVersion = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
        if ($apk.versionCode -le $publishedVersion.versionCode) {
            throw 'Increase versionCode and versionName in app/build.gradle before publishing an update.'
        }
    }
    $candidate = Join-Path $buildDirectory $apk.outputFile
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    $signer = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Filter apksigner.bat -Recurse |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $signer) { throw 'Android SDK Build Tools are required to verify APK signing.' }
    $candidateDigest = Get-ApkSignerDigest $signer $candidate
    if ((Test-Path -LiteralPath $publishedApk) -and $candidateDigest -ne (Get-ApkSignerDigest $signer $publishedApk)) {
        throw 'The new APK must use the same signing certificate as the published APK.'
    }
    New-Item -ItemType Directory -Path $releaseDirectory -Force | Out-Null
    Copy-Item -LiteralPath $candidate -Destination $publishedApk
    @{ versionCode = $apk.versionCode; versionName = $apk.versionName } |
        ConvertTo-Json -Compress | Set-Content -LiteralPath $manifest -Encoding ascii
    Write-Output "Prepared Cue Club $($apk.versionName). Commit the APK and manifest together to publish through Render."
} finally {
    Pop-Location
}
