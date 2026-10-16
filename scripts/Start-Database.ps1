param([int]$Port = 55432, [string]$PostgresBin = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolsPath = Join-Path $projectRoot '.tools'
$dataPath = Join-Path $toolsPath 'postgres-data'
$configPath = Join-Path $toolsPath 'local-db.json'
function Protect-LocalFile([string]$Path) {
    if ($IsWindows) {
        $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
        $permissions = [System.Security.AccessControl.FileSecurity]::new()
        $permissions.SetAccessRuleProtection($true, $false)
        $permissions.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($currentUser, 'FullControl', 'Allow'))
        [System.IO.FileSystemAclExtensions]::SetAccessControl([System.IO.FileInfo]::new($Path), $permissions)
    }
}
if (-not $PostgresBin) {
    $psqlCommand = Get-Command psql -ErrorAction Stop
    $PostgresBin = Split-Path $psqlCommand.Source -Parent
}
New-Item -ItemType Directory -Force $toolsPath | Out-Null
if (-not (Test-Path -LiteralPath $configPath)) {
    $secretBytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($secretBytes)
    @{ port = $Port; password = [Convert]::ToBase64String($secretBytes) } | ConvertTo-Json | Set-Content -LiteralPath $configPath
}
$localConfig = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
if (-not $localConfig.appPassword) {
    $appSecret = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($appSecret)
    $localConfig | Add-Member -NotePropertyName appPassword -NotePropertyValue ([Convert]::ToBase64String($appSecret))
    $localConfig | ConvertTo-Json | Set-Content -LiteralPath $configPath
}
Protect-LocalFile $configPath
$Port = [int]$localConfig.port
$env:DB_URL = "jdbc:postgresql://127.0.0.1:$Port/habit_tracker"
$previousPgPassword = $env:PGPASSWORD
$env:PGPASSWORD = $localConfig.password
try {
    if (-not (Test-Path -LiteralPath (Join-Path $dataPath 'PG_VERSION'))) {
        $passwordFile = Join-Path $toolsPath 'initdb-password.tmp'
        try {
            Set-Content -LiteralPath $passwordFile -Value $localConfig.password -NoNewline
            Protect-LocalFile $passwordFile
            & (Join-Path $PostgresBin 'initdb.exe') -D $dataPath -U habit_tracker --auth=scram-sha-256 --encoding=UTF8 --locale=C "--pwfile=$passwordFile"
            if ($LASTEXITCODE -ne 0) { throw 'Falha ao inicializar o banco local.' }
        } finally { Remove-Item -LiteralPath $passwordFile -ErrorAction SilentlyContinue }
    }
    & (Join-Path $PostgresBin 'pg_ctl.exe') -D $dataPath status *> $null
    if ($LASTEXITCODE -ne 0) {
        & (Join-Path $PostgresBin 'pg_ctl.exe') -D $dataPath -l (Join-Path $toolsPath 'postgres.log') -o "-h 127.0.0.1 -p $Port" -w start
        if ($LASTEXITCODE -ne 0) { throw 'Falha ao iniciar o banco local.' }
    }
    $exists = & (Join-Path $PostgresBin 'psql.exe') -h 127.0.0.1 -p $Port -U habit_tracker -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='habit_tracker'"
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao consultar o banco local.' }
    if ($exists -ne '1') {
        & (Join-Path $PostgresBin 'createdb.exe') -h 127.0.0.1 -p $Port -U habit_tracker habit_tracker
        if ($LASTEXITCODE -ne 0) { throw 'Falha ao criar habit_tracker.' }
    }
    $schemaExists = & (Join-Path $PostgresBin 'psql.exe') -h 127.0.0.1 -p $Port -U habit_tracker -d habit_tracker -tAc "SELECT to_regclass('public.users') IS NOT NULL"
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao consultar o esquema.' }
    if ($schemaExists -eq 'f') {
        & (Join-Path $PostgresBin 'psql.exe') -h 127.0.0.1 -p $Port -U habit_tracker -d habit_tracker -v ON_ERROR_STOP=1 -f (Join-Path $projectRoot 'database/migrations/001_initial_schema.sql')
        if ($LASTEXITCODE -ne 0) { throw 'Falha ao aplicar a migracao.' }
    }
    $sqlPassword = $localConfig.appPassword.Replace("'", "''")
    $roleSql = @"
SELECT format('CREATE ROLE habit_tracker_app LOGIN PASSWORD %L', '$sqlPassword')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='habit_tracker_app')
\gexec
ALTER ROLE habit_tracker_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT CONNECT ON DATABASE habit_tracker TO habit_tracker_app;
GRANT USAGE ON SCHEMA public TO habit_tracker_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO habit_tracker_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO habit_tracker_app;
"@
    $roleSql | & (Join-Path $PostgresBin 'psql.exe') -h 127.0.0.1 -p $Port -U habit_tracker -d habit_tracker -v ON_ERROR_STOP=1 -q
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao configurar o usuario da API.' }
    $env:DB_USER = 'habit_tracker_app'
    $env:DB_PASSWORD = $localConfig.appPassword
    Write-Host "Banco local disponivel em 127.0.0.1:$Port. API configurada com usuario sem privilegios administrativos."
} finally {
    if ($null -eq $previousPgPassword) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
    else { $env:PGPASSWORD = $previousPgPassword }
}
