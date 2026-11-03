$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    $tracked = @(git ls-files)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect tracked files' }
    $privatePattern = '(^|/)(AGENTS\.md|DEVELOPMENT-PLAN\.md|CODEX-START-PROMPT\.md|\.ai-local)(/|$)'
    if (@($tracked | Where-Object { $_ -match $privatePattern }).Count) { throw 'Private document is tracked' }
    foreach ($privatePath in @('AGENTS.md','DEVELOPMENT-PLAN.md','CODEX-START-PROMPT.md','.ai-local/WORKLOG.md','.ai-local/CURRENT-STATUS.md')) {
        git check-ignore -q -- $privatePath
        if ($LASTEXITCODE -ne 0) { throw 'Missing private-document ignore rule' }
    }
    $forbidden = 'java\.awt\.Robot|ProcessBuilder|Runtime\.getRuntime|System\.load|java\.net\.|javax\.net\.|com\.sun\.|sun\.misc\.|\b(onLoad|patch|hook|claimClickGreeting|behave|summon|dismiss|moveTo|setCharactersVisible|bindHotkey)\s*\('
    $sources = @(Get-ChildItem (Join-Path $root 'src/main') -Recurse -Filter '*.java')
    if (@($sources | Select-String -Pattern $forbidden).Count) { throw 'Forbidden integration API found' }
    git diff --check
    if ($LASTEXITCODE -ne 0) { throw 'Working-tree diff check failed' }
    git diff --cached --check
    if ($LASTEXITCODE -ne 0) { throw 'Staged diff check failed' }
    Write-Output 'PASS: private-document exclusion, prohibited-API source scan, diff whitespace'
} finally { Pop-Location }
