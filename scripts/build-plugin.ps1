param([string]$SdkHome, [string]$JdkHome=$env:JAVA_HOME,
    [ValidateSet('1.5.0','1.5.1')][string]$ExpectedSdk='1.5.1')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
if (-not $SdkHome -or -not (Test-Path -LiteralPath (Join-Path $SdkHome 'sdk.json'))) { throw 'Official SDK distribution required' }
$meta=Get-Content (Join-Path $SdkHome 'sdk.json') -Raw | ConvertFrom-Json
if ($meta.sdk_version -ne $ExpectedSdk -or $meta.api_version -ne 1) { throw 'SDK target mismatch' }
if (-not $JdkHome -or (Get-Content (Join-Path $JdkHome 'release') -Raw) -notmatch 'JAVA_VERSION="25(?:\.|\+|")') { throw 'JDK25 required' }
$work=Join-Path $root ('build/plugin-'+[Guid]::NewGuid().ToString('N'))
$classes=Join-Path $work 'classes'
$view=Join-Path $work 'official-sdk'
New-Item -ItemType Directory -Path $classes,(Join-Path $view 'lib') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $SdkHome 'lib/Shimeji-ee.jar') -Destination $view
Get-ChildItem -LiteralPath (Join-Path $SdkHome 'lib') -Filter '*.jar' | Where-Object Name -ne 'Shimeji-ee.jar' | Copy-Item -Destination (Join-Path $view 'lib')
$cp=(Join-Path $view 'Shimeji-ee.jar')+';'+(Join-Path $view 'lib/*')+';'+$classes
$core=@(Get-ChildItem (Join-Path $root 'src/main') -Recurse -Filter '*.java' | ForEach-Object FullName)
$sdk=@(Get-ChildItem (Join-Path $root 'sdk/src') -Recurse -Filter '*.java' | Where-Object Name -ne 'CompatibilityProbe.java' | ForEach-Object FullName)
& (Join-Path $JdkHome 'bin/javac.exe') --release 17 -encoding UTF-8 -Xlint:all -Werror -d $classes @core
if ($LASTEXITCODE -ne 0) { throw 'Core compilation failed' }
& (Join-Path $JdkHome 'bin/javac.exe') --release 25 -encoding UTF-8 -Xlint:all -Werror -cp $cp -d $classes @sdk
if ($LASTEXITCODE -ne 0) { throw 'Plugin compilation failed' }
Copy-Item -LiteralPath (Join-Path $root 'sdk/src/META-INF') -Destination $classes -Recurse
Copy-Item -LiteralPath (Join-Path $root 'sdk/plugin.json') -Destination $classes
& (Join-Path $PSScriptRoot 'build-native.ps1') -JdkHome $JdkHome
$package=Join-Path $work 'LumiPeek'
New-Item -ItemType Directory -Path (Join-Path $package 'plugins'),(Join-Path $package 'native') -Force | Out-Null
$jar=Join-Path $package 'plugins/lumipeek.jar'
& (Join-Path $JdkHome 'bin/jar.exe') "-J-Djava.io.tmpdir=$work" --create --file $jar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Jar packaging failed' }
Copy-Item -LiteralPath (Join-Path $root 'build/native/lumipeek-files.dll'),(Join-Path $root 'build/native/lumipeek-worker.dll') -Destination (Join-Path $package 'native')
Copy-Item -LiteralPath (Join-Path $root 'README.md') -Destination $package
$entries=@(& (Join-Path $JdkHome 'bin/jar.exe') --list --file $jar)
if ($LASTEXITCODE -ne 0) { throw 'Jar audit failed' }
if ($entries | Where-Object { $_ -notmatch '^(META-INF/|io/|plugin.json$)' -or $_ -match '(Test|Fault|Probe|AGENTS|WORKLOG|\.log$|com/group_finity|\.ai-local)' }) { throw 'Unexpected package entry' }
if ($entries -notcontains 'META-INF/services/com.group_finity.mascot.lumi.plugin.LumiPlugin' -or $entries -notcontains 'io/lumipeek/core/PhotoWorkerMain.class') { throw 'Missing plugin/worker entrypoint' }
Write-Output "PASS: development package, SDK $ExpectedSdk; no SDK/test/private files; real-app loading NOT VERIFIED"
Write-Output "PACKAGE: $package"
