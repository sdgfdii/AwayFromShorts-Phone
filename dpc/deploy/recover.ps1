[CmdletBinding()]
param([string]$Serial,[string]$Adb,[switch]$AcceptRecovery,[switch]$Uninstall)
$ErrorActionPreference='Stop'
$OutputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Import-Module (Join-Path $PSScriptRoot 'Deployment.psm1') -Force
function Invoke-RecoveryAdb {
    param([string[]]$Arguments)
    $nativeArgs=@()
    if($script:RecoverySerial){$nativeArgs+=@('-s',$script:RecoverySerial)}
    $previousPreference=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        $out=& $script:RecoveryAdb @nativeArgs @Arguments 2>&1
        $code=$LASTEXITCODE
    } finally { $ErrorActionPreference=$previousPreference }
    if($code -ne 0){throw (($out|ForEach-Object{"$_"}) -join "`n")}
    return (($out|ForEach-Object{"$_"}) -join "`n").Replace("`r",'')
}
try {
    if(!$Adb){$Adb=Join-Path $PSScriptRoot 'tools/adb.exe'}
    if(!(Test-Path -LiteralPath $Adb)){throw '找不到 ADB，请使用完整部署包或提供 -Adb。'}
    $script:RecoveryAdb=[IO.Path]::GetFullPath($Adb)
    $script:RecoverySerial=$null
    $list=Invoke-RecoveryAdb -Arguments @('devices')
    $online=@([regex]::Matches($list,'(?m)^(\S+)\s+device\s*$')|ForEach-Object{$_.Groups[1].Value})
    if(!$Serial -and $online.Count -eq 1){$Serial=$online[0]}
    if(!$Serial -or $online -notcontains $Serial){throw '请连接并授权一台设备；多台设备时用 -Serial 指定。'}
    $script:RecoverySerial=$Serial
    $apk=Join-Path $PSScriptRoot 'maintenance/afs-recovery.apk'
    $meta=Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot 'manifest.json')|ConvertFrom-Json
    if((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash -ne $meta.maintenance.sha256){throw '维护包校验失败。'}
    Write-Host "将通过同签名维护包解除 $Serial 上的 AFS 设备管理与冻结。"
    if($Uninstall){Write-Host '同时卸载应用，删除本机应用数据。请先备份。'}
    else {Write-Host '本机应用数据会保留；需要卸载时请先导出需要保留的内容。'}
    if(!$AcceptRecovery -and (Read-Host '确认后输入 RECOVER') -cne 'RECOVER'){throw '已取消，未更改设备。'}
    Invoke-RecoveryAdb -Arguments @('push',$apk,'/data/local/tmp/afs-recovery.apk')|Out-Null
    $installed=Invoke-RecoveryAdb -Arguments @('shell','pm','install','-r','/data/local/tmp/afs-recovery.apk')
    if($installed.Trim() -ne 'Success'){throw "维护包安装失败，未解除管理：$installed"}
    Invoke-RecoveryAdb -Arguments @('shell','rm','-f','/data/local/tmp/afs-recovery.apk')|Out-Null
    $result=Invoke-RecoveryAdb -Arguments @('shell','am','broadcast','-a','com.afs.guard.RECOVER','-n','com.afs.guard/.RecoveryReceiver','--es','confirm','REMOVE_AFS_DEVICE_OWNER')
    if($result -notmatch 'AFS_RECOVERY_DONE'){throw "系统未确认恢复完成：$result"}
    $finished=$false
    for($attempt=0;$attempt -lt 20;$attempt++){
        $policy=Invoke-RecoveryAdb -Arguments @('shell','dumpsys','device_policy')
        $state=Get-AfsOwnerState -Owners 'unsupported' -Policy $policy
        $pending=$policy -match 'Removing Device Admins.*com\.afs\.guard|(?m)^    com\.afs\.guard/'
        if($state -eq 'none' -and !$pending){$finished=$true;break}
        Start-Sleep -Milliseconds 500
    }
    if(!$finished){throw '系统尚未完成管理员注销。请重启手机后再卸载或重新部署；应用数据保留。'}
    if($Uninstall){
        $removed=Invoke-RecoveryAdb -Arguments @('uninstall','com.afs.guard')
        if($removed.Trim() -ne 'Success'){throw "管理已解除，但卸载未成功：$removed"}
        Write-Host '已解除管理并卸载。'
    } else {Write-Host '已确认解除管理。本机数据保留，可先打开应用导出，再从系统设置卸载。'}
    exit 0
} catch {Write-Host ("恢复停止："+$_.Exception.Message) -ForegroundColor Red; exit 1}
