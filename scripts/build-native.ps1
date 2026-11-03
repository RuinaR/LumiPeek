param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$VcVars = 'C:/Program Files/Microsoft Visual Studio/2022/BuildTools/VC/Auxiliary/Build/vcvars64.bat',
    [switch]$TestFixtures,
    [switch]$Analyze
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
foreach ($path in @($root, $JdkHome, $VcVars)) {
    if (-not $path -or $path -match '[%！!^&|<>"\r\n]' -or -not (Test-Path -LiteralPath $path)) {
        throw 'Invalid or missing compiler/JDK path'
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $JdkHome 'include/jni.h'))) { throw 'JDK JNI headers missing' }
$kind = if ($TestFixtures) { 'native-test' } else { 'native' }
$output = Join-Path $root "build/$kind"
New-Item -ItemType Directory -Path $output -Force | Out-Null
$define = if ($TestFixtures) { '/DLUMIPEEK_TEST' } else { '' }
$analysis = if ($Analyze) { '/analyze' } else { '' }
$fixtureCompile = if ($TestFixtures) {
    "cl /nologo /c /std:c++17 /EHsc /MT /W4 /WX /O2 /GS /guard:cf /sdl /permissive- /I`"$JdkHome/include`" /I`"$JdkHome/include/win32`" /Fo`"$output/tests.obj`" `"$root/native/windows/tests.cpp`"`r`nif errorlevel 1 exit /b 1"
} else { '' }
$fixtureObject = if ($TestFixtures) { "`"$output/tests.obj`"" } else { '' }
$workerObjects = @"
cl /nologo /c /std:c++17 /EHsc /MT /W4 /WX /O2 /GS /guard:cf /sdl /permissive- $define $analysis /I"$JdkHome/include" /I"$JdkHome/include/win32" /Fo"$output/worker.obj" "$root/native/windows/worker.cpp"
if errorlevel 1 exit /b 1
link /nologo /DLL /MACHINE:X64 /DYNAMICBASE /NXCOMPAT /GUARD:CF /OUT:"$output/lumipeek-worker.dll" "$output/worker.obj" kernel32.lib
if errorlevel 1 exit /b 1
dumpbin /nologo /exports "$output/lumipeek-worker.dll" > "$output/worker-exports.txt"
if errorlevel 1 exit /b 1
dumpbin /nologo /dependents "$output/lumipeek-worker.dll" > "$output/worker-dependencies.txt"
if errorlevel 1 exit /b 1
"@
$batch = @"
@echo off
call "$VcVars" >nul
if errorlevel 1 exit /b 1
cl /nologo /c /std:c++17 /EHsc /MT /W4 /WX /O2 /GS /guard:cf /sdl /permissive- $define $analysis /I"$JdkHome/include" /I"$JdkHome/include/win32" /Fo"$output/files.obj" "$root/native/windows/files.cpp"
if errorlevel 1 exit /b 1
$fixtureCompile
link /nologo /DLL /MACHINE:X64 /DYNAMICBASE /NXCOMPAT /GUARD:CF /OUT:"$output/lumipeek-files.dll" "$output/files.obj" $fixtureObject kernel32.lib ntdll.lib
if errorlevel 1 exit /b 1
dumpbin /nologo /exports "$output/lumipeek-files.dll" > "$output/exports.txt"
if errorlevel 1 exit /b 1
dumpbin /nologo /dependents "$output/lumipeek-files.dll" > "$output/dependencies.txt"
if errorlevel 1 exit /b 1
$workerObjects
exit /b 0
"@
$batchPath = Join-Path $output 'compile.cmd'
Set-Content -LiteralPath $batchPath -Value $batch -Encoding ascii
& $env:ComSpec /d /c $batchPath
if ($LASTEXITCODE -ne 0) { throw 'Native compilation failed' }
$exports = Get-Content -LiteralPath (Join-Path $output 'exports.txt') -Raw
if (-not $TestFixtures -and $exports -match 'WindowsFixtures') { throw 'Test exports leaked into production DLL' }
$expected = @('abi','identify0','discover0','read0')
foreach ($name in $expected) {
    if ($exports -notmatch ('\bJava_io_lumipeek_core_WindowsFiles_' + $name + '\b')) { throw 'Missing native export' }
}
$dependencies = Get-Content -LiteralPath (Join-Path $output 'dependencies.txt') -Raw
$dlls = @([regex]::Matches($dependencies, '(?im)^\s+([a-z0-9_.-]+\.dll)\s*$') | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() })
if ($dlls.Count -ne 2 -or $dlls -notcontains 'kernel32.dll' -or $dlls -notcontains 'ntdll.dll') {
    throw 'Unexpected native dependency'
}
$workerExports = Get-Content -LiteralPath (Join-Path $output 'worker-exports.txt') -Raw
if (-not $TestFixtures -and $workerExports -match 'WorkerFault') { throw 'Worker test exports leaked' }
$workerDependencies = Get-Content -LiteralPath (Join-Path $output 'worker-dependencies.txt') -Raw
$workerDlls = @([regex]::Matches($workerDependencies, '(?im)^\s+([a-z0-9_.-]+\.dll)\s*$') | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() })
if ($workerDlls.Count -ne 1 -or $workerDlls[0] -ne 'kernel32.dll') { throw 'Unexpected supervisor dependency' }
Write-Output "PASS: Windows x64 native build ($kind)"
