[CmdletBinding()]
param([string]$SigningConfig,[string]$Sdk,[string]$Python,[string]$Bash,
      [string]$Version,[int]$VersionCode,[int]$MaintenanceVersionCode,
      [string]$Notes,[string]$NotesFile,[string]$Mirrors,[string]$Repo,[switch]$SkipPackage)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
# Single source of truth for the version is dpc/build.sh; follow it when no explicit value is given.
if(!$Version -or $VersionCode -lt 1){
    $sh=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $root 'dpc/build.sh')
    if(!$Version){
        $m=[regex]::Match($sh,'AFS_VERSION_NAME:-\s*([\d.]+)')
        if(!$m.Success){throw 'Cannot read AFS_VERSION_NAME from dpc/build.sh'}
        $Version=$m.Groups[1].Value
    }
    if($VersionCode -lt 1){
        $m=[regex]::Match($sh,'AFS_VERSION_CODE:-\s*(\d+)')
        if(!$m.Success){throw 'Cannot read AFS_VERSION_CODE from dpc/build.sh'}
        $VersionCode=[int]$m.Groups[1].Value
    }
}
if($MaintenanceVersionCode -lt 1){$MaintenanceVersionCode=$VersionCode+1}
if($Version -notmatch '^\d+\.\d+\.\d+$' -or $VersionCode -lt 1 -or $MaintenanceVersionCode -le $VersionCode){throw 'Invalid release version or version codes'}
if(!$SigningConfig){$SigningConfig=Join-Path $env:LOCALAPPDATA 'AwayFromShorts/Signing/phone-release-v1.json'}
if(!$Sdk){$Sdk=$env:AFS_SDK}
if(!$Sdk){$Sdk=Join-Path $env:USERPROFILE '.afs-android/sdk'}
if(!$Python){
    # On a store-enabled Windows, Get-Command python.exe resolves to the WindowsApps
    # stub: it starts, does nothing and exits non-zero. That silently kills the asset
    # packing step and the packaging step. Skip any WindowsApps hit and look for a
    # real interpreter instead.
    $cands=@()
    $found=Get-Command python.exe,python3.exe -ErrorAction SilentlyContinue
    foreach($f in $found){
        if($f.Source -notlike '*\WindowsApps\*'){$cands+=$f.Source}
    }
    $glob=Get-ChildItem -Path (Join-Path $env:USERPROFILE '.workbuddy/binaries/python/versions/*/python.exe') -ErrorAction SilentlyContinue
    foreach($g in $glob){$cands+=$g.FullName}
    if($cands.Count -gt 0){$Python=$cands[0]}
}
if(!$Python){throw 'No usable python.exe found (WindowsApps stub does not count); pass -Python.'}
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
    if(!$SkipPackage){
        # Package = verify signature/identity + manifest.json (notes and mirror links) + zips + sums.
        $pack=@((Join-Path $root 'dpc/release/package_release.py'),'--sdk',$Sdk,
                '--version',$Version,'--version-code',[string]$VersionCode,
                '--maintenance-version-code',[string]$MaintenanceVersionCode)
        if($Repo){$pack+=@('--repo',$Repo)}
        if($Mirrors){$pack+=@('--mirrors',$Mirrors)}
        if($Notes){$pack+=@('--notes',$Notes)}
        if($NotesFile){$pack+=@('--notes-file',(Resolve-Path -LiteralPath $NotesFile).Path)}
        & $Python @pack
        if($LASTEXITCODE -ne 0){throw 'Release packaging failed'}
    }
} finally {
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$old[$name],'Process')}
}
