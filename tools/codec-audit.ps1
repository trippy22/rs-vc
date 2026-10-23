# Development-only source-size/interoperability audit. Does not change plugin sources.
param(
    [string]$Reference = 'concentus-master',
    [string]$PublishedJar = '.tools/concentus.jar'
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$referenceRoot = (Resolve-Path (Join-Path $workspace $Reference)).Path
$published = (Resolve-Path (Join-Path $workspace $PublishedJar)).Path
$jdk = Join-Path $env:JAVA_HOME 'bin'
$sourceRoot = Join-Path $referenceRoot 'Java/Concentus/src/main/java/org/concentus'
$sources = @(Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' | Sort-Object Name)
$outputRoot = Join-Path $workspace ('.tools/codec-audit/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null

function Invoke-Jdk([string]$Tool, [string[]]$Arguments) {
    & (Join-Path $jdk ($Tool + '.exe')) @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool failed with exit code $LASTEXITCODE" }
}

# Conservative source-reference closure: include compile-time references too,
# which jdeps can omit after Java inlines constants. No codec methods are rewritten.
$sourceByName = @{}
$typeToSource = @{}
foreach ($source in $sources) {
    $sourceByName[$source.BaseName] = $source
    $body = [IO.File]::ReadAllText($source.FullName)
    foreach ($type in [regex]::Matches($body, '(?m)^(?:(?:public|final|abstract)\s+)*(?:class|enum|interface)\s+(\w+)')) {
        $typeToSource[$type.Groups[1].Value] = $source.BaseName
    }
}
$seen = [System.Collections.Generic.HashSet[string]]::new()
$queue = [System.Collections.Generic.Queue[string]]::new()
$queue.Enqueue('OpusEncoder'); $queue.Enqueue('OpusDecoder')
while ($queue.Count -gt 0) {
    $name = $queue.Dequeue()
    if (!$seen.Add($name)) { continue }
    $body = [IO.File]::ReadAllText($sourceByName[$name].FullName)
    $body = [regex]::Replace($body, '(?s)/\*.*?\*/|(?m)//[^\r\n]*', '')
    foreach ($token in [regex]::Matches($body, '\b[A-Za-z_][A-Za-z_0-9]*\b')) {
        if ($typeToSource.ContainsKey($token.Value) -and !$seen.Contains($typeToSource[$token.Value])) {
            $queue.Enqueue($typeToSource[$token.Value])
        }
    }
}
$kept = @($sources | Where-Object { $seen.Contains($_.BaseName) })
$removed = @($sources | Where-Object { !$seen.Contains($_.BaseName) })
$measurements = @()
foreach ($variant in @('full', 'subset')) {
    $selected = if ($variant -eq 'full') { $sources } else { $kept }
    $variantRoot = Join-Path $outputRoot $variant
    $sourceCopy = Join-Path $variantRoot 'src/org/concentus'
    $classes = Join-Path $variantRoot 'classes'
    New-Item -ItemType Directory -Path $sourceCopy, $classes -Force | Out-Null
    foreach ($source in $selected) { Copy-Item -LiteralPath $source.FullName -Destination $sourceCopy }
    # Preserve the license and all individual source headers in both candidates.
    Copy-Item -LiteralPath (Join-Path $referenceRoot 'LICENSE') -Destination (Join-Path $classes 'LICENSE')
    $argFile = Join-Path $variantRoot 'sources.args'
    $arguments = Get-ChildItem -LiteralPath $sourceCopy -Filter '*.java' | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
    [IO.File]::WriteAllLines($argFile, [string[]]$arguments)
    Invoke-Jdk 'javac' @('--release', '11', '-encoding', 'UTF-8', '-d', $classes, ('@' + $argFile))
    $jar = Join-Path $variantRoot 'codec.jar'
    Invoke-Jdk 'jar' @(('-J-Djava.io.tmpdir=' + $outputRoot), '--create', '--file', $jar, '-C', $classes, '.')
    $measurements += [pscustomobject]@{
        Variant = $variant
        SourceFiles = $selected.Count
        SourceBytes = ($selected | Measure-Object -Property Length -Sum).Sum
        NonblankLines = ($selected | Get-Content | Measure-Object -Line).Lines
        Classes = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class').Count
        JarBytes = (Get-Item -LiteralPath $jar).Length
    }
}
$subsetClassRoot = Join-Path $outputRoot 'subset/classes'
$retainedClasses = @(Get-ChildItem -LiteralPath $subsetClassRoot -Recurse -Filter '*.class')
foreach ($class in $retainedClasses) {
    $relative = $class.FullName.Substring($subsetClassRoot.Length + 1)
    $original = Join-Path (Join-Path $outputRoot 'full/classes') $relative
    if ((Get-FileHash -LiteralPath $class.FullName).Hash -ne (Get-FileHash -LiteralPath $original).Hash) {
        throw "Retained class bytecode changed: $relative"
    }
}
$harness = Join-Path $outputRoot 'harness'
New-Item -ItemType Directory -Path $harness | Out-Null
Invoke-Jdk 'javac' @('--release', '11', '-encoding', 'UTF-8', '-cp', ((Join-Path $outputRoot 'full/codec.jar') + ';' + $published), '-d', $harness, (Join-Path $PSScriptRoot 'CodecAudit.java'))
$fixture = Join-Path $referenceRoot 'AudioData/48Khz Mono.raw'
foreach ($variant in @('full', 'subset')) {
    $classpath = $harness + ';' + (Join-Path $outputRoot "$variant/codec.jar") + ';' + $published
    $results = @(Invoke-Jdk 'java' @('-cp', $classpath, 'CodecAudit', $fixture))
    [IO.File]::WriteAllLines((Join-Path $outputRoot "$variant-results.csv"), [string[]]$results)
}
$fullResults = [IO.File]::ReadAllText((Join-Path $outputRoot 'full-results.csv'))
$subsetResults = [IO.File]::ReadAllText((Join-Path $outputRoot 'subset-results.csv'))
if ($fullResults -cne $subsetResults) { throw 'Subset output differs from full source on audit fixtures' }
$manifest = [pscustomobject]@{
    Output = $outputRoot
    Measurements = $measurements
    RemovedFiles = @($removed.Name)
    IdenticalAuditResults = $true
    IdenticalRetainedClassCount = $retainedClasses.Count
    FixtureSha256 = (Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash
    PublishedJarSha256 = (Get-FileHash -LiteralPath $published -Algorithm SHA256).Hash
    SourceHashes = @($sources | ForEach-Object { [pscustomobject]@{ Name = $_.Name; Sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash } })
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputRoot 'manifest.json') -Encoding UTF8
$measurements | Format-Table
Write-Output ('Removed: ' + ($removed.Name -join ', '))
Write-Output ('Full/subset results identical. Artifacts: ' + $outputRoot)
Write-Output $fullResults
