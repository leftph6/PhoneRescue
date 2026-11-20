package local.phonerescue.app;

import android.accessibilityservice.AccessibilityService;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** User-started, foreground-chat automation. No accessibility text is read while idle. */
public class RepairService extends AccessibilityService {
  public static volatile RepairService instance;
  final Handler ui=new Handler(Looper.getMainLooper());
  final ExecutorService net=Executors.newSingleThreadExecutor();
  final StringBuilder history=new StringBuilder();
  volatile boolean running;
  String target="com.openai.chatgpt",base="",key="",task="",directory="",phase="idle",nonce="",outgoing="",pending="",state="未启动",lastReply="",lastOperation="";
  int epoch=0,round=0,limit=8,repeat=0,repairs=0;
  long started,phaseStarted,replyChanged;
  boolean submitted=false,needsVerification=false,diagnosticOnly=true;
  String workspaceContext="";
  View overlay;TextView overlayText;Button overlayStop;
  AlertDialog approval;
  interface Done {void accept(JSONObject value)throws Exception;}
  @Override protected void onServiceConnected(){instance=this;}
  @Override public void onAccessibilityEvent(AccessibilityEvent e) { /* polling only during an explicitly started session */ }
  @Override public void onInterrupt(){if(running)stop("系统中断了无障碍服务");}
  public boolean isRunning(){return running;}
  android.content.SharedPreferences mainPrefs(){return getSharedPreferences("MainActivity",0);}
  public String report(){return history.length()>0?history.toString():getSharedPreferences("automation",0).getString("report","尚未运行自动维修");}
  void log(String message){
    message=RepairProtocol.scrub(message,key);history.append(message).append("\n\n");
    if(history.length()>60000)history.delete(0,history.length()-60000);
    getSharedPreferences("automation",0).edit().putString("report",history.toString()).apply();
  }
  void status(String message){state=message;if(overlayText!=null)overlayText.setText("电脑急救桥 · "+round+"/"+limit+"\n"+message);}
  void change(String next,String message){phase=next;phaseStarted=System.currentTimeMillis();status(message);}
  public void begin(String app,String endpoint,String token,String goal,String cwd,int max,boolean readOnly)throws Exception{
    if(running)throw new Exception("已有自动维修运行中");
    if(!app.equals("com.openai.chatgpt")&&!app.equals("com.deepseek.chat"))throw new Exception("不支持的聊天 App");
    if(!mainPrefs().getString("job","").isEmpty())throw new Exception("有尚未确认结果的命令，请先重新配对查询结果");
    Intent launch=getPackageManager().getLaunchIntentForPackage(app);if(launch==null)throw new Exception("未找到所选官方聊天 App");
    URI uri=new URI(endpoint);if(!"http".equals(uri.getScheme())||uri.getPort()!=18765||uri.getUserInfo()!=null)throw new Exception("电脑连接地址无效");
    if(goal.trim().isEmpty()||goal.length()>2000||max<1||max>20)throw new Exception("填写问题描述，轮数需为 1–20");
    target=app;base=endpoint;key=token;task=goal;directory=cwd;limit=max;round=0;repairs=0;repeat=0;lastOperation="";needsVerification=false;diagnosticOnly=readOnly;
    pending="";history.setLength(0);epoch++;running=true;started=System.currentTimeMillis();
    log("开始自动维修 · "+(app.equals("com.openai.chatgpt")?"ChatGPT":"DeepSeek")+"\n目标："+goal);
    showOverlay();change("checking","检查电脑连接…");final int run=epoch;
    request("GET","/api/status",null,v->{if(!valid(run))return;workspaceContext="电脑工作区："+v.optString("workspace",directory)+"；上传原件目录："+v.optString("inbox")+"；可下载产物目录："+v.optString("outbox");launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(launch);next("尚未执行检查。电脑工作目录："+directory);},e->{if(valid(run))fail(e);});
    ui.post(ticker);
  }
  boolean valid(int run){return running&&epoch==run;}
  void next(String previous)throws Exception{
    if(!running)return;
    if(round>=limit){finish("达到轮数上限。请查看已执行结果；这不表示问题已修复。");return;}
    round++;nonce=UUID.randomUUID().toString().replace("-","");outgoing=RepairProtocol.prompt(nonce,(diagnosticOnly?"本次仅诊断，禁止 shell，仅使用 diagnose、files 或 done。":"")+task+"\n"+workspaceContext,previous,round,limit);
    submitted=false;lastReply="";replyChanged=0;
    change("fill","准备向聊天 App 发送第 "+round+" 轮请求");
  }
  final Runnable ticker=new Runnable(){public void run(){if(!running)return;try{tick();}catch(Exception e){fail(e);}if(running)ui.postDelayed(this,900);}};
  void tick()throws Exception{
    long now=System.currentTimeMillis();if(now-started>15*60*1000){stop("达到 15 分钟会话上限");return;}
    if(phase.equals("checking")||phase.equals("job")||phase.equals("approval"))return;
    if(now-phaseStarted>180000){stop("等待聊天界面或模型回复超时。未自动重发，避免重复提交。");return;}
    AccessibilityNodeInfo root=getRootInActiveWindow();
    if(root==null){status("等待聊天界面，请保持手机解锁");return;}
    if(!target.contentEquals(root.getPackageName()==null?"":root.getPackageName())){root.recycle();status("请保持所选聊天 App 在前台；当前暂不操作");return;}
    ArrayList<AccessibilityNodeInfo> nodes=new ArrayList<>();collect(root,nodes);
    try{
      AccessibilityNodeInfo editor=null,send=null;boolean generating=false;
      StringBuilder text=new StringBuilder();Set<String> seen=new HashSet<>();
      for(AccessibilityNodeInfo n:nodes){
        if(n.isEditable()&&n.isVisibleToUser()&&n.isEnabled()&&!n.isPassword())editor=n;
        String label=(n.getContentDescription()==null?"":n.getContentDescription().toString()).trim();
        String id=n.getViewIdResourceName()==null?"":n.getViewIdResourceName().toLowerCase(Locale.ROOT);
        String value=n.getText()==null?"":n.getText().toString();
        if(!n.isEditable()&&!value.isEmpty()&&seen.add(value)&&text.length()<100000)text.append(value).append('\n');
        String semantic=(label.isEmpty()?value:label).trim().toLowerCase(Locale.ROOT);
        if(n.isVisibleToUser()&&(semantic.equals("stop generating")||semantic.equals("stop streaming")||semantic.equals("停止生成")||semantic.equals("停止回答")||semantic.equals("停止响应")||id.endsWith("/stop_button")))generating=true;
        if(n.isVisibleToUser()&&n.isEnabled()&&(semantic.equals("send")||semantic.equals("send message")||semantic.equals("send prompt")||semantic.equals("发送")||semantic.equals("发送消息")||id.endsWith("/send_button")||id.endsWith("/send_message_button")))send=n;
      }
      if(phase.equals("fill")){
        if(editor==null){status("未找到聊天输入框。请打开一个新对话。");return;}
        if(generating){status("等待当前回复结束");return;}
        String draft=editor.getText()==null?"":editor.getText().toString();
        if(!draft.trim().isEmpty()&&!isPlaceholder(editor,draft)&&!draft.equals(outgoing)){stop("聊天输入框中有未发送草稿。已停止，未覆盖草稿。");return;}
        Bundle a=new Bundle();a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,outgoing);
        if(!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,a)){stop("聊天 App 不允许自动填写输入框，请保留手动模式。");return;}
        change("send","请求已填写，正在查找发送按钮");return;
      }
      if(phase.equals("send")){
        String draft=editor==null||editor.getText()==null?"":editor.getText().toString();
        if((draft.isEmpty()||isPlaceholder(editor,draft))&&(submitted||text.indexOf(nonce)>=0||generating)){change("reply","等待模型返回操作…");return;}
        if(!draft.equals(outgoing)){status("等待输入框更新；不会点击其他内容");return;}
        if(!submitted&&send!=null){if(click(send)){submitted=true;status("请求已发送，等待模型…");}return;}
        status(submitted?"等待发送完成…":"未识别发送按钮，可手动点一次发送；随后自动接管");return;
      }
      if(phase.equals("reply")){
        if(generating){status("模型正在回复…");return;}
        JSONObject action=RepairProtocol.parse(text.toString(),nonce);
        if(action==null){status("等待本轮完整操作标记；请保持最新回复可见");return;}
        String candidate=action.toString();if(!candidate.equals(lastReply)){lastReply=candidate;replyChanged=now;return;}
        if(now-replyChanged<2200)return;
        handle(action);
      }
    }finally{for(AccessibilityNodeInfo n:nodes)n.recycle();}
  }
  boolean isPlaceholder(AccessibilityNodeInfo n,String draft){return n!=null&&n.isShowingHintText();}
  // Compose can nest the actual editor more than 45 levels deep. Bound total
  // nodes instead of depth, and avoid recursive traversal of provider trees.
  void collect(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> nodes){
    ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();queue.add(root);
    while(!queue.isEmpty()){
      AccessibilityNodeInfo n=queue.removeFirst();nodes.add(n);
      for(int i=0;i<n.getChildCount()&&nodes.size()+queue.size()<5000;i++){
        AccessibilityNodeInfo child=n.getChild(i);if(child!=null)queue.addLast(child);
      }
    }
  }
  boolean click(AccessibilityNodeInfo n){AccessibilityNodeInfo current=n;for(int i=0;i<4&&current!=null;i++){
    if(current.isClickable()&&current.performAction(AccessibilityNodeInfo.ACTION_CLICK)){if(current!=n)current.recycle();return true;}
    AccessibilityNodeInfo parent=current.getParent();if(current!=n)current.recycle();current=parent;
  }if(current!=null&&current!=n)current.recycle();return false;}
  void handle(JSONObject action)throws Exception{
    String kind=action.getString("kind");
    if(kind.equals("files")){
      change("job","读取电脑工作区文件清单…");final int run=epoch;
      request("GET","/api/files",null,v->{if(!valid(run))return;JSONArray all=v.getJSONArray("files"),selected=new JSONArray();int chars=0;for(int i=0;i<all.length();i++){JSONObject f=all.getJSONObject(i);chars+=f.toString().length();if(chars>16000){v.put("truncated",true);break;}selected.put(f);}v.put("files",selected);log("第 "+round+" 轮 · 工作区文件清单："+v.toString());needsVerification=false;next("工作区文件清单（仅验证存在性，内容需另行检查）："+v.toString());},e->{if(valid(run))fail(e);});return;
    }
    if(kind.equals("done")){
      if(needsVerification){next("上一项操作后尚未核对结果。维修请使用 diagnose，文件/编译任务请先通过 shell 验证内容或运行结果，再用 files 核对产物。不得把文件存在当成内容正确。");return;}
      finish("模型总结："+action.getString("summary")+"\n已执行用户确认操作："+repairs+" 项。");return;
    }
    String signature=kind.equals("diagnose")?"diagnose:"+action.getString("check"):"shell:"+action.getString("command");
    repeat=signature.equals(lastOperation)?repeat+1:0;lastOperation=signature;
    if(repeat>=2){stop("模型连续重复同一操作，已停止循环");return;}
    if(kind.equals("diagnose")){
      String check=action.getString("check");log("第 "+round+" 轮 · 自动只读检查："+check+"\n"+action.optString("reason"));
      execute(RepairProtocol.CHECKS.get(check),directory,45,false);return;
    }
    if(diagnosticOnly){log("只读模式已拒绝模型自定义命令");next("只读模式不允许 shell。请从预设 diagnose 中选择，或给出 done 总结。");return;}
    change("approval","等待确认模型提出的 PowerShell 操作");
    String command=action.getString("command"),cwd=action.getString("workingDirectory");int timeout=action.getInt("timeoutSeconds");
    log("第 "+round+" 轮 · 待确认："+action.optString("reason")+"\n"+command);
    final int run=epoch;
    TextView message=new TextView(this);message.setText(action.optString("reason")+"\n\n目录："+cwd+"\n超时："+timeout+" 秒\n\n"+command);message.setTextIsSelectable(true);message.setPadding(24,16,24,16);
    ScrollView scroll=new ScrollView(this);scroll.addView(message);
    approval=new AlertDialog.Builder(this).setTitle("模型请求在电脑执行").setView(scroll).setCancelable(false)
      .setPositiveButton("执行这一步",(d,w)->{approval=null;if(valid(run))try{execute(command,cwd,timeout,true);}catch(Exception e){fail(e);}})
      .setNegativeButton("拒绝这一步",(d,w)->{approval=null;if(valid(run))try{log("用户拒绝此操作");next("用户拒绝上一步 shell 操作。不要再次请求执行同一命令，请用只读方式继续或说明限制。");}catch(Exception e){fail(e);}}).create();
    approval.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);approval.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);approval.show();
  }
  void execute(String command,String cwd,int timeout,boolean repair)throws Exception{
    if(!running)return;String id=UUID.randomUUID().toString();pending=id;mainPrefs().edit().putString("job",id).commit();
    JSONObject req=new JSONObject().put("id",id).put("command",command).put("workingDirectory",cwd).put("timeoutSeconds",timeout);
    log("执行请求 "+id+"\n"+command);change("job","电脑正在执行，结果将自动回传");final int run=epoch;
    request("POST","/api/jobs",req,v->{if(valid(run))pollJob(id,repair,run);},e->{if(valid(run))stop("命令提交结果不确定，已暂停。请回急救桥重新连接查询原请求，勿重复执行。"+e.getMessage());});
  }
  void pollJob(String id,boolean repair,int run){
    if(!valid(run))return;
    request("GET","/api/jobs/"+id,null,v->{
      if(!valid(run))return;
      if(v.getString("state").equals("running")){ui.postDelayed(()->pollJob(id,repair,run),1000);return;}
      pending="";mainPrefs().edit().remove("job").apply();
      String result=RepairProtocol.resultForModel(v,key);log("电脑结果："+result);
      if(repair){repairs++;needsVerification=true;}else if(v.optInt("exitCode",-1)==0&&v.optString("state").equals("completed"))needsVerification=false;
      next("实际执行结果："+result);
    },e->{if(valid(run))stop("查询电脑结果失败，已暂停。请回急救桥重新连接查询原请求。"+e.getMessage());});
  }
  interface Failed{void accept(Exception error);}
  void request(String method,String path,JSONObject body,Done done,Failed failed){final String endpoint=base,token=key;net.execute(()->{
    HttpURLConnection c=null;try{
      c=(HttpURLConnection)new URL(endpoint+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);c.setRequestProperty("X-Rescue-Token",token);
      if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
      int code=c.getResponseCode();InputStream stream=code<400?c.getInputStream():c.getErrorStream();ByteArrayOutputStream output=new ByteArrayOutputStream();
      if(stream!=null)try(InputStream in=stream){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){output.write(b,0,n);if(output.size()>1100000)throw new IOException("返回数据过大");}}
      String raw=new String(output.toByteArray(),StandardCharsets.UTF_8);if(code>=300)throw new IOException("HTTP "+code+(code==401?"：请重新配对":""));
      JSONObject value=raw.isEmpty()?new JSONObject():new JSONObject(raw);ui.post(()->{try{done.accept(value);}catch(Exception e){failed.accept(e);}});
    }catch(Exception e){ui.post(()->failed.accept(e));}finally{if(c!=null)c.disconnect();}
  });}
  void fail(Exception error){stop("自动维修暂停："+error.getMessage());}
  void finish(String summary){if(!running)return;running=false;epoch++;ui.removeCallbacks(ticker);change("idle",summary);log(summary);closeApproval();if(overlayStop!=null)overlayStop.setText("关闭");}
  public void stop(String why){
    if(!running){hideOverlay();return;}String id=pending;finish(why);
    if(!id.isEmpty()){
      log("已请求取消电脑操作 "+id+"。取消不会撤销已有修改。");
      request("POST","/api/jobs/"+id+"/cancel",new JSONObject(),v->request("GET","/api/jobs/"+id,null,r->{if(!r.optString("state").equals("running")&&mainPrefs().getString("job","").equals(id)){mainPrefs().edit().remove("job").apply();pending="";}},e->{}),e->{});
    }
  }
  void closeApproval(){if(approval!=null){approval.dismiss();approval=null;}}
  void showOverlay(){hideOverlay();LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(18,12,18,12);box.setBackgroundColor(Color.rgb(20,42,52));
    overlayText=new TextView(this);overlayText.setTextColor(Color.WHITE);overlayText.setTextSize(12);overlayText.setMaxLines(4);box.addView(overlayText);
    LinearLayout row=new LinearLayout(this);overlayStop=new Button(this);overlayStop.setText("停止");overlayStop.setOnClickListener(v->{if(running)stop("用户停止自动维修");else hideOverlay();});row.addView(overlayStop);
    Button open=new Button(this);open.setText("查看日志");open.setOnClickListener(v->{startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));});row.addView(open);box.addView(row);
    WindowManager.LayoutParams p=new WindowManager.LayoutParams(Math.round(240*getResources().getDisplayMetrics().density),-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_SECURE,android.graphics.PixelFormat.TRANSLUCENT);
    p.gravity=Gravity.TOP|Gravity.END;p.y=Math.round(72*getResources().getDisplayMetrics().density);overlay=box;((WindowManager)getSystemService(WINDOW_SERVICE)).addView(box,p);
  }
  void hideOverlay(){if(overlay!=null){try{((WindowManager)getSystemService(WINDOW_SERVICE)).removeView(overlay);}catch(Exception ignored){}overlay=null;overlayText=null;overlayStop=null;}}
  @Override public void onDestroy(){if(running)stop("无障碍服务已关闭");hideOverlay();closeApproval();ui.removeCallbacks(ticker);if(instance==this)instance=null;net.shutdown();super.onDestroy();}
}
