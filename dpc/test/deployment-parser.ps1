$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '../deploy/Deployment.psm1') -Force
$count=0
function Check([bool]$ok,[string]$name){if(!$ok){throw "FAIL $name"};$script:count++}
$empty="Current Device Policy Manager state:`n  Enabled Device Admins (User 0):"
Check ((Get-AfsOwnerState 'no owners' '') -eq 'none') 'modern none'
Check ((Get-AfsOwnerState 'User 0: admin=com.afs.guard/.AfsAdmin,DeviceOwner' '') -eq 'ours') 'modern own admin'
Check ((Get-AfsOwnerState 'User 0: admin=com.afs.guard/.AfsAdminFake,DeviceOwner' '') -eq 'other') 'exact component'
Check ((Get-AfsOwnerState 'User 1: admin=com.afs.guard/.AfsAdmin,DeviceOwner' '') -eq 'other') 'wrong user'
Check ((Get-AfsOwnerState 'unknown command' $empty) -eq 'none') 'legacy none'
$own="Current Device Policy Manager state:`n  Device Owner:`n    admin=ComponentInfo{com.afs.guard/com.afs.guard.AfsAdmin}`n    User ID: 0`n  Enabled Device Admins (User 0):"
Check ((Get-AfsOwnerState 'unknown command' $own) -eq 'ours') 'legacy own admin'
Check ((Get-AfsOwnerState 'unknown command' 'Current Device Policy Manager state:') -eq 'unknown') 'truncated policy'
Check ((Get-AfsOwnerState 'unknown command' 'Permission Denial') -eq 'unknown') 'denied policy'
Test-AfsFirstInstall 'UserInfo{0:Owner:13}' '  Accounts: 0' $empty
$count++
foreach($case in @(
    @('UserInfo{0:Owner:13}','  Accounts: 1',$empty),
    @('UserInfo{0:Owner:13}','Permission Denial',$empty),
    @('UserInfo{0:Owner:13} UserInfo{999:Clone:0}','  Accounts: 0',$empty),
    @('UserInfo{0:Owner:13}','  Accounts: 0','Permission Denial')
)){
    $rejected=$false
    try{Test-AfsFirstInstall $case[0] $case[1] $case[2]}catch{$rejected=$true}
    Check $rejected 'unsafe preflight rejected'
}
Write-Output "Deployment parser: $count checks passed"
