param(
    [string]$TaskFile,
    [string]$Prompt,
    [string]$SessionId,
    [switch]$Check,
    [switch]$Handoff,
    [string]$WorkingDirectory = (Split-Path $PSScriptRoot -Parent)
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath $WorkingDirectory).Path
$taskCli = Join-Path $env:LOCALAPPDATA 'Programs/DeepSeek Harness/resources/runtime/cli/bin/dsh.cmd'
if (!(Test-Path -LiteralPath $taskCli)) { throw 'DeepSeek Harness desktop CLI was not found.' }
if (@($Check.IsPresent, $Handoff.IsPresent, !!$TaskFile, !!$Prompt).Where({$_}).Count -ne 1) {
    throw 'Choose exactly one of -Check, -Handoff, -TaskFile, or -Prompt.'
}
if ($SessionId -and $SessionId -notmatch '^session-[a-zA-Z0-9-]+$') { throw 'Invalid DSH session ID.' }
if ($Check) { $Prompt = 'Connectivity check. Do not use tools or read/write files. Reply only DSH_READY.' }
if ($Handoff) { $TaskFile = Join-Path $taskRoot 'docs/DSH-IMPLEMENTATION-TASK.md' }
if ($TaskFile) { $Prompt = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $TaskFile).Path, [Text.Encoding]::UTF8) }
if ([string]::IsNullOrWhiteSpace($Prompt)) { throw 'The task is empty.' }

$taskLogDir = Join-Path $taskRoot '.codex-fix/dsh-handoff'
New-Item -ItemType Directory -Force -Path $taskLogDir | Out-Null
$taskRun = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$taskEvents = Join-Path $taskLogDir ($taskRun + '.jsonl')
$taskErrors = Join-Path $taskLogDir ($taskRun + '.stderr.log')
$taskDiagnostics = Join-Path $taskLogDir ($taskRun + '.diagnostics.log')
$taskSummary = Join-Path $taskLogDir ($taskRun + '.result.json')
$taskArgs = @('codex-handoff', '--json')
if ($SessionId) { $taskArgs += @('--session-id', $SessionId) }
$taskArgs += '-'
$taskExit = -1
$taskFinal = $null
$taskReason = $null
$taskActualSession = $null
$taskOldEncoding = $OutputEncoding
$taskOldConsoleEncoding = [Console]::OutputEncoding
$taskWriter = [IO.StreamWriter]::new($taskEvents, $false, [Text.UTF8Encoding]::new($false))
Push-Location -LiteralPath $taskRoot
try {
    $OutputEncoding = [Text.UTF8Encoding]::new($false)
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    $Prompt | & $taskCli @taskArgs 2> $taskErrors | ForEach-Object {
        $taskLine = [string]$_
        # Some Windows desktop CLI builds send catalog diagnostics on stdout.
        if (!$taskLine.TrimStart().StartsWith('{')) {
            Add-Content -LiteralPath $taskDiagnostics -Value $taskLine -Encoding UTF8
            return
        }
        $taskWriter.WriteLine($taskLine)
        $taskWriter.Flush()
        try { $taskEvent = $taskLine | ConvertFrom-Json } catch { throw 'Invalid DSH JSON output; inspect the event log.' }
        switch ($taskEvent.type) {
            'session' { $taskActualSession = $taskEvent.sessionId; Write-Host "DSH session: $taskActualSession" }
            'status' {
                if ($taskEvent.phase -eq 'step_end') { Write-Host "DSH step $($taskEvent.step) finished." }
                if ($taskEvent.phase -eq 'turn_end') { $taskReason = $taskEvent.reason.kind }
            }
            'final' { $taskFinal = $taskEvent.text; Write-Output $taskFinal }
            'error' { Write-Host "DSH error: $($taskEvent.message)" }
        }
    }
    $taskExit = $LASTEXITCODE
} finally {
    $taskWriter.Dispose()
    Pop-Location
    $OutputEncoding = $taskOldEncoding
    [Console]::OutputEncoding = $taskOldConsoleEncoding
    [ordered]@{sessionId=$taskActualSession;exitCode=$taskExit;reason=$taskReason;cwd=$taskRoot;events=$taskEvents;stderr=$taskErrors;final=$taskFinal} |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $taskSummary -Encoding UTF8
    Write-Host "DSH result: $taskSummary"
}
if ($taskExit -ne 0 -or $taskReason -ne 'completed' -or $null -eq $taskFinal) {
    throw "DSH did not complete (exit=$taskExit, reason=$taskReason). See $taskErrors"
}
if ($Check -and $taskFinal.Trim() -ne 'DSH_READY') { throw 'DSH connectivity check did not return the expected acknowledgement.' }
