param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$Artifacts,[string]$Python,[string]$StageOnly='',[string]$SigningConfig,[switch]$InitializeOwner,[switch]$SeedData)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskSdk=Join-Path $env:USERPROFILE '.afs-android/sdk'
$taskBt=Join-Path $taskSdk 'build-tools/34.0.0'
$taskAndroid=Join-Path $taskSdk 'platforms/android-34/android.jar'
$taskAdb=Join-Path $taskSdk 'platform-tools/adb.exe'
$taskClasses=Join-Path $taskRoot 'build/classes.jar'
if(!$Python){$Python=(Get-Command python).Source}
$Artifacts=[IO.Path]::GetFullPath($Artifacts)
if($Serial -notlike 'emulator-*' -and $Serial -notin @("127.0.0.1:5665","127.0.0.1:5667","127.0.0.1:5671")){throw 'Only a disposable emulator is permitted'}
function Checked {param([string]$File,[string[]]$Arguments)
 $out=& $File @Arguments 2>&1
 $out | Write-Output
 if($LASTEXITCODE -ne 0){throw "Command failed ($LASTEXITCODE): $File"}
}
$taskTestAvds=@{"127.0.0.1:5665"="AFS_R2_C19F_API34";"127.0.0.1:5667"="AFS_R2_C19F_API24";"127.0.0.1:5671"="AFS_R2_C19F_RELEASE34"}
if($taskTestAvds.ContainsKey($Serial)){if(((& $taskAdb -s $Serial shell getprop ro.boot.qemu.avd_name | Out-String).Trim()) -ne $taskTestAvds[$Serial]){throw "Unexpected test AVD"}}
$qemu=(& $taskAdb -s $Serial shell getprop ro.kernel.qemu | Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $qemu -ne '1'){throw 'Target is not a confirmed emulator'}
$taskSafeSerial=$Serial.Replace(":","_");$taskOut=Join-Path $Artifacts "qa-$taskSafeSerial"
New-Item -ItemType Directory -Force "$taskOut/classes","$taskOut/dex" | Out-Null
Checked "$taskBt/aapt2.exe" @('link','-o',"$taskOut/base.apk",'-I',$taskAndroid,'--manifest',"$PSScriptRoot/device/PluginManifest.xml",'--min-sdk-version','24','--target-sdk-version','34')
Checked (Get-Command javac).Source @('--release','8','-encoding','UTF-8','-cp',"$taskAndroid;$taskClasses",'-d',"$taskOut/classes","$PSScriptRoot/device/PluginDeviceTest.java","$PSScriptRoot/device/PluginBaselineDataTest.java")
Checked (Get-Command jar).Source @('cf',"$taskOut/classes.jar",'-C',"$taskOut/classes",'.')
Checked "$taskBt/d8.bat" @('--lib',$taskAndroid,'--classpath',$taskClasses,'--min-api','24','--output',"$taskOut/dex","$taskOut/classes.jar")
@'
import sys,zipfile
with zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED) as z:
 z.write(sys.argv[2],'classes.dex')
'@ | Set-Content -LiteralPath "$taskOut/add-dex.py" -Encoding ascii
Checked $Python @("$taskOut/add-dex.py","$taskOut/base.apk","$taskOut/dex/classes.dex")
Checked "$taskBt/zipalign.exe" @('-f','-p','4',"$taskOut/base.apk","$taskOut/aligned.apk")
$sign=@('--ks',"$taskRoot/keystore/afs.keystore",'--ks-pass','pass:afsguard','--key-pass','pass:afsguard')
if($env:AFS_KEYSTORE){$sign=@('--ks',$env:AFS_KEYSTORE,'--ks-key-alias',$env:AFS_KEY_ALIAS,'--ks-pass','env:AFS_STORE_PASS','--key-pass','env:AFS_STORE_PASS')}
if($SigningConfig){
 $config=Get-Content -LiteralPath $SigningConfig -Raw -Encoding UTF8 | ConvertFrom-Json
 $savedQaPass=$env:AFS_PLUGIN_QA_STORE_PASS;$env:AFS_PLUGIN_QA_STORE_PASS=$config.password
 $sign=@('--ks',$config.keystore,'--ks-key-alias',$config.alias,'--ks-pass','env:AFS_PLUGIN_QA_STORE_PASS','--key-pass','env:AFS_PLUGIN_QA_STORE_PASS')
}
try{Checked "$taskBt/apksigner.bat" (@('sign')+$sign+@('--out',"$taskOut/qa.apk","$taskOut/aligned.apk"))}
finally{if($SigningConfig){$env:AFS_PLUGIN_QA_STORE_PASS=$savedQaPass}}
function Install([string]$Name){Checked $taskAdb @('-s',$Serial,'install','-r',(Join-Path $Artifacts $Name))}
function HostHash {
 $paths=& $taskAdb -s $Serial shell pm path com.afs.guard
 if($LASTEXITCODE -ne 0){throw 'Cannot read installed host path'}
 $p=($paths | Where-Object {$_ -match '^package:.*base.apk$'} | Select-Object -First 1) -replace '^package:',''
 if(!$p){throw 'Installed host base.apk not found'}
 # Android 7's adbd sync process lacks the shell process's traverse access to
 # /data/app. Copy the public APK through shell; never fall back to a local file.
 Checked $taskAdb @('-s',$Serial,'shell','cp',$p.Trim(),'/sdcard/afs-plugin-host-proof.apk') | Out-Null
 Checked $taskAdb @('-s',$Serial,'pull','/sdcard/afs-plugin-host-proof.apk',"$taskOut/installed-host.apk") | Out-Null
 Checked $taskAdb @('-s',$Serial,'shell','rm','/sdcard/afs-plugin-host-proof.apk') | Out-Null
 return (Get-FileHash -LiteralPath "$taskOut/installed-host.apk" -Algorithm SHA256).Hash
}
function Stage([string]$Name){
 $call=@('-s',$Serial,'shell','am','instrument','-w','-e','stage',$Name)
 if($Name -eq 'storage-retry-restart'){$call+=@('-e','old_pid',$script:taskExpectedOldPid)}
 $call+='com.afs.guard.pluginqa/com.afs.host.plugins.PluginDeviceTest'
 $out=& $taskAdb @call 2>&1
 $out | Tee-Object -FilePath "$taskOut/$Name.log"
 if($LASTEXITCODE -ne 0 -or ($out -join "`n") -notmatch "Plugin device $([regex]::Escape($Name)): \d+ checks passed" -or ($out -join "`n") -match 'FAILED|AssertionError|INSTRUMENTATION_FAILED'){throw "Plugin stage failed: $Name"}
}
if(!$StageOnly){
 Install 'host-test.apk'
 Install 'victim.apk'
 if($InitializeOwner){Checked $taskAdb @('-s',$Serial,'shell','dpm','set-device-owner','com.afs.guard/.AfsAdmin')}
 foreach($pkg in @('com.afs.plugin.usagestats','com.afs.plugin.fixture','com.afs.plugin.fixturetwo','com.afs.plugin.untrusted')){
  $installed=& $taskAdb -s $Serial shell pm list packages $pkg
  if($LASTEXITCODE -ne 0){throw 'Package query failed'}
  if($installed -contains "package:$pkg"){Checked $taskAdb @('-s',$Serial,'uninstall',$pkg)}
 }
 Install 'plugin-v1.apk';Install 'fixture-v1.apk';Install 'fixture-two.apk';Install 'fixture-untrusted.apk'
 Checked $taskAdb @('-s',$Serial,'push',(Join-Path $Artifacts 'fixture-v2.apk'),'/data/local/tmp/afs-plugin-v2.apk')
 Checked $taskAdb @('-s',$Serial,'push',(Join-Path $Artifacts 'fixture-collision.apk'),'/data/local/tmp/afs-plugin-collision.apk')
}
Checked $taskAdb @('-s',$Serial,'install','-r',"$taskOut/qa.apk")
if($SeedData){
 $seed=& $taskAdb -s $Serial shell am instrument -w -e mode seed com.afs.guard.pluginqa/com.afs.guard.PluginBaselineDataTest 2>&1
 $seed | Tee-Object -FilePath "$taskOut/data-seed.log"
 if($LASTEXITCODE -ne 0 -or ($seed -join "`n") -notmatch 'Plugin baseline data: \d+ checks passed' -or ($seed -join "`n") -match 'FAILED|AssertionError'){throw 'Baseline data fixture failed'}
}
$initialOp=& $taskAdb -s $Serial shell appops get com.afs.guard GET_USAGE_STATS
if($LASTEXITCODE -ne 0){throw 'Cannot record initial AppOps'}
$initialOp | Set-Content -LiteralPath "$taskOut/appops-before.txt"
Checked $taskAdb @('-s',$Serial,'shell','appops','set','com.afs.guard','GET_USAGE_STATS','allow')
if($StageOnly){Stage $StageOnly;exit}
$hostHash=HostHash
try{
 Stage 'storage-retry'
 $pidMatch=[regex]::Match((Get-Content -LiteralPath "$taskOut/storage-retry.log" -Raw),'hostPid=(\d+)')
 if(!$pidMatch.Success){throw 'Missing process identity for persistence verification'}
 $script:taskExpectedOldPid=$pidMatch.Groups[1].Value
 Checked $taskAdb @('-s',$Serial,'shell','am','force-stop','com.afs.guard')
 Stage 'storage-retry-restart'
 Stage 'normal'
 Install 'fixture-v2.apk';if((HostHash) -ne $hostHash){throw 'Installed host changed during plugin-only update'}
 Stage 'upgrade'
 Install 'plugin-v2.apk';if((HostHash) -ne $hostHash){throw 'Host changed during real statistics plugin update'}
 Stage 'real-upgrade'
 Stage 'disable'
 Checked $taskAdb @('-s',$Serial,'shell','am','force-stop','com.afs.guard')
 Stage 'disabled'
 Install 'fixture-incompatible.apk';Stage 'incompatible'
 Install 'fixture-fail.apk';Stage 'prepare-fail'
 Install 'fixture-timeout.apk';Stage 'prepare-timeout'
 Checked $taskAdb @('-s',$Serial,'uninstall','com.afs.plugin.fixture')
 Install 'fixture-v1.apk';Stage 'reinstall'
 Stage 'stale-dialog'
 Stage 'collision'
 Stage 'untrusted'
 if((HostHash) -ne $hostHash){throw 'Host changed during independent plugin matrix'}
 Write-Output "Plugin full runtime matrix passed on $Serial; unchanged installed host SHA256=$hostHash"
}finally{
 Checked $taskAdb @('-s',$Serial,'uninstall','com.afs.guard.pluginqa')
}
