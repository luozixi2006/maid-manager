$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$pythonPath = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
$tailscalePath = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
if (!(Test-Path -LiteralPath $pythonPath)) { throw 'Missing server virtual environment. See README.md.' }
if (!(Test-Path -LiteralPath $tailscalePath)) { throw 'Tailscale is not installed.' }
$address = (& $tailscalePath ip -4 | Select-Object -First 1).Trim()
if ($LASTEXITCODE -ne 0 -or $address -notmatch '^100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.\d{1,3}\.\d{1,3}$') {
    throw 'Connect Tailscale first.'
}
& $pythonPath (Join-Path $PSScriptRoot 'main.py') --host $address --port 8787
exit $LASTEXITCODE
