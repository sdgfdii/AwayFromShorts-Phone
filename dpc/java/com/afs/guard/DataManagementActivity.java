package com.afs.guard;
import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import android.text.InputType;
import java.io.*;
import org.json.JSONObject;
public final class DataManagementActivity extends ThemedActivity {
    private static final int EXPORT=8201,IMPORT=8202;private Ui ui;private LinearLayout body;private boolean busy;private long operation;private TextView operationStatus;private AlertDialog restorePreview;
    private interface RestartScheduler {void schedule(Context app)throws Exception;}
    private RestartScheduler restartScheduler=app->{Intent i=new Intent(app,DataManagementActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pending=PendingIntent.getActivity(app,8203,i,PendingIntent.FLAG_CANCEL_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarm=(AlarmManager)app.getSystemService(ALARM_SERVICE);alarm.set(AlarmManager.RTC,System.currentTimeMillis()+1200,pending);};
    @Override protected void onDestroy(){operation++;if(restorePreview!=null){restorePreview.dismiss();restorePreview=null;}super.onDestroy();}
    protected void onCreate(Bundle b){super.onCreate(b);ui=new Ui(this);ScrollView scroll=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(1);body.setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(32));scroll.addView(body);setContentView(scroll);render();}
    private void text(String s){TextView t=new TextView(this);t.setText(s);t.setTextColor(ui.text());t.setPadding(0,ui.dp(8),0,ui.dp(8));body.addView(t);}
    private void button(String label,Runnable run){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->{if(busy){toast(T.s("操作处理中，请稍候"));return;}try{run.run();}catch(RuntimeException error){toast(error.getMessage());}});body.addView(b);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void setBusy(boolean value,String message){busy=value;if(operationStatus!=null){operationStatus.setText(message);operationStatus.setVisibility(value?android.view.View.VISIBLE:android.view.View.GONE);}}
    private void postResult(long token,Runnable result){runOnUiThread(()->{if(token==operation&&!isFinishing()&&!isDestroyed())result.run();});}
    private void render(){body.removeAllViews();text(T.s("数据与备份"));operationStatus=new TextView(this);operationStatus.setTextColor(ui.text2());body.addView(operationStatus);setBusy(busy,T.s("操作处理中…"));text(T.s("学习数据保存在本机应用私有目录。完整备份包含目标、打卡、记忆与图片、题库与答题记录、卡片、笔记与附件、冻结规则；不包含密码、token、AI 密钥和设备管理授权。备份为未加密 ZIP，请存放在自己的安全位置。当前设备安全上限：解压后 ")+(LocalArchive.LIMIT/1024/1024)+T.s("MB。"));
        button(T.s("导出完整本地备份"),()->pick(EXPORT));button(T.s("从完整备份恢复"),()->{if(!LocalBackup.mayRestore(this)){toast(T.s("完整覆盖恢复仅限未启用设备管理或首次配置期。日常内容导入请使用下方各模块入口。"));return;}pick(IMPORT);});
        String result=getSharedPreferences("afs_data_status",0).getString("result","");if(!result.isEmpty())text(result);
        text(T.s("内容导入与导出"));button(T.s("记忆卡片：导入、导出与模块备份"),()->startActivity(new Intent(this,MemoryActivity.class)));button(T.s("题库：导入与导出"),()->startActivity(new Intent(this,QuizActivity.class)));button(T.s("笔记：文件导入、导出与合并恢复"),()->startActivity(new Intent(this,MemoActivity.class)));
        text(T.s("云端同步 · ")+SyncSettings.state(this));
        button(SyncConfig.paused(this)?T.s("重新启用云端同步"):T.s("配置云端同步（Gist 目标与密钥）"),()->{
            if(SyncConfig.paused(this)&&SyncConfig.target(this)!=null){SyncConfig.resume(this);toast(T.s("云端同步已重新启用，正在补推本机台账"));new Thread(()->{boolean ok=Hub.pushNowSync(this);if(!ok)runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())toast(T.s("补推失败，检查网络后点「上传打卡数据」重试"));});},"afs-sync-resume").start();render();}
            else SyncSettings.showGist(this);
        });
        button(T.s("上传打卡数据"),()->{toast(T.s("正在上传"));new Thread(()->{boolean ok=Hub.pushNowSync(this);runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())toast(ok?T.s("已上传 ")+SyncSettings.at(this)+" · rev "+Hub.ledgerRev(this):(SyncConfig.configured(this)?T.s("上传失败，检查网络后重试"):T.s("云端同步未启用，请先配置")));});},"afs-data-upload").start();});
        text(T.s("其他云端目标（占位）：WebDAV / GitHub 私有仓库 / Git 仓库 只保存配置，尚未实现传输，不影响上面的个人同步。"));
        button(T.s("WebDAV（占位）"),()->profile("webdav"));button(T.s("GitHub 私有仓库（占位）"),()->profile("github"));button(T.s("Git 仓库（占位）"),()->profile("git"));button(T.s("返回"),this::finish);
    }
    private void pick(int request){Intent i=new Intent(request==EXPORT?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(request==EXPORT?"application/zip":"*/*");if(request==EXPORT)i.putExtra(Intent.EXTRA_TITLE,"AwayFromShorts-backup-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.US).format(new java.util.Date())+".zip");try{startActivityForResult(i,request);}catch(ActivityNotFoundException e){toast(T.s("没有可用的系统文件选择器"));}}
    protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null||(request!=EXPORT&&request!=IMPORT))return;
        final android.net.Uri uri=data.getData();final long token=++operation;final Context app=getApplicationContext();
        setBusy(true,request==EXPORT?T.s("正在导出本地备份…"):T.s("正在校验本地备份…"));
        new Thread(()->{try{
            if(request==EXPORT){try(OutputStream out=app.getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException(T.s("无法打开目标文件"));LocalBackup.export(app,out);}
                postResult(token,()->{setBusy(false,"");toast(T.s("本地备份已导出"));});
            }else{final String summary;try(InputStream in=app.getContentResolver().openInputStream(uri)){summary=LocalBackup.inspect(in);}
                postResult(token,()->{setBusy(false,"");restorePreview=ThemeStore.dialog(this).setTitle(T.s("完整覆盖恢复？")).setMessage(summary+T.s("\n\n将覆盖本机对应数据。确认后应用会重启；写入失败会回滚，现有云端凭据和管理授权不变。建议先导出当前备份。"))
                    .setNegativeButton(T.s("取消"),null).setPositiveButton(T.s("恢复并重启"),(d,w)->queueRestore(uri)).show();});
            }
        }catch(Exception error){postResult(token,()->{setBusy(false,"");toast(T.s("操作失败：")+error.getMessage());});}},"afs-local-backup").start();
    }
    private void queueRestore(android.net.Uri uri){
        final long token=++operation;final Context app=getApplicationContext();setBusy(true,T.s("正在准备恢复…"));
        new Thread(()->{try{try(InputStream in=app.getContentResolver().openInputStream(uri)){LocalBackup.queue(app,in);}
            // Queue/restart is accepted business work, even if the original page has gone away.
            runOnUiThread(()->restart(token));
        }catch(Exception error){postResult(token,()->{setBusy(false,"");toast(T.s("恢复未开始：")+error.getMessage());});}},"afs-restore").start();
    }
    private void restart(long token){
        Context app=getApplicationContext();try{restartScheduler.schedule(app);
            if(!isFinishing()&&!isDestroyed())finishAffinity();android.os.Process.killProcess(android.os.Process.myPid());
        }catch(Exception error){if(token==operation&&!isFinishing()&&!isDestroyed()){setBusy(false,"");operationStatus.setText(T.s("备份已准备，请完全重启应用以完成恢复"));operationStatus.setVisibility(android.view.View.VISIBLE);}Toast.makeText(app,T.s("已准备恢复，请完全重启应用：")+error.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private EditText input(LinearLayout form,String label,String value,boolean secret){TextView t=new TextView(this);t.setText(label);form.addView(t);EditText e=new EditText(this);e.setSingleLine(true);e.setSaveEnabled(false);e.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));e.setText(value);form.addView(e);return e;}
    private void profile(String provider){JSONObject saved=CloudProfiles.load(this,provider);ScrollView scroll=new ScrollView(this);LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(ui.dp(16),0,ui.dp(16),0);scroll.addView(form);
        EditText target=input(form,provider.equals("github")?"owner/private-repository":T.s("仓库或 WebDAV HTTPS 地址"),saved.optString("target"),false);EditText branch=input(form,T.s("分支（WebDAV 无需填写）"),saved.optString("branch",provider.equals("webdav")?"":"main"),false);EditText path=input(form,T.s("备份相对目录"),saved.optString("path","AwayFromShorts"),false);EditText user=input(form,T.s("用户名（可选）"),saved.optString("username"),false);EditText secret=input(form,T.s("应用密码 / token（留空保留）"),"",true);TextView error=new TextView(this);error.setText(saved.has("target")?T.s("已保存配置；尚未执行云端传输"):T.s("尚未配置；保存不会连接服务器"));form.addView(error);
        AlertDialog dialog=ThemeStore.dialog(this).setTitle(T.s("云同步配置")).setView(scroll).setPositiveButton(T.s("保存配置"),null).setNeutralButton(T.s("删除配置"),null).setNegativeButton(T.s("取消"),null).create();dialog.setOnShowListener(d->{dialog.getButton(-1).setOnClickListener(v->{try{JSONObject p=new JSONObject().put("target",target.getText().toString().trim()).put("branch",branch.getText().toString().trim()).put("path",path.getText().toString().trim()).put("username",user.getText().toString().trim());CloudProfiles.save(this,provider,p,secret.getText().toString());dialog.dismiss();toast(T.s("配置已保存，尚未进行云端传输"));}catch(Exception e){error.setText(e.getMessage());}});dialog.getButton(-3).setOnClickListener(v->{CloudProfiles.remove(this,provider);dialog.dismiss();toast(T.s("配置已删除"));});});dialog.show();
    }
}
