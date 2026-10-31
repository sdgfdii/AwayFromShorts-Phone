[CmdletBinding()]
param(
    [string]$Serial,
    [string]$Apk,
    [string]$Adb,
    [switch]$CheckOnly,
    [switch]$NonInteractive,
    [switch]$AcceptDeviceOwner
)
$ErrorActionPreference = 'Stop'
$OutputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
Import-Module (Join-Path $PSScriptRoot 'Deployment.psm1') -Force

function Invoke-AfsAdb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $nativeArgs = @()
    if ($script:SelectedSerial) { $nativeArgs += @('-s', $script:SelectedSerial) }
    $nativeArgs += $Arguments
    $previousPreference=$ErrorActionPreference
    try {
        # Windows PowerShell 5.1 turns native stderr into ErrorRecords. Read
        # expected dpm errors as evidence so the Android 7 fallback can run.
        $ErrorActionPreference='Continue'
        $out = & $script:AdbExe @nativeArgs 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference=$previousPreference }
    $message = ($out | ForEach-Object { "$_" }) -join "`n"
    if (!$AllowFailure -and $code -ne 0) { throw "ADB 命令失败：$message" }
    return $message.Replace("`r",'')
}
function Read-AfsOwner {
    $list = Invoke-AfsAdb -Arguments @('shell','dpm','list-owners') -AllowFailure
    $script:Policy = Invoke-AfsAdb -Arguments @('shell','dumpsys','device_policy')
    Get-AfsOwnerState -Owners $list -Policy $script:Policy
}
try {
    if (!$Apk) {
        $Apk = Join-Path $PSScriptRoot 'afs-guard.apk'
        if (!(Test-Path -LiteralPath $Apk)) { $Apk = Join-Path $PSScriptRoot '../../afs-guard.apk' }
    }
    $Apk = [IO.Path]::GetFullPath($Apk)
    if (!(Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "找不到 APK：$Apk" }
    $metaPath = Join-Path $PSScriptRoot 'manifest.json'
    if (Test-Path -LiteralPath $metaPath) {
        $meta = Get-Content -Raw -Encoding UTF8 -LiteralPath $metaPath | ConvertFrom-Json
        if ((Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash -ne $meta.apk.sha256) { throw 'APK 与部署包校验值不一致，请重新下载完整部署包。' }
    }
    if (!$Adb) {
        $candidates = @((Join-Path $PSScriptRoot 'tools/adb.exe'))
        foreach ($sdk in @($env:ANDROID_HOME,$env:ANDROID_SDK_ROOT,$env:AFS_SDK)) {
            if ($sdk) { $candidates += Join-Path $sdk 'platform-tools/adb.exe' }
        }
        $found = Get-Command adb.exe -ErrorAction SilentlyContinue
        if ($found) { $candidates += $found.Source }
        foreach ($candidate in $candidates) { if (Test-Path -LiteralPath $candidate) { $Adb = $candidate; break } }
    }
    if (!$Adb -or !(Test-Path -LiteralPath $Adb -PathType Leaf)) { throw '找不到 ADB。请使用完整 Windows 部署包，或通过 -Adb 指定官方 platform-tools 中的 adb.exe。' }
    $script:AdbExe = [IO.Path]::GetFullPath($Adb)
    $script:SelectedSerial = $null
    $devices = Invoke-AfsAdb -Arguments @('devices','-l')
    $online = @([regex]::Matches($devices,'(?m)^(\S+)\s+device(?:\s|$)') | ForEach-Object { $_.Groups[1].Value })
    if (!$Serial) {
        if ($online.Count -eq 1) { $Serial = $online[0] }
        elseif ($online.Count -gt 1 -and !$NonInteractive) {
            for ($i=0; $i -lt $online.Count; $i++) { Write-Host ("{0}. {1}" -f ($i+1),$online[$i]) }
            $number = 0
            if (![int]::TryParse((Read-Host '输入要部署的设备编号'),[ref]$number) -or $number -lt 1 -or $number -gt $online.Count) { throw '设备编号无效。' }
            $Serial = $online[$number-1]
        } else { throw "请连接并解锁手机，开启 USB 调试，在手机上允许此电脑；多设备时使用 -Serial 指定。`n$devices" }
    }
    if ($online -notcontains $Serial) { throw "所选设备尚未授权或未连接：$Serial`n$devices" }
    $script:SelectedSerial = $Serial
    $sdkText = Invoke-AfsAdb -Arguments @('shell','getprop','ro.build.version.sdk')
    $sdk = 0
    if (![int]::TryParse($sdkText.Trim(),[ref]$sdk) -or $sdk -lt 24) { throw '需要 Android 7.0（API 24）或更新系统。' }
    $model = Invoke-AfsAdb -Arguments @('shell','getprop','ro.product.model')
    Write-Host "设备：$model · API $sdk · $Serial"
    $owner = Read-AfsOwner
    if ($owner -eq 'other') { throw '设备已有其他设备所有者或工作资料管理者，已停止。' }
    if ($owner -eq 'unknown') { throw '无法识别这台设备的管理状态，已停止。' }
    if ($owner -eq 'none') {
        $users = Invoke-AfsAdb -Arguments @('shell','pm','list','users')
        $accounts = Invoke-AfsAdb -Arguments @('shell','dumpsys','account')
        Test-AfsFirstInstall -Users $users -Accounts $accounts -Policy $script:Policy
        $foreground = Invoke-AfsAdb -Arguments @('shell','am','get-current-user')
        if ($foreground.Trim() -ne '0') { throw '请先切回手机主用户。' }
    }
    Write-Host '部署前检查通过。'
    if ($CheckOnly) { Write-Host '只完成检查，未安装或修改手机。'; exit 0 }
    if ($owner -eq 'none' -and !$AcceptDeviceOwner) {
        if ($NonInteractive) { throw '首次部署请明确提供 -AcceptDeviceOwner。' }
        Write-Host '将把本应用设为设备所有者，启用冻结、禁止强停/清数据/卸载、禁止安全模式和系统内恢复出厂。'
        Write-Host 'Android 7/8 强制自动时间，冻结时段固定按首次配置时区。日常使用不需要连接电脑。'
        Write-Host '当前正式包不能保证用 remove-active-admin 解除。请先阅读同目录的恢复说明。'
        if ((Read-Host '确认理解后输入 DEPLOY；其他输入取消') -cne 'DEPLOY') { throw '已取消，未修改设备。' }
    }
    Invoke-AfsAdb -Arguments @('push',$Apk,'/data/local/tmp/afs-release.apk') | Out-Null
    $installed = Invoke-AfsAdb -Arguments @('shell','pm','install','-r','/data/local/tmp/afs-release.apk') -AllowFailure
    if ($installed.Trim() -ne 'Success') { throw "安装失败，保留原安装与数据，不会自动卸载。`n$installed" }
    Invoke-AfsAdb -Arguments @('shell','rm','-f','/data/local/tmp/afs-release.apk') -AllowFailure | Out-Null
    $ownerResult=''
    if ($owner -eq 'none') {
        $ownerResult=Invoke-AfsAdb -Arguments @('shell','dpm','set-device-owner','com.afs.guard/.AfsAdmin') -AllowFailure
    }
    if ((Read-AfsOwner) -ne 'ours') { throw "APK 已安装，但设备所有者授权未成功。系统返回：$ownerResult" }
    Invoke-AfsAdb -Arguments @('shell','dumpsys','deviceidle','whitelist','+com.afs.guard') -AllowFailure | Out-Null
    Invoke-AfsAdb -Arguments @('shell','pm','grant','com.afs.guard','android.permission.WRITE_SECURE_SETTINGS') -AllowFailure | Out-Null
    Invoke-AfsAdb -Arguments @('shell','am','broadcast','--include-stopped-packages','-a','com.afs.guard.WAKE','-n','com.afs.guard/.BootReceiver') | Out-Null
    $null = Read-AfsOwner
    foreach ($restriction in @('no_factory_reset','no_safe_boot','no_control_apps')) {
        if ($script:Policy -notmatch "(?m)^\s+$restriction\s*$") { throw "已获得设备所有者，但系统未确认加固项 $restriction。请保留诊断信息并停止作为正式部署使用。" }
    }
    if ($sdk -lt 28 -and $script:Policy -notmatch 'requireAutoTime=true') { throw '系统未确认强制自动时间，部署验证未通过。' }
    if ($sdk -ge 28 -and $script:Policy -notmatch '(?m)^\s+no_config_date_time\s*$') { throw '系统未确认禁止修改日期时间，部署验证未通过。' }
    $idle = Invoke-AfsAdb -Arguments @('shell','dumpsys','deviceidle','whitelist')
    if ($idle -notmatch 'com\.afs\.guard') { throw 'Doze 白名单未生效；后台准时性验证未通过。' }
    $power=''
    for($attempt=0;$attempt -lt 6;$attempt++){
        $power=Invoke-AfsAdb -Arguments @('shell','dumpsys','power')
        if($power -match 'afs:guard'){break}
        Start-Sleep -Milliseconds 500
    }
    if($power -notmatch 'afs:guard'){throw '常驻守护未确认持有唤醒锁，后台准时性验证未通过。'}
    $alarms=Invoke-AfsAdb -Arguments @('shell','dumpsys','alarm')
    if($alarms -notmatch 'com\.afs\.guard'){throw '未确认边界闹钟，部署验证未通过。'}
    Invoke-AfsAdb -Arguments @('shell','am','start','-n','com.afs.guard/.MainActivity') | Out-Null
    Write-Host '部署完成并通过管理限制验证。请在手机上配置冻结规则，并点“立即应用”。'
    Write-Host '个人同步可在打卡页点“同步”设置；不配置也能离线学习和冻结。'
    exit 0
} catch { Write-Host ("部署停止：" + $_.Exception.Message) -ForegroundColor Red; exit 1 }
