package com.afs.guard;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Additional offline tools. No freeze, check-in or learning state is accessed here. */
public final class ToolkitActivity extends ThemedActivity {
    private static final int PICK=81,SAVE=82;
    private Ui ui; private String mode; private LinearLayout content,files; private TextView result,status;
    private EditText expression,value,dateA,dateB,offset,range,edge,quality;
    private Spinner angle,category,from,to,pdfMode,format;
    private final ArrayList<Uri> chosen=new ArrayList<>();
    private Button process,save; private ImageView preview;
    private Job job; private boolean destroyed;
    private static final class Job { volatile boolean running,disposed; volatile File output; volatile String message=""; volatile String mime="",name=""; volatile ToolkitActivity owner;
        void publish(){if(disposed){if(output!=null)output.delete();return;}ToolkitActivity a=owner;if(a!=null)a.runOnUiThread(()->{if(owner==a&&!a.destroyed)a.renderJob();});}
    }
    @Override protected void onCreate(Bundle state){super.onCreate(state);ui=new Ui(this);mode=getIntent().getStringExtra("tool");
        job=(Job)getLastNonConfigurationInstance();if(job==null)job=new Job();job.owner=this;
        if(state!=null){ArrayList<String> list=state.getStringArrayList("chosen");if(list!=null)for(String s:list)chosen.add(Uri.parse(s));if(job.output==null){String path=state.getString("output");if(path!=null){File f=new File(path);if(f.isFile()&&f.getParentFile().equals(getCacheDir()))job.output=f;}job.mime=state.getString("mime","");job.name=state.getString("name","");}}
        String title="calc".equals(mode)?"科学计算器":"units".equals(mode)?"工科单位换算":"dates".equals(mode)?"日期计算":"pdf".equals(mode)?"PDF 合并与提取":"图片处理";
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(ui.dp(20),ui.dp(24),ui.dp(20),ui.dp(20));ui.page(root);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);View back=new ThemeGlyphView(this,"返回",ui.text());back.setContentDescription("返回");back.setPadding(ui.dp(10),ui.dp(10),ui.dp(10),ui.dp(10));back.setBackground(ui.round(ui.surface(),99));back.setOnClickListener(v->finish());bar.addView(back,new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));TextView heading=label(title,24);heading.setPadding(ui.dp(12),ui.dp(8),0,ui.dp(8));bar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(1);content.setPadding(0,ui.dp(18),0,ui.dp(20));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        if("calc".equals(mode))calculator();else if("units".equals(mode))units();else if("dates".equals(mode))dates();else filePage();
        restoreFields(state);renderJob();
    }
    private TextView label(String text,int size){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(ui.text());t.setPadding(ui.dp(6),ui.dp(8),ui.dp(6),ui.dp(8));return t;}
    private void help(String s){TextView t=label(s,13);t.setTextColor(ui.text2());content.addView(t);}
    private Button button(String text,Runnable action){Button b=new Button(this);b.setText(text);b.setTextSize(15);b.setAllCaps(false);b.setTextColor(ui.accent());b.setBackgroundTintList(null);b.setStateListAnimator(null);b.setElevation(0);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ui.line()),ui.round(ui.surface(),14),null));b.setPadding(ui.dp(14),0,ui.dp(14),0);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,ui.dp(48));lp.setMargins(0,ui.dp(6),0,ui.dp(6));b.setLayoutParams(lp);b.setOnClickListener(v->action.run());return b;}
    private void action(String text,Runnable task){content.addView(button(text,()->attempt(task)));}
    private void attempt(Runnable task){try{task.run();}catch(NumberFormatException e){toast("请输入有效数字");}catch(RuntimeException e){toast(e.getMessage()==null?"输入无效":e.getMessage());}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private EditText input(String hint,String text,boolean number){EditText e=new EditText(this);e.setHint(hint);e.setText(text);e.setTextColor(ui.text());e.setHintTextColor(ui.text3());e.setTextSize(17);e.setSingleLine(true);e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2000)});if(number)e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);content.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
    private Spinner select(String title,String[] options){content.addView(label(title,13));Spinner s=new Spinner(this);adapter(s,options);content.addView(s,new LinearLayout.LayoutParams(-1,ui.dp(48)));return s;}
    private void adapter(Spinner s,String[] options){ArrayAdapter<String>a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,options){@Override public View getView(int p,View v,android.view.ViewGroup parent){TextView t=(TextView)super.getView(p,v,parent);t.setTextColor(ui.text());return t;}@Override public View getDropDownView(int p,View v,android.view.ViewGroup parent){TextView t=(TextView)super.getDropDownView(p,v,parent);t.setTextColor(ui.text());t.setBackgroundColor(ui.surface());t.setPadding(ui.dp(16),ui.dp(14),ui.dp(16),ui.dp(14));return t;}};a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);}
    private String text(EditText e){return e.getText().toString().trim();}
    private void calculator(){
        help("支持括号、+ − × ÷、^ 幂、% 百分比、pi、e。函数使用括号，例如 sin(30)、sqrt(9)、log(100)、ln(e)。");
        expression=input("输入表达式","",false);angle=select("三角函数单位",new String[]{"角度 DEG","弧度 RAD"});
        result=label("",24);result.setTextIsSelectable(true);content.addView(result);
        action("计算",()->{String q=text(expression),n=ToolMath.number(ToolMath.calculate(q,angle.getSelectedItemPosition()==0));result.setText(n);String old=getPreferences(0).getString("history","");String next=q+" = "+n+"\n"+old;String[] lines=next.split("\n");StringBuilder b=new StringBuilder();for(int i=0;i<Math.min(20,lines.length);i++)b.append(lines[i]).append('\n');getPreferences(0).edit().putString("history",b.toString()).apply();});
        String[][] keys={{"sin(","cos(","tan(","sqrt("},{"log(","ln(","abs(","^"},{"(",")","pi","%"}};
        for(String[] row:keys){LinearLayout line=new LinearLayout(this);for(String key:row)line.addView(button(key,()->{int start=Math.max(0,expression.getSelectionStart()),end=Math.max(start,expression.getSelectionEnd());expression.getText().replace(start,end,key);}),new LinearLayout.LayoutParams(0,-2,1));content.addView(line);}
        action("计算历史",()->ThemeStore.dialog(this).setTitle("最近 20 次计算").setMessage(getPreferences(0).getString("history","暂无记录")).setPositiveButton("关闭",null).setNeutralButton("清空",(d,w)->getPreferences(0).edit().remove("history").apply()).show());
    }
    private void units(){category=select("物理量",ToolMath.CATEGORIES);value=input("数值","1",true);from=select("从",ToolMath.UNITS[0]);to=select("转换为",ToolMath.UNITS[0]);to.setSelection(1);
        category.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?>p){}public void onItemSelected(android.widget.AdapterView<?>p,View v,int n,long id){adapter(from,ToolMath.UNITS[n]);adapter(to,ToolMath.UNITS[n]);to.setSelection(1);}});
        result=label("",24);result.setTextIsSelectable(true);content.addView(result);action("换算",()->result.setText(ToolMath.number(ToolMath.convert(Double.parseDouble(text(value)),category.getSelectedItemPosition(),from.getSelectedItemPosition(),to.getSelectedItemPosition()))+" "+to.getSelectedItem()));
        action("交换单位",()->{int n=from.getSelectedItemPosition();from.setSelection(to.getSelectedItemPosition());to.setSelection(n);});help("马力为机械马力 hp，卡为热化学卡 cal；温度检查绝对零度。");}
    private void dates(){String today=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());help("按日历天计算，不含起始日；同一天为 0 天。格式：YYYY-MM-DD。");dateA=input("起始日期",today,false);action("选择起始日期",()->pickDate(dateA));dateB=input("结束日期",today,false);action("选择结束日期",()->pickDate(dateB));offset=input("加减天数（负数向前）","7",true);result=label("",22);result.setTextIsSelectable(true);content.addView(result);action("计算日期差",()->result.setText("结束 − 起始："+ToolMath.days(text(dateA),text(dateB))+" 天"));action("计算起始日期加减天数",()->result.setText(ToolMath.addDays(text(dateA),Integer.parseInt(text(offset)))));}
    private void pickDate(EditText target){Calendar c=Calendar.getInstance();try{String[] d=text(target).split("-");c.set(Integer.parseInt(d[0]),Integer.parseInt(d[1])-1,Integer.parseInt(d[2]));}catch(Exception ignored){}new DatePickerDialog(this,(v,y,m,d)->target.setText(String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d)),c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();}
    private void filePage(){boolean pdf="pdf".equals(mode);
        if(pdf){help("离线处理，保留 PDF 文字。单文件 ≤40 MB，合计 ≤80 MB，最多 500 页。暂不支持加密文档。");pdfMode=select("操作",new String[]{"合并多个 PDF","提取页面为一个新 PDF"});range=input("提取页码：1,3-5（留空为全部）","",false);pdfMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int n,long id){range.setVisibility(n==1?View.VISIBLE:View.GONE);}});}
        else{help("等比例缩小，不放大原图。JPEG 透明区域填白；PNG 为无损编码，质量选项仅影响 JPEG/WebP。另存副本，不改原图。");edge=input("最长边像素 64–4096","1920",true);quality=input("质量 1–100","85",true);format=select("输出格式",new String[]{"jpg","png","webp"});}
        action(pdf?"添加 PDF 文件":"选择图片",this::pick);files=new LinearLayout(this);files.setOrientation(1);content.addView(files);renderFiles();
        process=button("处理",()->attempt(this::process));content.addView(process);status=label("",14);content.addView(status);save=button("另存结果",()->attempt(this::save));content.addView(save);if(!pdf){preview=new ImageView(this);preview.setAdjustViewBounds(true);content.addView(preview,new LinearLayout.LayoutParams(-1,ui.dp(180)));}
    }
    private void pick(){if(job.running){toast("正在处理，请稍候");return;}Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("pdf".equals(mode)?"application/pdf":"image/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,"pdf".equals(mode));startActivityForResult(i,PICK);}
    private String name(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "所选文件";}
    private void renderFiles(){if(files==null)return;files.removeAllViews();for(int i=0;i<chosen.size();i++){final int index=i;LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.addView(label((i+1)+". "+name(chosen.get(i)),14),new LinearLayout.LayoutParams(0,-2,1));if(i>0)row.addView(button("↑",()->{if(job.running)return;Collections.swap(chosen,index,index-1);renderFiles();}),new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));row.addView(button("移除",()->{if(job.running)return;chosen.remove(index);renderFiles();}),new LinearLayout.LayoutParams(ui.dp(72),ui.dp(48)));files.addView(row);}}
    private void process(){if(job.running)return;if(chosen.isEmpty())throw new IllegalArgumentException("请先选择文件");
        final ArrayList<Uri> inputs=new ArrayList<>(chosen);final boolean pdf="pdf".equals(mode),merge=pdf&&pdfMode.getSelectedItemPosition()==0;final String pages=pdf?text(range):"",ext=pdf?"pdf":format.getSelectedItem().toString();final int size=pdf?0:Integer.parseInt(text(edge)),q=pdf?0:Integer.parseInt(text(quality));
        if(job.output!=null){job.output.delete();job.output=null;}job.running=true;job.message="正在本机处理…";job.mime=pdf?"application/pdf":"image/"+(ext.equals("jpg")?"jpeg":ext);job.name="AFS-"+System.currentTimeMillis()+"."+ext;renderJob();
        final Context app=getApplicationContext();final Job current=job;
        new Thread(()->{try{current.output=pdf?ToolFiles.pdf(app,inputs,merge,pages):ToolFiles.image(app,inputs.get(0),size,q,ext);current.message="处理完成 · "+String.format(Locale.US,"%.1f KB",current.output.length()/1024d)+"，请选择另存位置";}catch(Exception|LinkageError|OutOfMemoryError e){current.message=e instanceof OutOfMemoryError?"文件过大，内存不足，请缩小后重试":"处理失败："+(e.getMessage()==null?"文件格式不支持":e.getMessage());}finally{current.running=false;current.publish();}},"afs-tool-process").start();
    }
    private void renderJob(){if(status==null)return;status.setText(job.message);process.setEnabled(!job.running);save.setEnabled(!job.running&&job.output!=null&&job.output.isFile());if(preview!=null){preview.setImageDrawable(null);if(job.output!=null){BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=8;preview.setImageBitmap(BitmapFactory.decodeFile(job.output.getPath(),o));}}}
    private void save(){if(job.output==null||!job.output.isFile())throw new IllegalArgumentException("请先处理文件");startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(job.mime).putExtra(Intent.EXTRA_TITLE,job.name),SAVE);}
    @Override protected void onActivityResult(int request,int code,Intent data){super.onActivityResult(request,code,data);if(code!=RESULT_OK||data==null)return;
        if(request==PICK){if(!"pdf".equals(mode))chosen.clear();ArrayList<Uri> added=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)added.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)added.add(data.getData());for(Uri uri:added){if(chosen.size()>=20){toast("最多选择 20 个文件");break;}try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}if(!chosen.contains(uri))chosen.add(uri);}renderFiles();}
        if(request==SAVE&&data.getData()!=null&&job.output!=null){final Uri target=data.getData();final File source=job.output;final Job current=job;final Context app=getApplicationContext();current.running=true;current.message="正在保存…";renderJob();new Thread(()->{try(InputStream in=new FileInputStream(source);OutputStream out=app.getContentResolver().openOutputStream(target,"wt")){if(out==null)throw new IOException("无法写入目标文件");ToolFiles.copy(in,out,Long.MAX_VALUE);current.message="已保存："+current.name;}catch(Exception e){current.message="保存失败，可再次另存；目标文件可能不完整";}finally{current.running=false;current.publish();}},"afs-tool-save").start();}
    }
    @Override public Object onRetainNonConfigurationInstance(){return job;}
    @Override protected void onDestroy(){destroyed=true;if(job.owner==this)job.owner=null;if(isFinishing()){job.disposed=true;if(!job.running&&job.output!=null)job.output.delete();}super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);ArrayList<String> list=new ArrayList<>();for(Uri u:chosen)list.add(u.toString());b.putStringArrayList("chosen",list);if(job.output!=null)b.putString("output",job.output.getPath());b.putString("mime",job.mime);b.putString("name",job.name);EditText[] fields={expression,value,dateA,dateB,offset,range,edge,quality};for(int i=0;i<fields.length;i++)if(fields[i]!=null)b.putString("field"+i,fields[i].getText().toString());Spinner[] selectors={angle,category,from,to,pdfMode,format};for(int i=0;i<selectors.length;i++)if(selectors[i]!=null)b.putInt("select"+i,selectors[i].getSelectedItemPosition());if(result!=null)b.putString("result",result.getText().toString());}
    private void restoreFields(Bundle b){if(b==null)return;EditText[] fields={expression,value,dateA,dateB,offset,range,edge,quality};for(int i=0;i<fields.length;i++)if(fields[i]!=null&&b.containsKey("field"+i))fields[i].setText(b.getString("field"+i));Spinner[] selectors={angle,category,from,to,pdfMode,format};for(int i=0;i<selectors.length;i++)if(selectors[i]!=null)selectors[i].setSelection(b.getInt("select"+i,0));if(category!=null)category.post(()->{from.setSelection(b.getInt("select2",0));to.setSelection(b.getInt("select3",1));});if(result!=null)result.setText(b.getString("result",""));}
}
