$ErrorActionPreference = 'Stop'
$frontendPath = Join-Path (Split-Path $PSScriptRoot -Parent) 'frontend'
$vitePath = Join-Path $frontendPath 'node_modules/vite/bin/vite.js'
if (-not (Test-Path -LiteralPath $vitePath)) {
    throw 'Instale as dependencias na pasta frontend com npm install ou pnpm install.'
}
$node = (Get-Command node -ErrorAction Stop).Source
Push-Location $frontendPath
try { & $node $vitePath --host 127.0.0.1 }
finally { Pop-Location }
