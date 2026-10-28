[CmdletBinding()]
param([string]$SigningConfig,[string]$Sdk,[string]$Python,[string]$Bash,
      [string]$Version='1.56.0',[int]$VersionCode=2177,[int]$MaintenanceVersionCode=2178)
$ErrorActionPreference='Stop'
if($Version -notmatch '^\d+\.\d+\.\d+$' -or $VersionCode -lt 1 -or $MaintenanceVersionCode -le $VersionCode){throw 'Invalid release version or version codes'}
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(!$SigningConfig){$SigningConfig=Join-Path $env:LOCALAPPDATA 'AwayFromShorts/Signing/phone-release-v1.json'}
if(!$Sdk){$Sdk=$env:AFS_SDK}
if(!$Sdk){$Sdk=Join-Path $env:USERPROFILE '.afs-android/sdk'}
if(!$Python){$Python=(Get-Command python.exe -ErrorAction Stop).Source}
if(!$Bash){
    $git=(Get-Command git.exe -ErrorAction Stop).Source
    $Bash=Join-Path (Split-Path (Split-Path $git)) 'bin/bash.exe'
}
if(!(Test-Path -LiteralPath $Bash)){throw 'Git Bash not found; specify -Bash.'}
$config=Get-Content -Raw -Encoding UTF8 -LiteralPath $SigningConfig | ConvertFrom-Json
$out=Join-Path $root ('dist/'+$Version)
New-Item -ItemType Directory -Force $out | Out-Null
$names=@('AFS_RELEASE','AFS_KEYSTORE','AFS_KEY_ALIAS','AFS_STORE_PASS','AFS_KEY_PASS','AFS_APK_OUTPUT','AFS_SDK','AFS_PYTHON','AFS_PREFILL','AFS_RECOVERY','AFS_VERSION_CODE','AFS_VERSION_NAME')
$old=@{}
foreach($name in $names){$old[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    $env:AFS_RELEASE='1'
    $env:AFS_RECOVERY='0'
    $env:AFS_VERSION_CODE=[string]$VersionCode
    $env:AFS_VERSION_NAME=$Version
    $env:AFS_KEYSTORE=$config.keystore.Replace('\','/')
    $env:AFS_KEY_ALIAS=$config.alias
    $env:AFS_STORE_PASS=$config.password
    $env:AFS_KEY_PASS=$config.password
    $env:AFS_APK_OUTPUT=(Join-Path $out 'afs-guard.apk').Replace('\','/')
    $env:AFS_SDK=$Sdk.Replace('\','/')
    $env:AFS_PYTHON=$Python.Replace('\','/')
    $env:AFS_PREFILL='0'
    & $Bash (Join-Path $root 'dpc/build.sh')
    if($LASTEXITCODE -ne 0){throw 'Release build failed'}
    Write-Output "Release APK: $out/afs-guard.apk"
    $env:AFS_RECOVERY='1'
    $env:AFS_VERSION_CODE=[string]$MaintenanceVersionCode
    $env:AFS_APK_OUTPUT=(Join-Path $out 'maintenance/afs-recovery.apk').Replace('\','/')
    & $Bash (Join-Path $root 'dpc/build.sh')
    if($LASTEXITCODE -ne 0){throw 'Maintenance build failed'}
    Write-Output "Maintenance APK: $out/maintenance/afs-recovery.apk"
} finally {
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$old[$name],'Process')}
}
