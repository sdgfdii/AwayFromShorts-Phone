# Pass arguments through unchanged, e.g. ./scripts/import-quiz.ps1 bank.csv -o bank.json
$ScriptArguments = @($args)
$ErrorActionPreference = 'Stop'
$quizPython = $null
if ($env:AFS_PYTHON -and (Test-Path -LiteralPath $env:AFS_PYTHON -PathType Leaf)) {
    $quizPython = $env:AFS_PYTHON
}
if (-not $quizPython) {
    $quizPythonCommand = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($quizPythonCommand -and $quizPythonCommand.Source -notlike '*\WindowsApps\*') {
        $quizPython = $quizPythonCommand.Source
    }
}
if (-not $quizPython) {
    $quizBundledPython = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
    if (Test-Path -LiteralPath $quizBundledPython -PathType Leaf) { $quizPython = $quizBundledPython }
}
if (-not $quizPython) {
    $quizPyLauncher = Get-Command py.exe -ErrorAction SilentlyContinue
    if ($quizPyLauncher) {
        & $quizPyLauncher.Source -3 (Join-Path $PSScriptRoot 'import_quiz.py') @ScriptArguments
        exit $LASTEXITCODE
    }
    throw 'Python 3.9+ is required. Install Python or set AFS_PYTHON to python.exe.'
}
& $quizPython (Join-Path $PSScriptRoot 'import_quiz.py') @ScriptArguments
exit $LASTEXITCODE
