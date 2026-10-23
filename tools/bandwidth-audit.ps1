param([Parameter(Mandatory=$true)][string]$InputAudio)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskInput = (Resolve-Path -LiteralPath $InputAudio).Path
if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 11 first.' }
$taskJavac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$taskJava = Join-Path $env:JAVA_HOME 'bin/java.exe'
$taskOutput = Join-Path $taskRoot ('build/bandwidth-audit/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
Push-Location $taskRoot
try {
    & .\gradlew.bat classes --offline
    if ($LASTEXITCODE -ne 0) { throw 'Build failed. Resolve the Gradle error before measuring.' }
    $taskClasses = Join-Path $taskRoot 'build/classes/java/main'
    & $taskJavac --release 11 -cp $taskClasses -d $taskOutput (Join-Path $PSScriptRoot 'BandwidthAudit.java')
    if ($LASTEXITCODE -ne 0) { throw 'Audit compilation failed.' }
    & $taskJava -cp ($taskOutput + [IO.Path]::PathSeparator + $taskClasses) BandwidthAudit $taskInput |
        Tee-Object -FilePath (Join-Path $taskOutput 'results.csv')
    if ($LASTEXITCODE -ne 0) { throw 'Audio measurement failed.' }
    Write-Output ('Results: ' + (Join-Path $taskOutput 'results.csv'))
} finally { Pop-Location }
