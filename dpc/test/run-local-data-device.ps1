param([string]$Python,[string]$Serial='emulator-5582')
$ErrorActionPreference='Stop'
if($Serial -notlike "emulator-*"){throw "Sync fixture tests require an emulator serial"}
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskSdk=Join-Path $env:USERPROFILE '.afs-android/sdk'
$taskAndroid=Join-Path $taskSdk 'platforms/android-34/android.jar'
$taskBt=Join-Path $taskSdk 'build-tools/34.0.0'
$taskAdb=Join-Path $taskSdk 'platform-tools/adb.exe'
$taskClasses=Join-Path $taskRoot 'build/classes.jar'
$taskPdf=Join-Path $taskRoot 'libs/pdfbox-android-2.0.27.0.jar'
if(!$Python){$Python=(Get-Command python).Source}
$taskOut=Join-Path $taskRoot 'build/device-localdata'
New-Item -ItemType Directory -Force "$taskOut/classes","$taskOut/dex" | Out-Null
function Checked {param([string]$File,[string[]]$Arguments) & $File @Arguments;if($LASTEXITCODE -ne 0){throw "$File failed: $LASTEXITCODE"}}
Checked "$taskBt/aapt2.exe" @('link','-o',"$taskOut/base.apk",'-I',$taskAndroid,'--manifest',"$PSScriptRoot/device/LocalDataManifest.xml",'--min-sdk-version','24','--target-sdk-version','34')
Checked (Get-Command javac).Source @('--release','8','-encoding','UTF-8','-cp',"$taskAndroid;$taskClasses;$taskPdf",'-d',"$taskOut/classes","$PSScriptRoot/device/LocalDataDeviceTest.java")
Checked (Get-Command jar).Source @('cf',"$taskOut/classes.jar",'-C',"$taskOut/classes",'.')
Checked "$taskBt/d8.bat" @('--lib',$taskAndroid,'--classpath',$taskClasses,'--classpath',$taskPdf,'--min-api','24','--output',"$taskOut/dex","$taskOut/classes.jar")
# Windows PowerShell 会把内联 -c 里的双引号吃掉（z.write(...,"classes.dex") → 语法崩），
# 所以脚本落到文件再执行。
$taskAddDex=Join-Path $taskOut 'add-dex.py'
$taskAddDexPy=@'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1], "a", zipfile.ZIP_DEFLATED)
z.write(sys.argv[2], "classes.dex")
z.close()
'@
Set-Content -Path $taskAddDex -Value $taskAddDexPy -Encoding ASCII
Checked $Python @($taskAddDex,"$taskOut/base.apk","$taskOut/dex/classes.dex")
Checked "$taskBt/zipalign.exe" @('-f','-p','4',"$taskOut/base.apk","$taskOut/aligned.apk")
$signArgs=@('sign','--ks',"$taskRoot/keystore/afs.keystore",'--ks-pass','pass:afsguard','--key-pass','pass:afsguard')
if($env:AFS_KEYSTORE){$signArgs=@('sign','--ks',$env:AFS_KEYSTORE,'--ks-key-alias',$env:AFS_KEY_ALIAS,'--ks-pass','env:AFS_STORE_PASS','--key-pass','env:AFS_STORE_PASS')}
Checked "$taskBt/apksigner.bat" ($signArgs+@('--out',"$taskOut/qa.apk","$taskOut/aligned.apk"))
Checked $taskAdb @('-s',$Serial,'install','-r',"$taskOut/qa.apk")
try {
    $taskResult=& $taskAdb -s $Serial shell am instrument -w com.afs.guard.localdataqa/com.afs.guard.LocalDataDeviceTest 2>&1
    $taskResult | Write-Output
    $taskText=$taskResult -join "`n"
    if($taskText -notmatch 'Local data device: \d+ checks passed' -or $taskText -match 'INSTRUMENTATION_FAILED|AssertionError|Exception:'){throw 'Sync config device checks failed'}
} finally {
    & $taskAdb -s $Serial uninstall com.afs.guard.localdataqa | Out-Null
    & $taskAdb -s $Serial shell am broadcast --include-stopped-packages -a com.afs.guard.WAKE | Out-Null
}
