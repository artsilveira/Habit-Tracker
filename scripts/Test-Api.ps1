param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
function Request($method, $path, $body, $session, $expected = 200) {
    $options = @{ Uri = "$BaseUrl/api$path"; Method = $method; WebSession = $session; SkipHttpErrorCheck = $true; TimeoutSec = 15 }
    if ($null -ne $body) { $options.ContentType = 'application/json'; $options.Body = ConvertTo-Json -InputObject $body -Depth 10 -Compress }
    $response = Invoke-WebRequest @options
    if ([int]$response.StatusCode -ne $expected) { throw "$method $path : esperado $expected, recebido $($response.StatusCode): $($response.Content)" }
    return $response.Content | ConvertFrom-Json
}
$sessionA = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$sessionB = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$suffix = [guid]::NewGuid().ToString('N')
$testEmailA = "test-a-$suffix@example.com"
$testEmailB = "test-b-$suffix@example.com"
$password = "Test-$suffix"
$today = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow, 'E. South America Standard Time').ToString('yyyy-MM-dd')
$yesterday = ([DateTime]::Parse($today)).AddDays(-1).ToString('yyyy-MM-dd')
$tomorrow = ([DateTime]::Parse($today)).AddDays(1).ToString('yyyy-MM-dd')
try {
    Request GET /auth/me $null $sessionA 401 | Out-Null
    # Primeiro login com email ainda inexistente também deve verificar a senha e
    # retornar 401, inclusive com limite curto para transações ociosas no banco.
    Request POST /auth/login @{ email=$testEmailA; password='dummy-password-for-timing-only' } $sessionA 401 | Out-Null
    Request POST /auth/register @{ name='Teste A'; email=$testEmailA; password=$password; timezone='America/Sao_Paulo' } $sessionA | Out-Null
    Request POST /auth/register @{ name='Teste B'; email=$testEmailB; password=$password; timezone='America/Sao_Paulo' } $sessionB | Out-Null
    Request POST /auth/register @{ name='Duplicado'; email=$testEmailA; password=$password; timezone='America/Sao_Paulo' } $sessionB 409 | Out-Null
    $habit = Request POST /habits @{ name='Ler'; startDate=$yesterday } $sessionA
    $other = Request POST /habits @{ name='Caminhar'; startDate=$today } $sessionA
    Request PUT "/habits/$($habit.id)/entries/$today" @{ status='COMPLETED' } $sessionA | Out-Null
    Request PUT "/habits/$($habit.id)/entries/$today" @{ status='COMPLETED' } $sessionA | Out-Null
    Request PUT "/habits/$($habit.id)/entries/$tomorrow" @{ status='COMPLETED' } $sessionA 400 | Out-Null
    Request PUT "/habits/$($habit.id)/entries/1900-01-01" @{ status='COMPLETED' } $sessionA 400 | Out-Null
    Request PUT "/habits/$($habit.id)/entries/$today" @{ status='INVALID' } $sessionA 400 | Out-Null
    Request PATCH "/habits/$($habit.id)" @{ endDate=$yesterday } $sessionA 409 | Out-Null
    Request DELETE "/habits/$($habit.id)" $null $sessionB 404 | Out-Null
    Request PUT "/habits/$($habit.id)/entries/$today" @{ status='COMPLETED' } $sessionB 404 | Out-Null
    Request PUT /habits/order @{ ids=@($other.id,$habit.id) } $sessionA | Out-Null
    Request PUT "/moods/$today" @{ level=4 } $sessionA | Out-Null
    Request PUT "/moods/$today" @{ level=5 } $sessionA | Out-Null
    Request PUT "/moods/$today" @{ level=6 } $sessionA 400 | Out-Null
    Request PUT "/moods/$tomorrow" @{ level=4 } $sessionA 400 | Out-Null
    $parts = $today.Split('-')
    $monthly = Request GET "/tracker?year=$($parts[0])&month=$([int]$parts[1])" $null $sessionA
    if ($monthly.habits[0].id -ne $other.id) { throw 'Reordenacao nao persistiu.' }
    $savedHabit = $monthly.habits | Where-Object id -eq $habit.id
    if ($savedHabit.completed -ne 1) { throw 'Upsert duplicou a conclusao.' }
    if ($monthly.moods.$today -ne 5) { throw 'Humor nao foi atualizado.' }
    $isolated = Request GET "/tracker?year=$($parts[0])&month=$([int]$parts[1])" $null $sessionB
    if ($isolated.habits.Count -ne 0 -or $isolated.moods.PSObject.Properties.Count -gt 0) { throw 'Dados vazaram entre contas.' }
    $leap = Request GET '/tracker?year=2024&month=2' $null $sessionA
    if ($leap.daysInMonth -ne 29) { throw 'Fevereiro bissexto incorreto.' }
    Request DELETE "/habits/$($habit.id)/entries/$today" $null $sessionA | Out-Null
    Request PATCH "/habits/$($habit.id)" @{ endDate=$yesterday } $sessionA | Out-Null
    Request DELETE "/moods/$today" $null $sessionA | Out-Null
    Request DELETE "/habits/$($habit.id)" $null $sessionA | Out-Null
    Request DELETE "/habits/$($other.id)" $null $sessionA | Out-Null
    Request POST /auth/logout $null $sessionA | Out-Null
    Request GET /auth/me $null $sessionA 401 | Out-Null
    Request POST /auth/login @{ email=$testEmailA; password='senha-incorreta' } $sessionA 401 | Out-Null
    Request POST /auth/login @{ email=$testEmailA; password=$password } $sessionA | Out-Null
    Write-Host 'PASS: autenticacao, isolamento, upsert, datas, periodo, ordenacao, humor, historico e exclusao.'
} finally {
    # Only remove the accounts created by this test, when DB_* are available.
    if ($env:DB_URL -and $env:DB_USER -and $env:DB_PASSWORD) {
        $match = [regex]::Match($env:DB_URL, 'jdbc:postgresql://([^:/]+):(\d+)/(\w+)')
        if ($match.Success) {
            $previousPgPassword = $env:PGPASSWORD
            $env:PGPASSWORD = $env:DB_PASSWORD
            try {
                & psql -h $match.Groups[1].Value -p $match.Groups[2].Value -U $env:DB_USER -d $match.Groups[3].Value -v ON_ERROR_STOP=1 -c "DELETE FROM users WHERE email IN ('$testEmailA','$testEmailB')" | Out-Null
            } finally {
                if ($null -eq $previousPgPassword) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
                else { $env:PGPASSWORD = $previousPgPassword }
            }
        }
    }
}
