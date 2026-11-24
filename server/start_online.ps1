$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$tailscale = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'

if (-not (Test-Path $tailscale)) {
    throw 'Tailscale is not installed. Install it from https://tailscale.com/download/windows.'
}
$status = (& $tailscale status 2>&1 | Out-String)
if ($status -match 'Logged out') {
    throw 'Sign in to Tailscale on this PC first, then run start_online.bat again.'
}

$probe = [System.Net.Sockets.TcpClient]::new()
$serverAlreadyRunning = $false
try {
    $probe.Connect('127.0.0.1', 8765)
    $serverAlreadyRunning = $true
} catch {
    $serverAlreadyRunning = $false
} finally {
    $probe.Dispose()
}

if (-not $serverAlreadyRunning) {
    $python = (Get-Command py.exe -ErrorAction Stop).Source
    Start-Process -FilePath $python -ArgumentList @('-3', '-u', "`"$serverRoot\office_pool_server.py`"") -WorkingDirectory $serverRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $serverRoot 'server.log') -RedirectStandardError (Join-Path $serverRoot 'server-error.log')
    $ready = $false
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        try {
            Invoke-RestMethod -Uri 'http://127.0.0.1:8765/health' -TimeoutSec 2 | Out-Null
            $ready = $true
            break
        } catch { }
    }
    if (-not $ready) { throw 'The local league server did not start. Check server-error.log.' }
}

Write-Host 'Enabling the public HTTPS address through Tailscale Funnel...'
& $tailscale funnel --bg 8765
if ($LASTEXITCODE -ne 0) { throw 'Tailscale Funnel did not start. Complete its one-time setup and try again.' }
Write-Host ''
Write-Host 'The address above is the server URL to enter in Cue Club on each phone.'
Write-Host 'The server and Funnel must remain running for the app to show Online.'
