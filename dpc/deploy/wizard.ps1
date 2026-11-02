[CmdletBinding()]
param([string]$Serial,[string]$Apk,[string]$Adb,[switch]$SelfTest,[string]$PreviewPath,[switch]$ProbeScan)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
$form=New-Object Windows.Forms.Form
$form.Text='AwayFromShorts 安装与升级';$form.Size=New-Object Drawing.Size(760,630);$form.StartPosition='CenterScreen';$form.MinimumSize=$form.Size
$intro=New-Object Windows.Forms.Label;$intro.Text='连接手机并开启 USB 调试。先检查，再安装；已授权设备自动按升级流程处理。';$intro.SetBounds(20,18,700,42);$form.Controls.Add($intro)
$guide=New-Object Windows.Forms.LinkLabel;$guide.Text='查看安装、升级和恢复说明';$guide.SetBounds(20,42,690,18);$form.Controls.Add($guide)
$guide.Add_LinkClicked({$readme=Join-Path $PSScriptRoot 'README.md';if(!(Test-Path -LiteralPath $readme)){$readme=Join-Path $PSScriptRoot '../../docs/DEPLOYMENT-AND-USABILITY.md'};if(Test-Path -LiteralPath $readme){Invoke-Item -LiteralPath $readme}else{$status.Text='说明文件缺失，请完整解压部署包。'}})
$devices=New-Object Windows.Forms.ComboBox;$devices.DropDownStyle='DropDownList';$devices.DisplayMember='Label';$devices.SetBounds(20,65,505,30);$form.Controls.Add($devices)
$scan=New-Object Windows.Forms.Button;$scan.Text='刷新设备';$scan.SetBounds(540,65,170,30);$form.Controls.Add($scan)
$consent=New-Object Windows.Forms.CheckBox;$consent.Text='首次授权将限制强停、卸载、安全模式、改系统时间和系统内恢复出厂；已阅读恢复说明并同意。';$consent.SetBounds(20,108,690,42);$form.Controls.Add($consent)
$check=New-Object Windows.Forms.Button;$check.Text='检查设备（不安装）';$check.SetBounds(20,158,220,38);$form.Controls.Add($check)
$install=New-Object Windows.Forms.Button;$install.Text='安装 / 保留数据升级';$install.SetBounds(255,158,220,38);$form.Controls.Add($install)
$export=New-Object Windows.Forms.Button;$export.Text='保存诊断日志';$export.SetBounds(490,158,220,38);$form.Controls.Add($export)
$progress=New-Object Windows.Forms.ProgressBar;$progress.SetBounds(20,210,690,16);$form.Controls.Add($progress)
$status=New-Object Windows.Forms.Label;$status.Text='未开始';$status.SetBounds(20,238,690,28);$form.Controls.Add($status)
$log=New-Object Windows.Forms.TextBox;$log.Multiline=$true;$log.ReadOnly=$true;$log.ScrollBars='Vertical';$log.SetBounds(20,275,690,260);$log.Anchor='Top,Bottom,Left,Right';$form.Controls.Add($log)
$help=New-Object Windows.Forms.Label;$help.Text='账号、分身或其他管理者阻挡首次授权时会明确停止；工具不会清机或删除账号。';$help.SetBounds(20,547,690,28);$help.Anchor='Bottom,Left,Right';$form.Controls.Add($help)
$script:process=$null;$script:line=$null;$script:errors=$null;$script:mode='';$script:scanLines=@()
if(!$Adb){$candidates=@((Join-Path $PSScriptRoot 'tools/adb.exe'));foreach($sdk in @($env:ANDROID_HOME,$env:ANDROID_SDK_ROOT,$env:AFS_SDK)){if($sdk){$candidates+=Join-Path $sdk 'platform-tools/adb.exe'}};$found=Get-Command adb.exe -ErrorAction SilentlyContinue;if($found){$candidates+=$found.Source};foreach($candidate in $candidates){if(Test-Path -LiteralPath $candidate){$Adb=$candidate;break}}}
function Quote-Ps([string]$s){return "'"+$s.Replace("'","''")+"'"}
function Start-Task([string]$kind){
 if($script:process){return}
 if(!$Adb -or !(Test-Path -LiteralPath $Adb)){$status.Text='缺少 ADB：请使用包含 tools/adb.exe 的完整部署包。';return}
 $script:mode=$kind;$script:scanLines=@();$log.AppendText("`r`n--- $kind ---`r`n")
 if($kind -eq 'scan'){$command='& '+(Quote-Ps $Adb)+' devices -l; exit $LASTEXITCODE'}else{
  if(!$devices.SelectedItem){$status.Text='请先选择在线设备。';return}
  $selected=[string]$devices.SelectedItem.Serial
  $command='& '+(Quote-Ps (Join-Path $PSScriptRoot 'deploy.ps1'))+' -NonInteractive -Serial '+(Quote-Ps $selected)+' -Adb '+(Quote-Ps $Adb)
  if($Apk){$command+=' -Apk '+(Quote-Ps $Apk)}
  if($kind -eq 'check'){$command+=' -CheckOnly'}elseif($consent.Checked){$command+=' -AcceptDeviceOwner'}
  $command+='; exit $LASTEXITCODE'
 }
 $info=New-Object Diagnostics.ProcessStartInfo;$info.FileName='powershell.exe';$info.Arguments='-NoProfile -InputFormat Text -OutputFormat Text -ExecutionPolicy Bypass -EncodedCommand '+[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command));$info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true;$info.StandardOutputEncoding=[Text.Encoding]::UTF8;$info.StandardErrorEncoding=[Text.Encoding]::UTF8
 $script:process=New-Object Diagnostics.Process;$script:process.StartInfo=$info
 try{$null=$script:process.Start();$script:line=$script:process.StandardOutput.ReadLineAsync();$script:errors=$script:process.StandardError.ReadToEndAsync();foreach($b in @($scan,$check,$install,$consent)){$b.Enabled=$false};$progress.Style='Marquee';$status.Text=if($kind -eq 'scan'){'正在识别设备…'}elseif($kind -eq 'check'){'正在检查管理状态、账号及部署条件…'}else{'正在安装、验证授权及后台运行状态…'};$timer.Start()}catch{$status.Text=$_.Exception.Message;$script:process=$null}
}
$timer=New-Object Windows.Forms.Timer;$timer.Interval=100
$timer.Add_Tick({
 try{
  if(!$script:process){return}
  while($script:line -and $script:line.IsCompleted){$value=$script:line.GetAwaiter().GetResult();if($null -eq $value){$script:line=$null;break};$log.AppendText($value+"`r`n");if($script:mode -eq 'scan'){$script:scanLines+=$value};$script:line=$script:process.StandardOutput.ReadLineAsync()}
  if($script:process.HasExited -and !$script:line -and $script:errors.IsCompleted){
   $exitCode=$script:process.ExitCode;$log.AppendText($script:errors.GetAwaiter().GetResult());$script:process.Dispose();$script:process=$null;$timer.Stop();$progress.Style='Blocks';$progress.Value=if($exitCode -eq 0){100}else{0};foreach($b in @($scan,$check,$install,$consent)){$b.Enabled=$true}
   if($script:mode -eq 'scan'){$devices.Items.Clear();foreach($value in $script:scanLines){if($value -match '^(\S+)\s+device(?:\s|$)'){$id=$Matches[1];$model=if($value -match 'model:(\S+)'){$Matches[1]}else{'Android'};$link=if($id.Contains(':')){'无线'}else{'USB'};$null=$devices.Items.Add([pscustomobject]@{Label="$model · $link · $id";Serial=$id})}};if($devices.Items.Count){$devices.SelectedIndex=0;if($Serial){for($i=0;$i -lt $devices.Items.Count;$i++){if($devices.Items[$i].Serial -eq $Serial){$devices.SelectedIndex=$i}}};$status.Text='请确认设备，随后先检查或直接安装。'}else{$status.Text='尚无在线设备：请解锁手机并允许 USB 调试，再刷新。'}}else{$status.Text=if($exitCode -eq 0){if($script:mode -eq 'check'){'条件检查通过，可安装；版本及签名由系统校验。'}else{'安装与运行状态验证完成。'}}else{'操作已停止，请查看原因；可保存日志。'}}
  }
 }catch{$timer.Stop();$status.Text=$_.Exception.Message;foreach($b in @($scan,$check,$install,$consent)){$b.Enabled=$true};$script:process=$null}
})
$scan.Add_Click({Start-Task 'scan'});$check.Add_Click({Start-Task 'check'});$install.Add_Click({Start-Task 'install'})
$export.Add_Click({$save=New-Object Windows.Forms.SaveFileDialog;$save.Filter='文本日志 (*.txt)|*.txt';$save.FileName='afs-deployment-diagnostic.txt';if($save.ShowDialog() -eq 'OK'){[IO.File]::WriteAllText($save.FileName,$log.Text,[Text.UTF8Encoding]::new($false))};$save.Dispose()})
$form.Add_FormClosing({param($sender,$event)if($script:process){$event.Cancel=$true;$status.Text='操作尚未结束，请等待完成后关闭，避免中断授权。'}})
if(!$SelfTest){$form.Add_Shown({Start-Task 'scan'})}
if($SelfTest){
 $form.ShowInTaskbar=$false;$form.StartPosition='Manual';$form.Location=New-Object Drawing.Point(-30000,-30000);$form.Show();[Windows.Forms.Application]::DoEvents();$form.PerformLayout();if($form.Controls.Count -lt 10){throw 'Wizard controls missing'}
 if($ProbeScan){Start-Task 'scan';$deadline=[DateTime]::UtcNow.AddSeconds(15);while($script:process -and [DateTime]::UtcNow -lt $deadline){[Windows.Forms.Application]::DoEvents();Start-Sleep -Milliseconds 15};if($script:process -or $devices.Items.Count -lt 1){throw 'Wizard asynchronous device scan failed'};Write-Output ("Wizard asynchronous scan passed: "+$devices.Items.Count+" devices")}
 if($PreviewPath){$bitmap=New-Object Drawing.Bitmap($form.Width,$form.Height);$form.DrawToBitmap($bitmap,(New-Object Drawing.Rectangle(0,0,$form.Width,$form.Height)));$bitmap.Save([IO.Path]::GetFullPath($PreviewPath));$bitmap.Dispose()}
 Write-Output 'Wizard layout self-check passed.' 
}else{$null=$form.ShowDialog()}
$timer.Dispose();$form.Dispose()
