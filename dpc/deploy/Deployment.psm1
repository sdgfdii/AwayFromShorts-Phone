Set-StrictMode -Version 2

function Get-AfsOwnerState {
    param([string]$Owners, [string]$Policy)
    $ours = '^com\.afs\.guard/(\.AfsAdmin|com\.afs\.guard\.AfsAdmin)$'
    if ($Owners.Trim() -eq 'no owners') { return 'none' }
    $rows = @($Owners -split "`n" | Where-Object {$_ -match ',DeviceOwner'})
    if ($rows.Count -gt 0) {
        if ($rows.Count -eq 1 -and $rows[0] -match 'User\s+0:\s+admin=([^,\s]+),DeviceOwner' -and $Matches[1] -match $ours) { return 'ours' }
        return 'other'
    }
    if ($Owners -match ',ProfileOwner') { return 'other' }
    if ($Policy -notmatch '(?m)^Current Device Policy Manager state:' -or
        $Policy -notmatch '(?m)^  Enabled Device Admins \(User ' -or
        $Policy -match 'Permission Denial|Exception:|Error:') { return 'unknown' }
    if ($Policy -match '(?m)^  Device Owner:') {
        if ($Policy -match '(?m)^  Device Owner:[ \t]*\r?\n[ \t]+admin=ComponentInfo\{([^}]+)\}') {
            if ($Matches[1] -match $ours -and $Policy -match '(?m)^    User ID: 0\s*$') { return 'ours' }
            return 'other'
        }
        return 'unknown'
    }
    if ($Policy -match '(?m)^  Profile Owner') { return 'other' }
    return 'none'
}

function Test-AfsFirstInstall {
    param([string]$Users, [string]$Accounts, [string]$Policy)
    if ($Policy -notmatch '(?m)^Current Device Policy Manager state:' -or
        $Policy -notmatch '(?m)^  Enabled Device Admins \(User ') { throw '无法检查已有设备管理员，已停止。' }
    if ($Policy -match 'Removing Device Admins.*com\.afs\.guard') { throw '系统仍在注销旧管理员。请重启手机后重试；本次未安装或修改手机。' }
    $userRows = @([regex]::Matches($Users, 'UserInfo\{([0-9]+):'))
    if ($userRows.Count -ne 1 -or $userRows[0].Groups[1].Value -ne '0') {
        throw '首次部署需要只有主用户。请先检查应用分身、第二空间、工作资料；脚本不会删除用户或清机。'
    }
    $counts = @([regex]::Matches($Accounts, '(?m)^\s*Accounts: ([0-9]+)\s*$'))
    if ($counts.Count -eq 0 -or $Accounts -match 'Permission Denial|Exception:|Error:') { throw '无法可靠读取账号状态，已停止。' }
    foreach ($count in $counts) {
        if ([int]$count.Groups[1].Value -ne 0) { throw '系统仍有账号。请在手机设置中自行检查并退出，备份需要保留的数据后重试。' }
    }
    if ($Policy -match '(?m)^    (?!com\.afs\.guard/)[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+:') {
        throw '发现其他应用的设备管理员，请先在系统设置中核实管理状态。'
    }
}

Export-ModuleMember -Function Get-AfsOwnerState, Test-AfsFirstInstall
