param([string]$Serial='3B66290094000000',[string]$Python,[switch]$BuildOnly)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskSdk=Join-Path $env:USERPROFILE '.afs-android/sdk'
$taskAndroid=Join-Path $taskSdk 'platforms/android-34/android.jar'
$taskBt=Get-ChildItem (Join-Path $taskSdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$taskAdb=Join-Path $taskSdk 'platform-tools/adb.exe'
$taskClasses=Join-Path $taskRoot 'build/classes.jar'
if(!(Test-Path -LiteralPath $taskClasses)){throw 'Build the target APK first.'}
if(!$Python){$Python=(Get-Command python -ErrorAction Stop).Source}
$taskOut=Join-Path $taskRoot 'build/device-appearance'
New-Item -ItemType Directory -Force "$taskOut/classes","$taskOut/dex" | Out-Null
function Invoke-Checked {param([string]$File,[string[]]$Arguments) & $File @Arguments;if($LASTEXITCODE -ne 0){throw "$File failed: $LASTEXITCODE"}}
Invoke-Checked "$taskBt/aapt2.exe" @('link','-o',"$taskOut/base.apk",'-I',$taskAndroid,'--manifest',"$PSScriptRoot/device/AppearanceManifest.xml",'--min-sdk-version','24','--target-sdk-version','34')
Invoke-Checked (Get-Command javac).Source @('--release','8','-encoding','UTF-8','-cp',"$taskAndroid;$taskClasses",'-d',"$taskOut/classes","$PSScriptRoot/device/AppearanceDeviceTest.java")
Invoke-Checked (Get-Command jar).Source @('cf',"$taskOut/classes.jar",'-C',"$taskOut/classes",'.')
Invoke-Checked "$taskBt/d8.bat" @('--lib',$taskAndroid,'--classpath',$taskClasses,'--min-api','24','--output',"$taskOut/dex","$taskOut/classes.jar")
Invoke-Checked $Python @('-c','import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],"a",zipfile.ZIP_DEFLATED); z.write(sys.argv[2],"classes.dex"); z.close()',"$taskOut/base.apk","$taskOut/dex/classes.dex")
Invoke-Checked "$taskBt/zipalign.exe" @('-f','-p','4',"$taskOut/base.apk","$taskOut/aligned.apk")
Invoke-Checked "$taskBt/apksigner.bat" @('sign','--ks',"$taskRoot/keystore/afs.keystore",'--ks-pass','pass:afsguard','--key-pass','pass:afsguard','--out',"$taskOut/qa.apk","$taskOut/aligned.apk")
if($BuildOnly){Write-Output "Prepared appearance checks: $taskOut/qa.apk";return}
Invoke-Checked $taskAdb @('-s',$Serial,'install','-r',"$taskOut/qa.apk")
try {
    $taskResult=& $taskAdb -s $Serial shell am instrument -w 'com.afs.guard.themeqa/com.afs.guard.AppearanceDeviceTest' 2>&1
    $taskResult | Write-Output
    $taskText=$taskResult -join "`n"
    if($LASTEXITCODE -ne 0 -or $taskText -notmatch 'DEVICE APPEARANCE: \d+ assertions passed' -or $taskText -match 'INSTRUMENTATION_FAILED|AssertionError|Exception:|Process crashed'){throw 'Appearance checks did not pass; inspect the result before continuing.'}
} finally {
    & $taskAdb -s $Serial uninstall com.afs.guard.themeqa | Out-Null
    & $taskAdb -s $Serial shell am broadcast --include-stopped-packages -a com.afs.guard.WAKE | Out-Null
}
