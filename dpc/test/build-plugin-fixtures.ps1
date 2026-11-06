[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Artifacts,[string]$Sdk,[string]$Python,[string]$Bash,[switch]$Production,[string]$SigningConfig)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$Artifacts=[IO.Path]::GetFullPath($Artifacts)
New-Item -ItemType Directory -Force $Artifacts | Out-Null
if(!$Sdk){$Sdk=Join-Path $env:USERPROFILE '.afs-android/sdk'}
if(!$Python){$Python=(Get-Command python.exe -ErrorAction Stop).Source}
if($Python -like '*\WindowsApps\*'){throw 'Pass -Python with a working interpreter'}
if(!$Bash){$git=(Get-Command git.exe -ErrorAction Stop).Source;$Bash=Join-Path (Split-Path (Split-Path $git)) 'bin/bash.exe'}
$names=@('AFS_RELEASE','AFS_RECOVERY','AFS_PREFILL','AFS_KEYSTORE','AFS_KEY_ALIAS','AFS_STORE_PASS','AFS_KEY_PASS','AFS_PLUGIN_TEST_MODE','AFS_APK_OUTPUT','AFS_PLUGIN_APK_OUTPUT','AFS_PLUGIN_VERSION_CODE','AFS_PLUGIN_VERSION_NAME','AFS_VERSION_CODE','AFS_VERSION_NAME','AFS_FIXTURE_PACKAGE','AFS_FIXTURE_ID','AFS_FIXTURE_PROTOCOL','AFS_FIXTURE_PREPARE','AFS_FIXTURE_READ_ONLY','AFS_STATS_LEGACY','AFS_SDK','AFS_PYTHON')
$saved=@{};foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
function Build([string]$Path,[string]$Log){
 & $Bash (Join-Path $root $Path) *> (Join-Path $Artifacts $Log)
 if($LASTEXITCODE -ne 0){Get-Content -LiteralPath (Join-Path $Artifacts $Log) -Tail 30;throw "Build failed: $Path"}
}
try{
 $env:AFS_SDK=$Sdk.Replace('\','/');$env:AFS_PYTHON=$Python.Replace('\','/')
 $env:AFS_RECOVERY='0';$env:AFS_PREFILL='0';$env:AFS_STATS_LEGACY='0';$env:AFS_VERSION_CODE=$null;$env:AFS_VERSION_NAME=$null
 if($Production){
  if(!$SigningConfig){$SigningConfig=Join-Path $env:LOCALAPPDATA 'AwayFromShorts/Signing/phone-release-v1.json'}
  $config=Get-Content -LiteralPath $SigningConfig -Raw -Encoding UTF8 | ConvertFrom-Json
  $env:AFS_RELEASE='1';$env:AFS_PLUGIN_TEST_MODE='0'
  $env:AFS_KEYSTORE=$config.keystore.Replace('\','/');$env:AFS_KEY_ALIAS=$config.alias;$env:AFS_STORE_PASS=$config.password;$env:AFS_KEY_PASS=$config.password
 }else{
  $env:AFS_RELEASE='0';$env:AFS_PLUGIN_TEST_MODE='1';$env:AFS_KEYSTORE=(Join-Path $root 'dpc/keystore/afs.keystore').Replace('\','/')
  $env:AFS_KEY_ALIAS='afs';$env:AFS_STORE_PASS='afsguard';$env:AFS_KEY_PASS='afsguard'
 }
 $env:AFS_APK_OUTPUT=(Join-Path $Artifacts 'host-test.apk').Replace('\','/')
 Build 'dpc/build.sh' 'build-host.log'
 $bt=Join-Path $Sdk 'build-tools/34.0.0';$android=Join-Path $Sdk 'platforms/android-34/android.jar'
 & "$bt/aapt2.exe" link -o "$Artifacts/victim-base.apk" -I $android --manifest (Join-Path $root 'dpc/test/device/PluginRuleVictimManifest.xml') --min-sdk-version 24 --target-sdk-version 34
 if($LASTEXITCODE -ne 0){throw 'Rule fixture resource build failed'}
 & "$bt/zipalign.exe" -f -p 4 "$Artifacts/victim-base.apk" "$Artifacts/victim-aligned.apk"
 if($LASTEXITCODE -ne 0){throw 'Rule fixture alignment failed'}
 & "$bt/apksigner.bat" sign --ks $env:AFS_KEYSTORE --ks-key-alias $env:AFS_KEY_ALIAS --ks-pass env:AFS_STORE_PASS --key-pass env:AFS_KEY_PASS --out "$Artifacts/victim.apk" "$Artifacts/victim-aligned.apk"
 if($LASTEXITCODE -ne 0){throw 'Rule fixture signing failed'}
 foreach($version in @(1,2)){
  $env:AFS_PLUGIN_VERSION_CODE=[string]$version;$env:AFS_PLUGIN_VERSION_NAME="1.0.$($version-1)"
  $env:AFS_PLUGIN_APK_OUTPUT=(Join-Path $Artifacts "plugin-v$version.apk").Replace('\','/')
  Build 'plugins/usage-stats/build.sh' "build-plugin-v$version.log"
 }
 $variants=@(
  @('1','1','0','com.afs.plugin.fixture','compat-fixture','0','fixture-v1.apk'),
  @('2','1','0','com.afs.plugin.fixture','compat-fixture','0','fixture-v2.apk'),
  @('3','2','0','com.afs.plugin.fixture','compat-fixture','0','fixture-incompatible.apk'),
  @('4','1','1','com.afs.plugin.fixture','compat-fixture','0','fixture-fail.apk'),
  @('5','1','2','com.afs.plugin.fixture','compat-fixture','0','fixture-timeout.apk'),
  @('1','1','0','com.afs.plugin.fixturetwo','compat-fixture-two','1','fixture-two.apk'),
  @('2','1','0','com.afs.plugin.fixturetwo','compat-fixture','1','fixture-collision.apk')
 )
 foreach($v in $variants){
  $env:AFS_PLUGIN_VERSION_CODE=$v[0];$env:AFS_PLUGIN_VERSION_NAME="1.0.$($v[0])";$env:AFS_FIXTURE_PROTOCOL=$v[1];$env:AFS_FIXTURE_PREPARE=$v[2]
  $env:AFS_FIXTURE_PACKAGE=$v[3];$env:AFS_FIXTURE_ID=$v[4];$env:AFS_FIXTURE_READ_ONLY=$v[5]
  $env:AFS_PLUGIN_APK_OUTPUT=(Join-Path $Artifacts $v[6]).Replace('\','/')
  Build 'dpc/test/fixture-plugin/build.sh' ("build-"+$v[6]+'.log')
 }
 # This deliberately untrusted key and APK are test fixtures, never release assets.
 $badKey=Join-Path $Artifacts 'untrusted-test.keystore'
 if(!(Test-Path -LiteralPath $badKey)){
  & keytool -genkeypair -keystore $badKey -storetype PKCS12 -alias afs-plugin-qa -keyalg RSA -keysize 2048 -validity 3650 -storepass afsplugintest -keypass afsplugintest -dname 'CN=AFS Untrusted Test' *> (Join-Path $Artifacts 'build-untrusted-key.log')
  if($LASTEXITCODE -ne 0){throw 'Could not create the untrusted test key'}
 }
 $env:AFS_RELEASE='0';$env:AFS_PLUGIN_TEST_MODE='1';$env:AFS_KEYSTORE=$badKey.Replace('\','/');$env:AFS_KEY_ALIAS='afs-plugin-qa';$env:AFS_STORE_PASS='afsplugintest';$env:AFS_KEY_PASS='afsplugintest'
 $env:AFS_PLUGIN_VERSION_CODE='1';$env:AFS_PLUGIN_VERSION_NAME='1.0.0';$env:AFS_FIXTURE_PACKAGE='com.afs.plugin.untrusted';$env:AFS_FIXTURE_ID='untrusted-qa';$env:AFS_FIXTURE_PROTOCOL='1';$env:AFS_FIXTURE_PREPARE='0';$env:AFS_FIXTURE_READ_ONLY='1'
 $env:AFS_PLUGIN_APK_OUTPUT=(Join-Path $Artifacts 'fixture-untrusted.apk').Replace('\','/')
 Build 'dpc/test/fixture-plugin/build.sh' 'build-untrusted.log'
 Get-ChildItem -LiteralPath $Artifacts -Filter '*.apk' | Where-Object {$_.Name -notmatch 'base|aligned'} | ForEach-Object {"$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())  $($_.Name)"} | Set-Content -LiteralPath (Join-Path $Artifacts 'SHA256SUMS.txt') -Encoding ascii
 Write-Output "Plugin fixture artifacts: $Artifacts (production trust=$([bool]$Production))"
}finally{foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
