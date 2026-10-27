param(
    [string]$Serial,
    [ValidateSet('smoke','seed','inspect','restore')][string]$Mode='smoke',
    [string]$QaDeck,
    [string]$Sdk=$env:AFS_SDK,
    [string]$Python=$env:AFS_PYTHON
)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$Sdk) { $Sdk=Join-Path $env:USERPROFILE '.afs-android/sdk' }
$taskAndroid=Join-Path $Sdk 'platforms/android-34/android.jar'
$taskBt=Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$taskAdb=Join-Path $Sdk 'platform-tools/adb.exe'
$taskClasses=Join-Path $taskRoot 'build/classes.jar'
if (!(Test-Path -LiteralPath $taskClasses)) { throw 'Build dpc/build.sh first; device test compiles against that target.' }
if (!$Python) { $Python=(Get-Command python -ErrorAction Stop).Source }
if (!$Serial) {
    $taskDevices=@(& $taskAdb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($taskDevices.Count -ne 1) { throw 'Connect one device or pass -Serial.' }
    $Serial=$taskDevices[0]
}
$taskOut=Join-Path $taskRoot 'build/device-memory'
New-Item -ItemType Directory -Force "$taskOut/classes","$taskOut/dex" | Out-Null
function Invoke-Checked { param([string]$File,[string[]]$Arguments) & $File @Arguments; if ($LASTEXITCODE -ne 0) { throw "$File failed: $LASTEXITCODE" } }
Invoke-Checked "$taskBt/aapt2.exe" @('link','-o',"$taskOut/base.apk",'-I',$taskAndroid,'--manifest',"$PSScriptRoot/device/AndroidManifest.xml",'--min-sdk-version','24','--target-sdk-version','34')
Invoke-Checked (Get-Command javac).Source @('--release','8','-encoding','UTF-8','-cp',"$taskAndroid;$taskClasses",'-d',"$taskOut/classes","$PSScriptRoot/device/MemoryDeviceTest.java")
Invoke-Checked (Get-Command jar).Source @('cf',"$taskOut/classes.jar",'-C',"$taskOut/classes",'.')
Invoke-Checked "$taskBt/d8.bat" @('--lib',$taskAndroid,'--classpath',$taskClasses,'--min-api','24','--output',"$taskOut/dex","$taskOut/classes.jar")
Invoke-Checked $Python @('-c','import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],"a",zipfile.ZIP_DEFLATED); z.write(sys.argv[2],"classes.dex"); z.close()',"$taskOut/base.apk","$taskOut/dex/classes.dex")
Invoke-Checked "$taskBt/zipalign.exe" @('-f','-p','4',"$taskOut/base.apk","$taskOut/aligned.apk")
Invoke-Checked "$taskBt/apksigner.bat" @('sign','--ks',"$taskRoot/keystore/afs.keystore",'--ks-pass','pass:afsguard','--key-pass','pass:afsguard','--out',"$taskOut/qa.apk","$taskOut/aligned.apk")
Invoke-Checked $taskAdb @('-s',$Serial,'install','-r',"$taskOut/qa.apk")
try {
    $taskArgs=@('-s',$Serial,'shell','am','instrument','-w','-e','mode',$Mode)
    if ($QaDeck) { $taskArgs+=@('-e','deck',$QaDeck) }
    $taskResult=& $taskAdb @taskArgs 'com.afs.guard.memoryqa/com.afs.guard.MemoryDeviceTest' 2>&1
    $taskResult | Write-Output
    if ($LASTEXITCODE -ne 0 -or ($taskResult -match 'INSTRUMENTATION_FAILED|AssertionError|Exception:')) { throw 'Device tests failed; inspect output and preserve the original backup.' }
} finally {
    & $taskAdb -s $Serial uninstall com.afs.guard.memoryqa | Out-Null
    & $taskAdb -s $Serial shell am broadcast --include-stopped-packages -a com.afs.guard.WAKE | Out-Null
}
