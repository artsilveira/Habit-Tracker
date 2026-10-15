param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
$testEmail = "security-$([guid]::NewGuid().ToString('N'))@example.com"
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
function Send-TestRequest($method, $path, $body = $null, $expected = 200, $headers = @{}, $contentType = 'application/json') {
    # Invoke-WebRequest preserva cabeçalhos personalizados na WebSession.
    $session.Headers.Clear()
    $options = @{ Uri="$BaseUrl/api$path"; Method=$method; WebSession=$session; SkipHttpErrorCheck=$true; TimeoutSec=15; Headers=$headers }
    if ($null -ne $body) {
        $options.ContentType = $contentType
        $options.Body = if ($body -is [string]) { $body } else { ConvertTo-Json -InputObject $body -Compress }
    }
    $response = Invoke-WebRequest @options
    if ([int]$response.StatusCode -ne $expected) { throw "$method $path : esperado $expected, recebido $($response.StatusCode): $($response.Content)" }
    return $response
}
try {
    $registered = Send-TestRequest POST /auth/register @{name='Security Test'; email=$testEmail; password='uma-senha-longa-para-os-testes'; timezone='America/Sao_Paulo'}
    $cookie = $registered.Headers['Set-Cookie'] -join ';'
    if ($cookie -notmatch 'HttpOnly' -or $cookie -notmatch 'SameSite=Strict' -or $cookie -notmatch 'Path=/api') { throw 'Flags do cookie ausentes.' }
    if ($registered.Content -match 'password|token|hash') { throw 'Credenciais expostas no corpo da resposta.' }
    $me = Send-TestRequest GET /auth/me
    if ($me.Headers['Set-Cookie']) { throw 'Cookie de outra requisicao reutilizado.' }
    if (($me.Headers['X-Content-Type-Options'] -join '') -ne 'nosniff') { throw 'Cabecalho de seguranca ausente.' }
    Send-TestRequest POST /auth/logout $null 403 @{Origin='https://evil.example'} | Out-Null
    Send-TestRequest POST /auth/logout $null 403 @{'Sec-Fetch-Site'='cross-site'} | Out-Null
    Send-TestRequest GET /auth/me | Out-Null
    Send-TestRequest POST /habits '{"name":"Ler","startDate":"2026-10-01"}' 415 @{} 'text/plain' | Out-Null
    Send-TestRequest POST /habits '{"name":"um","name":"dois","startDate":"2026-10-01"}' 400 | Out-Null
    Send-TestRequest POST /habits '{} {}' 400 | Out-Null
    Send-TestRequest POST /habits '[]' 400 | Out-Null
    Send-TestRequest POST /habits ('{"name":"' + ('a' * 17000) + '"}') 413 | Out-Null
    Send-TestRequest POST /habits @{name="linha`nquebrada"; startDate='2026-10-01'} 400 | Out-Null
    Send-TestRequest GET /auth/login $null 405 | Out-Null
    $duplicate = Send-TestRequest POST /auth/register @{name='Duplicate'; email=$testEmail; password='uma-senha-longa-para-os-testes'; timezone='America/Sao_Paulo'} 409
    if ($duplicate.Headers['Set-Cookie']) { throw 'Falha de cadastro enviou cookie de sessao.' }
    Send-TestRequest POST /auth/register @{name='Weak'; email="weak-$testEmail"; password='12345678'; timezone='America/Sao_Paulo'} 400 | Out-Null
    $sqlName = "x'); DROP TABLE users; --"
    Send-TestRequest POST /habits @{name=$sqlName; startDate='2026-10-01'} | Out-Null
    $tracker = (Send-TestRequest GET '/tracker?year=2026&month=10').Content | ConvertFrom-Json
    if ($tracker.habits[0].name -ne $sqlName) { throw 'Nome nao foi tratado como texto literal.' }
    Send-TestRequest GET /auth/me | Out-Null
    Send-TestRequest POST /auth/logout | Out-Null
    Send-TestRequest POST /auth/logout | Out-Null
    $blocked = $false
    $guessEmail = "guess-$testEmail"
    for ($attempt = 0; $attempt -lt 12; $attempt++) {
        $response = Invoke-WebRequest "$BaseUrl/api/auth/login" -Method POST -ContentType application/json -Body (@{email=$guessEmail; password='senha-incorreta'} | ConvertTo-Json -Compress) -SkipHttpErrorCheck -TimeoutSec 15
        if ([int]$response.StatusCode -eq 429) {
            if ([int]($response.Headers['Retry-After'] -join '') -le 0) { throw 'Retry-After ausente.' }
            $blocked = $true
            break
        }
        if ([int]$response.StatusCode -ne 401) { throw "Resposta inesperada no limite de login: $($response.StatusCode)" }
    }
    if (-not $blocked) { throw 'Limite de tentativas nao foi aplicado.' }
    Write-Host 'PASS: cookies, CSRF, JSON estrito, tamanho do corpo, SQL parametrizado, senha e limite de login.'
} finally {
    if ($env:DB_URL -and $env:DB_USER -and $env:DB_PASSWORD) {
        $db = [regex]::Match($env:DB_URL, 'jdbc:postgresql://([^:/]+):(\d+)/(\w+)')
        if ($db.Success) {
            $previousPassword = $env:PGPASSWORD
            $env:PGPASSWORD = $env:DB_PASSWORD
            try {
                & psql -h $db.Groups[1].Value -p $db.Groups[2].Value -U $env:DB_USER -d $db.Groups[3].Value -v ON_ERROR_STOP=1 -c "DELETE FROM users WHERE email='$testEmail'" | Out-Null
            } finally {
                if ($null -eq $previousPassword) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
                else { $env:PGPASSWORD = $previousPassword }
            }
        }
    }
}
