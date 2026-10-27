# Pass CLI arguments through unchanged. The Android app is not modified by this script.
$ScriptArguments = @($args)
$ErrorActionPreference = 'Stop'
$cardPython = $null
if ($env:AFS_PYTHON -and (Test-Path -LiteralPath $env:AFS_PYTHON -PathType Leaf)) { $cardPython = $env:AFS_PYTHON }
if (-not $cardPython) {
    $cardCommand = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($cardCommand -and $cardCommand.Source -notlike '*\WindowsApps\*') { $cardPython = $cardCommand.Source }
}
if (-not $cardPython) {
    $cardBundledPython = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
    if (Test-Path -LiteralPath $cardBundledPython -PathType Leaf) { $cardPython = $cardBundledPython }
}
if (-not $cardPython) {
    $cardLauncher = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($cardLauncher) {
        & $cardLauncher.Source -3 (Join-Path $PSScriptRoot 'make_memory_cards.py') @ScriptArguments
        exit $LASTEXITCODE
    }
    throw 'Python 3.9+ is required. Install Python or set AFS_PYTHON to python.exe.'
}
& $cardPython (Join-Path $PSScriptRoot 'make_memory_cards.py') @ScriptArguments
exit $LASTEXITCODE
