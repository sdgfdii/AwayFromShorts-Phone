param([string]$Name='AFS_Test_API34')
$ErrorActionPreference='Stop'
$taskSdk=Join-Path $env:USERPROFILE '.afs-android/sdk'
$taskEmulator=Join-Path $taskSdk 'emulator/emulator.exe'
if(!(Test-Path -LiteralPath $taskEmulator)){throw 'Android Emulator is not installed.'}
$taskExisting=Get-CimInstance Win32_Process | Where-Object {$_.Name -eq 'qemu-system-x86_64.exe' -and $_.CommandLine -match [regex]::Escape($Name)}
if($taskExisting){Write-Output "$Name is already running.";exit 0}
$taskLog=Join-Path $env:USERPROFILE '.afs-android/avd'
New-Item -ItemType Directory -Force $taskLog | Out-Null
# Direct virtual-device control uses the emulator's authenticated localhost gRPC service.
# This script never invokes adb or selects a physical device.
Start-Process -FilePath $taskEmulator -ArgumentList @('-avd',$Name,'-port','5580','-no-boot-anim','-no-audio','-gpu','swiftshader','-grpc','8554','-grpc-use-token') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskLog 'emulator.log') -RedirectStandardError (Join-Path $taskLog 'emulator-error.log')
Write-Output "Started $Name (Android 14, 1080x2400)."
