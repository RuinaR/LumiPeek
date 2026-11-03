param([string]$SdkHome, [string]$JdkHome = $env:JAVA_HOME,
    [ValidateSet('1.5.1','1.5.0')][string]$ExpectedSdk = '1.5.1', [string]$PluginPackage)
$ErrorActionPreference = 'Stop'
if (-not $SdkHome -or -not (Test-Path -LiteralPath (Join-Path $SdkHome 'sdk.json'))) {
    Write-Output 'BLOCKED: provide an official Little LUMI SDK 1.5.1 distribution with sdk.json and lib/Shimeji-ee.jar'
    exit 2
}
$meta = Get-Content -LiteralPath (Join-Path $SdkHome 'sdk.json') -Raw | ConvertFrom-Json
if ($meta.sdk_version -ne $ExpectedSdk -or $meta.api_version -ne 1) { throw 'BLOCKED: SDK version must match explicitly selected target / API 1' }
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'release'))) { throw 'BLOCKED: JDK 25 required' }
$release = Get-Content -LiteralPath (Join-Path $JdkHome 'release') -Raw
if ($release -notmatch 'JAVA_VERSION="25(?:\.|\+|")') { throw 'BLOCKED: JDK 25 required' }
if (-not (Test-Path -LiteralPath (Join-Path $SdkHome 'lib/Shimeji-ee.jar'))) { throw 'BLOCKED: SDK binary missing' }
$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $root ('build/sdk-smoke-' + [Guid]::NewGuid().ToString('N'))
$temp = Join-Path $output 'temp'
New-Item -ItemType Directory -Path $temp -Force | Out-Null
$sources = @(Get-ChildItem (Join-Path $root 'sdk') -Recurse -Filter '*.java' | ForEach-Object FullName)
$coreSources = @(Get-ChildItem (Join-Path $root 'src/main') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin/javac.exe') --release 17 -encoding UTF-8 -Xlint:all -Werror -d $output @coreSources
if ($LASTEXITCODE -ne 0) { throw 'Core dependency compilation failed' }
# The SDK app JAR's manifest expects lib/ beside it. Preserve official bytes,
# but build an isolated compiler view rather than suppress missing-path warnings.
$view = Join-Path $output 'official-sdk'
$viewLib = Join-Path $view 'lib'
New-Item -ItemType Directory -Path $viewLib -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $SdkHome 'lib/Shimeji-ee.jar') -Destination $view
Get-ChildItem -LiteralPath (Join-Path $SdkHome 'lib') -Filter '*.jar' |
    Where-Object Name -ne 'Shimeji-ee.jar' | Copy-Item -Destination $viewLib
$sdkClasspath = (Join-Path $view 'Shimeji-ee.jar') + ';' + (Join-Path $viewLib '*') + ';' + $output
& (Join-Path $JdkHome 'bin/javac.exe') --release 25 -encoding UTF-8 -Xlint:all -Werror -cp $sdkClasspath -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'SDK probe compilation failed' }
& (Join-Path $JdkHome 'bin/java.exe') '-Djava.awt.headless=true' "-Djava.io.tmpdir=$temp" -cp "$output;$sdkClasspath" io.lumipeek.sdk.SdkSmoke $ExpectedSdk
if ($LASTEXITCODE -ne 0) { throw 'SDK smoke failed' }
& (Join-Path $JdkHome 'bin/java.exe') '-Djava.awt.headless=true' "-Djava.io.tmpdir=$temp" --add-modules jdk.httpserver -cp "$output;$sdkClasspath" io.lumipeek.sdk.OllamaTests
if ($LASTEXITCODE -ne 0) { throw 'Fake Ollama checks failed' }
$featureCp="$output;$sdkClasspath"
$featureArgs=@()
if ($PluginPackage) {
    # This emulates the documented adjacent-native layout, not the real SDK protected loader.
    $protected=Join-Path $output 'simulated-protected'
    New-Item -ItemType Directory -Path $protected -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PluginPackage 'plugins/lumipeek.jar') -Destination $protected
    Copy-Item -LiteralPath (Join-Path $PluginPackage 'native') -Destination $protected -Recurse
    $featureCp=(Join-Path $protected 'lumipeek.jar')+';'+$featureCp
    $featureArgs=@('worker')
}
& (Join-Path $JdkHome 'bin/java.exe') '-Djava.awt.headless=true' "-Djava.io.tmpdir=$temp" --enable-native-access=ALL-UNNAMED -Xcheck:jni --add-modules jdk.httpserver -cp $featureCp io.lumipeek.sdk.FeatureTests @featureArgs
if ($LASTEXITCODE -ne 0) { throw 'Feature integration checks failed' }
