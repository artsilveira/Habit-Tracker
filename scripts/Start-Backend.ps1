$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $env:DB_URL -or -not $env:DB_USER -or -not $env:DB_PASSWORD) {
    throw 'Defina DB_URL, DB_USER e DB_PASSWORD ou execute primeiro ./scripts/Start-Database.ps1 no mesmo terminal.'
}
$mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
$localMaven = Join-Path $projectRoot '.tools/apache-maven-3.9.11/bin/mvn.cmd'
if ($mavenCommand) { $maven = $mavenCommand.Source }
elseif (Test-Path -LiteralPath $localMaven) { $maven = $localMaven }
else { throw 'Instale Maven 3.9+ ou configure-o na IDE.' }
if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin/java.exe' }
else { $java = (Get-Command java -ErrorAction Stop).Source }
Push-Location $projectRoot
try {
    # Usar somente as versões resolvidas, sem carregar jars antigos deixados em target.
    & $maven -B -f backend/pom.xml "-Dmaven.repo.local=$projectRoot/.tools/m2" compile dependency:build-classpath '-DincludeScope=runtime' "-Dmdep.outputFile=$projectRoot/backend/target/runtime-classpath.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao compilar ou resolver o classpath.' }
    $runtimeClasspath = (Get-Content -LiteralPath "$projectRoot/backend/target/runtime-classpath.txt" -Raw).Trim()
    & $java -cp "$projectRoot/backend/target/classes;$runtimeClasspath" br.com.habittracker.Main
} finally { Pop-Location }
