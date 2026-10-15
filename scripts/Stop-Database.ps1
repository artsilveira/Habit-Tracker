param([string]$PostgresBin = '')
$ErrorActionPreference = 'Stop'
if (-not $PostgresBin) { $PostgresBin = Split-Path (Get-Command psql -ErrorAction Stop).Source -Parent }
$projectRoot = Split-Path $PSScriptRoot -Parent
$dataPath = Join-Path $projectRoot '.tools/postgres-data'
if (Test-Path -LiteralPath (Join-Path $dataPath 'PG_VERSION')) {
    & (Join-Path $PostgresBin 'pg_ctl.exe') -D $dataPath -m fast -w stop
    if ($LASTEXITCODE -ne 0) { throw 'Nao foi possivel parar o banco local.' }
}
