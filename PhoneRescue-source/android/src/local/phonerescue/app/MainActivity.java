package local.phonerescue.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import android.text.InputType;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

public class MainActivity extends Activity {
  final ExecutorService io=Executors.newSingleThreadExecutor();
  final Handler ui=new Handler(Looper.getMainLooper());
  EditText pair,command,cwd,seconds;
  EditText goal,maxRounds;
  CheckBox diagnosticOnly;
  TextView automationStatus,automationReport;
  Button autoStart;
  TextView status,result;
  TextView fileStatus;
  LinearLayout fileList;
  LinearLayout[] pages=new LinearLayout[4];
  Button[] tabs=new Button[4];
  String workspace="",downloadPath="",downloadName="";
  volatile boolean transferring=false;
  Button run;
  String endpoint="",token="",job="",lastResult="";
  boolean stopped=false,polling=false;
  final int ink=Color.rgb(226,234,245), muted=Color.rgb(150,168,190), teal=Color.rgb(80,220,190);
  interface Done {void accept(JSONObject value) throws Exception;}
  @Override public void onCreate(Bundle b){
    super.onCreate(b);
    getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(11,18,30));
    LinearLayout container=new LinearLayout(this);container.setOrientation(1);container.setPadding(dp(20),dp(42),dp(20),dp(40));scroll.addView(container);setContentView(scroll);
    scroll.setOnApplyWindowInsetsListener((v,insets)->{container.setPadding(dp(20),insets.getSystemWindowInsetTop()+dp(20),dp(20),insets.getSystemWindowInsetBottom()+dp(20));return insets;});
    title(container,"电脑急救桥",28,ink);title(container,"PHONE RESCUE  /  0.3",12,teal);
    title(container,"让手机上的 ChatGPT 使用你的电脑",15,muted);
    status=title(container,"连接 USB，输入电脑上的四位码即可开始",14,teal);
    LinearLayout nav=new LinearLayout(this);container.addView(nav);
    String[] labels={"任务","文件","连接","高级"};
    for(int i=0;i<4;i++){final int index=i;tabs[i]=new Button(this);tabs[i].setText(labels[i]);tabs[i].setTextSize(13);tabs[i].setTextColor(teal);tabs[i].setOnClickListener(v->showPage(index));nav.addView(tabs[i],new LinearLayout.LayoutParams(0,dp(48),1));pages[i]=new LinearLayout(this);pages[i].setOrientation(1);container.addView(pages[i]);}
    LinearLayout body=pages[2];
    title(body,"连接你的电脑",21,ink);
    title(body,"USB 模式只需四位数字。电脑运行 connect-usb.cmd 后，也会自动填入并连接。",14,muted);
    pair=input(body,"四位配对码，例如 0428",false);
    pair.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    pair.setText(getPreferences(0).getString("pair",""));
    button(body,"配对 / 重新连接",()->connect());
    title(body,"高级：局域网仍可填写完整 http://电脑IP:18765/#凭证 地址。",13,muted);
    button(body,"开启无障碍服务",()->startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    body=pages[0];
    title(body,"交给电脑完成",21,ink);
    title(body,"沿用官方 ChatGPT App，无需 API Key。请先在 ChatGPT 新建一个对话，并保持手机解锁。诊断结果会自动发入该对话。",14,muted);
    button(body,"打开 ChatGPT（先新建对话）",()->{Intent i=getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");if(i!=null)startActivity(i);else Toast.makeText(this,"未安装 ChatGPT",0).show();});
    goal=input(body,"描述要诊断或维修的问题",true);goal.setMinLines(2);goal.setText(getPreferences(0).getString("goal","检查电脑的网络连接和 agent 工具环境，说明发现的问题。"));
    button(body,"模板 · 编译并运行",()->{goal.setText("查看工作区 inbox 中我上传的源码，检查本机已有编译器，编译并运行程序，检查运行结果。不要联网安装依赖；若缺少依赖请说明。将构建产物与运行报告保存到 outbox，最后列出可下载文件。");diagnosticOnly.setChecked(false);});
    button(body,"模板 · 转换文件",()->{goal.setText("查看工作区 inbox 中我上传的文件，使用电脑已有工具转换格式。目标格式：请在此填写。保留原文件，将转换后的文件保存到 outbox，并验证输出文件可以读取。最后列出可下载文件。");diagnosticOnly.setChecked(false);});
    maxRounds=input(body,"最多自动轮数（1–20）",false);maxRounds.setInputType(InputType.TYPE_CLASS_NUMBER);maxRounds.setText("8");
    diagnosticOnly=new CheckBox(this);diagnosticOnly.setText("只读诊断模式（禁止模型自定义命令）");diagnosticOnly.setTextColor(ink);diagnosticOnly.setChecked(true);body.addView(diagnosticOnly);
    autoStart=button(body,"开始自动循环",()->startAutomation());
    button(body,"停止自动循环",()->{RepairService s=RepairService.instance;if(s!=null)s.stop("用户停止自动维修");});
    automationStatus=title(body,"检查无障碍服务状态…",14,teal);
    automationReport=title(body,"",13,muted);automationReport.setMaxLines(12);automationReport.setTextIsSelectable(true);
    button(body,"复制自动维修记录",()->{RepairService s=RepairService.instance;String report=s==null?getSharedPreferences("automation",0).getString("report",""):s.report();((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("自动维修记录",report));Toast.makeText(this,"已复制",0).show();});
    body=pages[1];
    title(body,"文件往返",21,ink);
    title(body,"上传到 inbox → 任务加工 → outbox 保存回手机。单个文件最多 100 MB；不需要云盘。",14,muted);
    button(body,"选择手机文件并上传",()->{if(endpoint.isEmpty()){status.setText("请先连接电脑");showPage(2);return;}Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(pick,71);});
    button(body,"刷新电脑文件",()->refreshFiles());
    fileStatus=title(body,"连接后可查看上传文件和任务产物",14,muted);
    fileList=new LinearLayout(this);fileList.setOrientation(1);body.addView(fileList);
    body=pages[3];
    title(body,"手动命令与结果",21,ink);
    title(body,"从 ChatGPT / DeepSeek 复制 PowerShell 命令或约定的 JSON，也可分享到本应用。导入后不会自动执行。",14,muted);
    button(body,"从剪贴板导入",()->{ClipboardManager cb=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(cb.hasPrimaryClip())importText(cb.getPrimaryClip().getItemAt(0).coerceToText(this).toString());});
    command=input(body,"PowerShell 命令",true);command.setMinLines(5);command.setTypeface(android.graphics.Typeface.MONOSPACE);
    cwd=input(body,"电脑工作目录",false);cwd.setText("");
    seconds=input(body,"超时（1–300 秒）",false);seconds.setInputType(InputType.TYPE_CLASS_NUMBER);seconds.setText("60");
    button(body,"填入只读网络诊断",()->command.setText("Get-NetIPConfiguration | Format-List; Get-DnsClientServerAddress | Format-Table -AutoSize; netsh winhttp show proxy"));
    run=button(body,"检查并执行",()->confirm());
    button(body,"取消当前操作",()->{if(!job.isEmpty())request("POST","/api/jobs/"+job+"/cancel",new JSONObject(),v->status.setText("已请求取消"));});
    button(body,"结束旧请求追踪",()->{if(autoRunning()){Toast.makeText(this,"请先停止自动循环",0).show();return;}new AlertDialog.Builder(this).setTitle("确认已检查电脑状态？").setMessage("这不会取消或撤销命令。仅在服务器重启、请求被拒绝，或已确认执行结果后清除追踪，避免重复修改电脑。") .setNegativeButton("保留追踪",null).setPositiveButton("清除追踪",(d,w)->{job="";getPreferences(0).edit().remove("job").apply();run.setEnabled(true);}).show();});
    title(body,"04  手动执行结果",19,ink);
    result=title(body,"连接电脑后即可开始。",14,ink);result.setTypeface(android.graphics.Typeface.MONOSPACE);result.setTextIsSelectable(true);
    button(body,"复制结果给模型",()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("维修结果",lastResult));Toast.makeText(this,"已复制",0).show();});
    button(body,"复制模型操作说明",()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("模型操作说明",prompt()));Toast.makeText(this,"已复制，请发给模型",0).show();});
    title(body,"自动模式会读取并操作 ChatGPT 当前对话界面，不读取登录凭证。关闭只读模式后，自定义命令仍需逐次确认。悬浮条可随时停止；聊天界面变化时可能需要手动辅助发送。",12,muted);
    if(Intent.ACTION_SEND.equals(getIntent().getAction())){String text=getIntent().getStringExtra(Intent.EXTRA_TEXT);if(text!=null)importText(text);}
    showPage(2);
    String pairing=getIntent().getStringExtra("pairing");if(pairing!=null)setPairing(pairing);
    RepairService active=RepairService.instance;
    if(active!=null&&active.isRunning()){endpoint=active.base;token=active.key;cwd.setText(active.directory);status.setText("已连接 · 自动任务运行中");showPage(0);}
    else if(!pair.getText().toString().trim().isEmpty())connect();
    if(b!=null){downloadPath=b.getString("downloadPath","");downloadName=b.getString("downloadName","");}
  }
  void showPage(int index){for(int i=0;i<4;i++){pages[i].setVisibility(i==index?View.VISIBLE:View.GONE);tabs[i].setAlpha(i==index?1f:.55f);}}
  void setPairing(String value){try{URI u=new URI(value);pair.setText("127.0.0.1".equals(u.getHost())&&u.getFragment()!=null&&u.getFragment().matches("[0-9]{4}")?u.getFragment():value);}catch(Exception e){pair.setText(value);}}
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);String p=intent.getStringExtra("pairing");if(p!=null&&!autoRunning()){setPairing(p);connect();}}
  @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("downloadPath",downloadPath);b.putString("downloadName",downloadName);}
  TextView title(LinearLayout b,String t,int size,int color){TextView v=new TextView(this);v.setText(t);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,14,0,10);b.addView(v);return v;}
  int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
  EditText input(LinearLayout b,String hint,boolean multi){EditText v=new EditText(this);v.setHint(hint);v.setTextColor(ink);v.setHintTextColor(muted);v.setTextSize(15);v.setSingleLine(!multi);v.setInputType(InputType.TYPE_CLASS_TEXT|(multi?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0)|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);v.setPadding(12,14,12,14);b.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;}
  Button button(LinearLayout b,String t,Runnable f){Button v=new Button(this);v.setText(t);v.setTextColor(teal);v.setAllCaps(false);v.setTextSize(14);GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(23,40,53));bg.setCornerRadius(dp(12));v.setBackground(bg);v.setOnClickListener(w->f.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.topMargin=dp(8);p.bottomMargin=dp(4);b.addView(v,p);return v;}
  void connect(){try{
    if(autoRunning())throw new Exception("自动任务运行中，请先停止再切换连接");
    String address=pair.getText().toString().trim();if(address.matches("[0-9]{4}"))address="http://127.0.0.1:18765/#"+address;
    URI u=new URI(address);String h=u.getHost();
    if(!"http".equals(u.getScheme())||h==null||u.getUserInfo()!=null||u.getPort()!=18765||u.getRawFragment()==null)throw new Exception("请粘贴完整的 http://地址:18765/#密码");
    boolean local=h.equals("127.0.0.1")||h.startsWith("10.")||h.startsWith("192.168.")||h.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..+");
    if(!local||!h.matches("[0-9.]+"))throw new Exception("只允许本机或私有局域网 IPv4 地址");
    endpoint="http://"+h+":18765";token=u.getFragment();
    request("GET","/api/status",null,v->{getPreferences(0).edit().putString("pair",pair.getText().toString().trim()).apply();status.setText("已连接："+v.getString("machine")+" · "+v.getString("mode"));workspace=v.optString("workspace","");if(!workspace.isEmpty())cwd.setText(workspace);job=getPreferences(0).getString("job","");if(!job.isEmpty())poll();showPage(0);refreshFiles();});
  }catch(Exception e){error(e);}}
  void importText(String text){try{String s=text.trim();if(s.startsWith("```")){int n=s.indexOf('\n');int end=s.lastIndexOf("```");if(n>=0&&end>n)s=s.substring(n+1,end).trim();}if(s.startsWith("{")){JSONObject j=new JSONObject(s);command.setText(j.getString("command"));cwd.setText(j.optString("workingDirectory",cwd.getText().toString()));seconds.setText(""+j.optInt("timeoutSeconds",60));}else command.setText(s);}catch(Exception e){error(e);}}
  void refreshFiles(){
    if(endpoint.isEmpty()){fileStatus.setText("请先连接电脑");return;}
    request("GET","/api/files",null,v->{fileList.removeAllViews();JSONArray files=v.getJSONArray("files");fileStatus.setText("共 "+files.length()+" 个文件"+(v.optBoolean("truncated")?"（仅展示前 200 个）":"")+"\n"+v.getString("workspace"));
      for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);String path=f.getString("path"),name=f.getString("name");
        title(fileList,(path.startsWith("outbox/")?"产物 · ":"已上传 · ")+path+"\n"+String.format(java.util.Locale.ROOT,"%.1f KB",f.getLong("bytes")/1024.0),14,ink);
        button(fileList,"保存到手机 · "+name,()->{if(transferring){fileStatus.setText("请等待当前传输完成");return;}downloadPath=path;downloadName=name;Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(save,72);});
      }
    });
  }
  @Override protected void onActivityResult(int request,int resultCode,Intent data){super.onActivityResult(request,resultCode,data);if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
    if(request==71)transfer(data.getData(),true);else if(request==72)transfer(data.getData(),false);
  }
  void transfer(Uri uri,boolean upload){
    if(transferring){fileStatus.setText("请等待当前传输完成");return;}if(endpoint.isEmpty()){fileStatus.setText("请先重新连接电脑");return;}
    final String base=endpoint,key=token,relative=downloadPath;
    transferring=true;fileStatus.setText(upload?"正在上传到电脑…":"正在保存到手机…");
    io.execute(()->{HttpURLConnection c=null;try{
      String name="phone-file";long size=-1;
      if(upload)try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){name=cursor.getString(0);if(!cursor.isNull(1))size=cursor.getLong(1);}}
      if(size>100L*1024*1024)throw new IOException("单个文件上限为 100 MB");
      String path=upload?"/api/files/upload?name="+URLEncoder.encode(name,"UTF-8"):"/api/files/download?path="+URLEncoder.encode(relative,"UTF-8");
      c=(HttpURLConnection)new URL(base+path).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(false);c.setRequestProperty("X-Rescue-Token",key);
      if(upload){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/octet-stream");if(size>=0)c.setFixedLengthStreamingMode(size);else c.setChunkedStreamingMode(65536);
        try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=c.getOutputStream()){copyFile(in,out);}
        int code=c.getResponseCode();if(code!=201)throw new IOException("上传失败 HTTP "+code);
        try(InputStream in=c.getInputStream()){byte[] buffer=new byte[4096];while(in.read(buffer)!=-1){}}
      }else{if(c.getResponseCode()!=200)throw new IOException("下载失败 HTTP "+c.getResponseCode());if(c.getContentLengthLong()>100L*1024*1024)throw new IOException("文件超过 100 MB");
        try(InputStream in=c.getInputStream();OutputStream out=getContentResolver().openOutputStream(uri,"wt")){copyFile(in,out);}
      }
      ui.post(()->{fileStatus.setText(upload?"上传完成，可以在任务页描述加工要求":"已保存到你选择的位置");if(upload)refreshFiles();});
    }catch(Exception e){ui.post(()->fileStatus.setText("传输未完成："+e.getMessage()+(!upload?"；目标文件可能不完整，请重新保存":"")));}
    finally{if(c!=null)c.disconnect();transferring=false;}});
  }
  void copyFile(InputStream in,OutputStream out)throws Exception{if(in==null||out==null)throw new IOException("无法打开所选文件");byte[] buffer=new byte[65536];int n;long total=0,last=0;while((n=in.read(buffer))!=-1){total+=n;if(total>100L*1024*1024)throw new IOException("文件超过 100 MB");out.write(buffer,0,n);if(total-last>=1024*1024){last=total;final long count=total;ui.post(()->fileStatus.setText("已传输 "+count/(1024*1024)+" MB…"));}}}
  void confirm(){try{
    if(autoRunning())throw new Exception("请先停止自动循环，再执行手动命令");
    if(!job.isEmpty())throw new Exception("已有未确认结果的请求，请重新连接查询");
    if(endpoint.isEmpty())throw new Exception("请先配对电脑");
    String cmd=command.getText().toString();int time=Integer.parseInt(seconds.getText().toString());if(cmd.trim().isEmpty()||time<1||time>300)throw new Exception("检查命令和超时设置");
    final JSONObject req=new JSONObject().put("id",UUID.randomUUID().toString()).put("command",cmd).put("workingDirectory",cwd.getText().toString()).put("timeoutSeconds",time);
    new AlertDialog.Builder(this).setTitle("在电脑执行此命令？").setMessage(cmd+"\n\n目录："+cwd.getText()+"\n可能修改电脑文件或设置。") .setNegativeButton("返回检查",null).setPositiveButton("执行",(d,w)->{
      try{job=req.getString("id");getPreferences(0).edit().putString("job",job).apply();run.setEnabled(false);request("POST","/api/jobs",req,v->poll());}catch(Exception e){error(e);}
    }).show();
  }catch(Exception e){error(e);}}
  void poll(){if(polling||stopped||job.isEmpty())return;polling=true;request("GET","/api/jobs/"+job,null,v->{polling=false;lastResult=v.toString(2);result.setText(lastResult);String state=v.getString("state");status.setText("操作状态："+state);if("running".equals(state)){run.setEnabled(false);ui.postDelayed(()->poll(),1000);}else{job="";run.setEnabled(true);getPreferences(0).edit().remove("job").apply();}});}
  void request(String method,String path,JSONObject data,Done done){final String base=endpoint,key=token;io.execute(()->{HttpURLConnection c=null;try{
    c=(HttpURLConnection)new URL(base+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(6000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);c.setRequestProperty("X-Rescue-Token",key);
    if(data!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(data.toString().getBytes(StandardCharsets.UTF_8));}}
    int code=c.getResponseCode();InputStream input=code<400?c.getInputStream():c.getErrorStream();ByteArrayOutputStream out=new ByteArrayOutputStream();if(input!=null){try(InputStream in=input){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>1100000)throw new IOException("结果过大");}}}
    String raw=new String(out.toByteArray(),StandardCharsets.UTF_8);if(code>=300)throw new IOException("HTTP "+code+" "+raw+(code==401?"：请更新配对地址":""));JSONObject v=raw.isEmpty()?new JSONObject():new JSONObject(raw);
    ui.post(()->{if(!isFinishing())try{done.accept(v);}catch(Exception e){error(e);}});
  }catch(Exception e){ui.post(()->error(e));}finally{if(c!=null)c.disconnect();}});}
  void error(Exception e){polling=false;run.setEnabled(job.isEmpty());status.setText("未完成："+e.getMessage()+"\n若提交后断线，点击重新连接查询原请求；不要重复执行。");}
  String prompt(){return "你通过电脑急救桥协助维修 Windows。你不能直接执行命令。每轮先说明目的，再单独给一个 JSON 代码块，包含 command（PowerShell）、workingDirectory（Windows 绝对路径）、timeoutSeconds（1–300）。先只读诊断，每次一步，等待我回传结果。不要要求我提供密码、token 或完整环境变量。修改前说明影响和恢复方法。不要使用交互式输入或启动后台任务。示例：{\"command\":\"Get-NetIPConfiguration | Format-List\",\"workingDirectory\":\"C:\\\\Users\\\\Public\",\"timeoutSeconds\":60}";}
  boolean autoRunning(){return RepairService.instance!=null&&RepairService.instance.isRunning();}
  void startAutomation(){try{
    RepairService service=RepairService.instance;if(service==null)throw new Exception("请先在系统无障碍设置开启电脑急救桥服务");
    if(endpoint.isEmpty())throw new Exception("请先点击配对 / 重新连接");
    if(!job.isEmpty()||!getPreferences(0).getString("job","").isEmpty())throw new Exception("请先查询旧请求结果，或在核实电脑状态后结束旧请求追踪");
    int max=Integer.parseInt(maxRounds.getText().toString());String text=goal.getText().toString().trim();if(text.isEmpty()||max<1||max>20)throw new Exception("请填写目标，轮数需为 1–20");
    new AlertDialog.Builder(this).setTitle("开始 ChatGPT 自动循环？").setMessage("程序会向当前 ChatGPT 对话发送你的目标和电脑诊断结果。请先新建对话并清空输入草稿。\n\n预设诊断自动执行；"+(diagnosticOnly.isChecked()?"本次禁止模型自定义命令。":"自定义 PowerShell 命令逐次确认。")+"\n最多 "+max+" 轮 / 15 分钟。运行中保持 ChatGPT 前台，浮窗可停止。")
      .setNegativeButton("返回",null).setPositiveButton("开始",(d,w)->{try{getPreferences(0).edit().putString("goal",text).apply();service.begin("com.openai.chatgpt",endpoint,token,text,cwd.getText().toString(),max,diagnosticOnly.isChecked());}catch(Exception e){error(e);}}).show();
  }catch(Exception e){error(e);}}
  final Runnable refreshAutomation=new Runnable(){public void run(){if(stopped)return;RepairService s=RepairService.instance;automationStatus.setText(s==null?"无障碍服务未开启":s.isRunning()?s.state:"无障碍服务已就绪 · "+s.state);autoStart.setEnabled(s!=null&&!s.isRunning());String report=s==null?getSharedPreferences("automation",0).getString("report",""):s.report();automationReport.setText(report.length()>3000?report.substring(report.length()-3000):report);ui.postDelayed(this,1200);}};
  @Override protected void onResume(){super.onResume();ui.removeCallbacks(refreshAutomation);ui.post(refreshAutomation);}
  @Override protected void onPause(){ui.removeCallbacks(refreshAutomation);super.onPause();}
  @Override protected void onDestroy(){stopped=true;ui.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
}
