[CmdletBinding()]
param([string]$SigningConfig,[string]$Sdk,[string]$Python,[string]$Bash,[string]$Version='1.0.0',[int]$VersionCode=1,[string]$Output)
$ErrorActionPreference='Stop'
if($Version -notmatch '^\d+\.\d+\.\d+$' -or $VersionCode -lt 1){throw 'Invalid plugin version'}
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(!$SigningConfig){$SigningConfig=Join-Path $env:LOCALAPPDATA 'AwayFromShorts/Signing/phone-release-v1.json'}
if(!$Sdk){$Sdk=Join-Path $env:USERPROFILE '.afs-android/sdk'}
if(!$Python){$Python=(Get-Command python.exe -ErrorAction Stop).Source}
if($Python -like '*\WindowsApps\*'){throw 'Pass -Python with a working interpreter'}
if(!$Bash){$git=(Get-Command git.exe -ErrorAction Stop).Source;$Bash=Join-Path (Split-Path (Split-Path $git)) 'bin/bash.exe'}
if(!$Output){$Output=Join-Path $root "dist/plugins/usage-stats/$Version/afs-plugin-usage-stats.apk"}
$Output=[IO.Path]::GetFullPath($Output)
$config=Get-Content -LiteralPath $SigningConfig -Raw -Encoding UTF8 | ConvertFrom-Json
$names=@('AFS_RELEASE','AFS_KEYSTORE','AFS_KEY_ALIAS','AFS_STORE_PASS','AFS_KEY_PASS','AFS_PLUGIN_TEST_MODE','AFS_PLUGIN_APK_OUTPUT','AFS_PLUGIN_VERSION_CODE','AFS_PLUGIN_VERSION_NAME','AFS_SDK','AFS_PYTHON')
$saved=@{};foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
 $env:AFS_RELEASE='1';$env:AFS_PLUGIN_TEST_MODE='0'
 $env:AFS_KEYSTORE=$config.keystore.Replace('\','/');$env:AFS_KEY_ALIAS=$config.alias
 $env:AFS_STORE_PASS=$config.password;$env:AFS_KEY_PASS=$config.password
 $env:AFS_PLUGIN_VERSION_CODE=[string]$VersionCode;$env:AFS_PLUGIN_VERSION_NAME=$Version
 $env:AFS_PLUGIN_APK_OUTPUT=$Output.Replace('\','/');$env:AFS_SDK=$Sdk.Replace('\','/');$env:AFS_PYTHON=$Python.Replace('\','/')
 & $Bash (Join-Path $PSScriptRoot 'build.sh')
 if($LASTEXITCODE -ne 0){throw 'Plugin release build failed'}
 $hash=(Get-FileHash -LiteralPath $Output -Algorithm SHA256).Hash.ToLowerInvariant()
 "$hash  $([IO.Path]::GetFileName($Output))" | Set-Content -LiteralPath ($Output+'.sha256') -Encoding ascii
 Write-Output "Signed plugin: $Output"
}finally{foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
