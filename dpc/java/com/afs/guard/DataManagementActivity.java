package com.afs.guard;
import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import android.text.InputType;
import java.io.*;
import org.json.JSONObject;
public final class DataManagementActivity extends ThemedActivity {
    private static final int EXPORT=8201,IMPORT=8202;private Ui ui;private LinearLayout body;private boolean busy;
    protected void onCreate(Bundle b){super.onCreate(b);ui=new Ui(this);ScrollView scroll=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(1);body.setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(32));scroll.addView(body);setContentView(scroll);render();}
    private void text(String s){TextView t=new TextView(this);t.setText(s);t.setTextColor(ui.text());t.setPadding(0,ui.dp(8),0,ui.dp(8));body.addView(t);}
    private void button(String label,Runnable run){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->{if(busy){toast("操作处理中，请稍候");return;}try{run.run();}catch(RuntimeException error){toast(error.getMessage());}});body.addView(b);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void render(){body.removeAllViews();text("数据与备份");text("学习数据保存在本机应用私有目录。完整备份包含目标、打卡、记忆与图片、题库与答题记录、卡片、笔记与附件、冻结规则；不包含密码、token、AI 密钥和设备管理授权。备份为未加密 ZIP，请存放在自己的安全位置。当前设备安全上限：解压后 "+(LocalArchive.LIMIT/1024/1024)+"MB。");
        button("导出完整本地备份",()->pick(EXPORT));button("从完整备份恢复",()->{if(!LocalBackup.mayRestore(this)){toast("完整覆盖恢复仅限未启用设备管理或首次配置期。日常内容导入请使用下方各模块入口。");return;}pick(IMPORT);});
        String result=getSharedPreferences("afs_data_status",0).getString("result","");if(!result.isEmpty())text(result);
        text("内容导入与导出");button("记忆卡片：导入、导出与模块备份",()->startActivity(new Intent(this,MemoryActivity.class)));button("题库：导入与导出",()->startActivity(new Intent(this,QuizActivity.class)));button("笔记：文件导入、导出与合并恢复",()->startActivity(new Intent(this,MemoActivity.class)));
        text("云同步配置 · 仅保存配置，实际传输后续实现");button("WebDAV",()->profile("webdav"));button("GitHub 私有仓库",()->profile("github"));button("Git 仓库",()->profile("git"));button("旧版 Gist 同步（现有功能）",()->SyncSettings.showGist(this));button("返回",this::finish);
    }
    private void pick(int request){Intent i=new Intent(request==EXPORT?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(request==EXPORT?"application/zip":"*/*");if(request==EXPORT)i.putExtra(Intent.EXTRA_TITLE,"AwayFromShorts-backup-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.US).format(new java.util.Date())+".zip");try{startActivityForResult(i,request);}catch(ActivityNotFoundException e){toast("没有可用的系统文件选择器");}}
    protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;android.net.Uri uri=data.getData();busy=true;new Thread(()->{try{
        if(request==EXPORT){try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("无法打开目标文件");LocalBackup.export(this,out);}runOnUiThread(()->toast("本地备份已导出"));}
        else if(request==IMPORT){final String summary;try(InputStream in=getContentResolver().openInputStream(uri)){summary=LocalBackup.inspect(in);}runOnUiThread(()->ThemeStore.dialog(this).setTitle("完整覆盖恢复？").setMessage(summary+"\n\n将覆盖本机对应数据。确认后应用会重启；写入失败会回滚，现有云端凭据和管理授权不变。建议先导出当前备份。")
            .setNegativeButton("取消",null).setPositiveButton("恢复并重启",(d,w)->{busy=true;new Thread(()->{try{try(InputStream in=getContentResolver().openInputStream(uri)){LocalBackup.queue(this,in);}runOnUiThread(this::restart);}catch(Exception e){runOnUiThread(()->toast("恢复未开始："+e.getMessage()));}finally{busy=false;}},"afs-restore").start();}).show());}
    }catch(Exception e){runOnUiThread(()->toast("操作失败："+e.getMessage()));}finally{busy=false;}},"afs-local-backup").start();}
    private void restart(){try{Intent i=new Intent(this,DataManagementActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);PendingIntent p=PendingIntent.getActivity(this,8203,i,PendingIntent.FLAG_CANCEL_CURRENT|PendingIntent.FLAG_IMMUTABLE);AlarmManager a=(AlarmManager)getSystemService(ALARM_SERVICE);a.set(AlarmManager.RTC,System.currentTimeMillis()+1200,p);finishAffinity();android.os.Process.killProcess(android.os.Process.myPid());}catch(Exception e){toast("已准备恢复，请完全重启应用："+e.getMessage());}}
    private EditText input(LinearLayout form,String label,String value,boolean secret){TextView t=new TextView(this);t.setText(label);form.addView(t);EditText e=new EditText(this);e.setSingleLine(true);e.setSaveEnabled(false);e.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));e.setText(value);form.addView(e);return e;}
    private void profile(String provider){JSONObject saved=CloudProfiles.load(this,provider);ScrollView scroll=new ScrollView(this);LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(ui.dp(16),0,ui.dp(16),0);scroll.addView(form);
        EditText target=input(form,provider.equals("github")?"owner/private-repository":"仓库或 WebDAV HTTPS 地址",saved.optString("target"),false);EditText branch=input(form,"分支（WebDAV 无需填写）",saved.optString("branch",provider.equals("webdav")?"":"main"),false);EditText path=input(form,"备份相对目录",saved.optString("path","AwayFromShorts"),false);EditText user=input(form,"用户名（可选）",saved.optString("username"),false);EditText secret=input(form,"应用密码 / token（留空保留）","",true);TextView error=new TextView(this);error.setText(saved.has("target")?"已保存配置；尚未执行云端传输":"尚未配置；保存不会连接服务器");form.addView(error);
        AlertDialog dialog=ThemeStore.dialog(this).setTitle("云同步配置").setView(scroll).setPositiveButton("保存配置",null).setNeutralButton("删除配置",null).setNegativeButton("取消",null).create();dialog.setOnShowListener(d->{dialog.getButton(-1).setOnClickListener(v->{try{JSONObject p=new JSONObject().put("target",target.getText().toString().trim()).put("branch",branch.getText().toString().trim()).put("path",path.getText().toString().trim()).put("username",user.getText().toString().trim());CloudProfiles.save(this,provider,p,secret.getText().toString());dialog.dismiss();toast("配置已保存，尚未进行云端传输");}catch(Exception e){error.setText(e.getMessage());}});dialog.getButton(-3).setOnClickListener(v->{CloudProfiles.remove(this,provider);dialog.dismiss();toast("配置已删除");});});dialog.show();
    }
}
