param([string]$JdkHome = $env:JAVA_HOME, [switch]$WindowsBoundary)
$ErrorActionPreference = 'Stop'
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin/javac.exe'))) {
    throw 'Set JAVA_HOME or pass -JdkHome with an installed JDK 17 or newer.'
}
$root = Split-Path -Parent $PSScriptRoot
if ($WindowsBoundary) {
    & (Join-Path $PSScriptRoot 'build-native.ps1') -JdkHome $JdkHome
    & (Join-Path $PSScriptRoot 'build-native.ps1') -JdkHome $JdkHome -TestFixtures
}
$classes = Join-Path $root ('build/check-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(Get-ChildItem (Join-Path $root 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin/javac.exe') --release 17 -encoding UTF-8 -Xlint:all -Werror -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
Push-Location $root
try {
    if ($WindowsBoundary) {
        & (Join-Path $JdkHome 'bin/java.exe') '-Djava.awt.headless=true' --enable-native-access=ALL-UNNAMED -Xcheck:jni -cp $classes io.lumipeek.core.CoreTests (Join-Path $root 'build/native/lumipeek-files.dll')
        if ($LASTEXITCODE -ne 0) { throw 'Core regressions failed' }
        & (Join-Path $JdkHome 'bin/java.exe') '-Djava.awt.headless=true' --enable-native-access=ALL-UNNAMED -Xcheck:jni -cp $classes io.lumipeek.core.WindowsBoundaryTests (Join-Path $root 'build/native-test/lumipeek-files.dll')
        if ($LASTEXITCODE -ne 0) { throw 'Windows boundary checks failed' }
        & (Join-Path $JdkHome 'bin/java.exe') '-Djava.awt.headless=true' --enable-native-access=ALL-UNNAMED -Xcheck:jni -cp $classes io.lumipeek.core.IsolationTests (Join-Path $JdkHome 'bin/java.exe') $classes (Join-Path $root 'build/native') (Join-Path $root 'build/native-test')
        if ($LASTEXITCODE -ne 0) { throw 'Isolated pipeline checks failed' }
        & (Join-Path $JdkHome 'bin/java.exe') '-Djava.awt.headless=true' --enable-native-access=ALL-UNNAMED -Xcheck:jni -cp $classes io.lumipeek.core.WorkerFaultTests (Join-Path $JdkHome 'bin/java.exe') $classes (Join-Path $root 'build/native-test/lumipeek-worker.dll')
    } else {
        & (Join-Path $JdkHome 'bin/java.exe') '-Djava.awt.headless=true' -cp $classes io.lumipeek.core.CoreTests
    }
    if ($LASTEXITCODE -ne 0) { throw 'Regression checks failed' }
    & (Join-Path $JdkHome 'bin/java.exe') '-Djava.awt.headless=true' -cp $classes io.lumipeek.core.WireTests
} finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'Headless checks failed' }
