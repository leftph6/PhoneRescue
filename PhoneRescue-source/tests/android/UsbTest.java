package local.phonerescue.app;
import android.app.Instrumentation;
import android.os.Bundle;
import android.content.Intent;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
public class UsbTest extends Instrumentation {
 String token;public void onCreate(Bundle b){token=b.getString("token");start();}
 JSONObject call(String path,JSONObject body)throws Exception{
  HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:18765/api/"+path).openConnection();c.setRequestProperty("X-Rescue-Token",token);c.setConnectTimeout(5000);c.setReadTimeout(10000);
  if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream o=c.getOutputStream()){o.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
  if(c.getResponseCode()>=300)throw new Exception("HTTP "+c.getResponseCode());ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}c.disconnect();return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
 }
 public void onStart(){Bundle result=new Bundle();try{
  JSONObject status=call("status",null);
  String id=UUID.randomUUID().toString();
  call("jobs",new JSONObject().put("id",id).put("command","Write-Output 'USB_PHONE_ROUNDTRIP_OK'; Write-Output '中文通过'").put("workingDirectory",status.getString("workspace")).put("timeoutSeconds",20));
  JSONObject r=null;for(int i=0;i<100;i++){r=call("jobs/"+id,null);if(!r.getString("state").equals("running"))break;Thread.sleep(100);}
  if(r==null||r.getInt("exitCode")!=0||!r.getString("stdout").contains("中文通过"))throw new Exception("roundtrip failed");
  Intent intent=new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);MainActivity activity=(MainActivity)startActivitySync(intent);
  runOnMainSync(()->{activity.pair.setText(token);activity.connect();});Thread.sleep(1500);
  final String[] text={""};runOnMainSync(()->text[0]=activity.status.getText().toString());if(!text[0].startsWith("已连接"))throw new Exception("UI pairing failed: "+text[0]);
  runOnMainSync(()->{activity.importText("```json\n{\"command\":\"Write-Output 'ready'\",\"workingDirectory\":\"C:\\\\Users\\\\Public\",\"timeoutSeconds\":30}\n```");});
  final String[] cmd={""};runOnMainSync(()->cmd[0]=activity.command.getText().toString());if(!cmd[0].equals("Write-Output 'ready'"))throw new Exception("import failed");
  File source=new File(getTargetContext().getCacheDir(),"bridge-source.bin"),saved=new File(getTargetContext().getCacheDir(),"bridge-downloaded.bin");
  byte[] bytes=new byte[262144];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i%251);
  try(FileOutputStream out=new FileOutputStream(source)){out.write(bytes);}
  runOnMainSync(()->activity.transfer(android.net.Uri.fromFile(source),true));
  for(int i=0;i<200&&activity.transferring;i++)Thread.sleep(100);
  if(activity.transferring)throw new Exception("upload timeout");
  org.json.JSONArray files=call("files",null).getJSONArray("files");String remote="";
  for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);if(f.getString("name").equals("phone-file")&&f.getLong("bytes")==bytes.length)remote=f.getString("path");}
  if(remote.isEmpty())throw new Exception("uploaded file missing");final String path=remote;
  runOnMainSync(()->{activity.downloadPath=path;activity.transfer(android.net.Uri.fromFile(saved),false);});
  for(int i=0;i<200&&activity.transferring;i++)Thread.sleep(100);
  if(!java.util.Arrays.equals(bytes,java.nio.file.Files.readAllBytes(saved.toPath())))throw new Exception("download bytes mismatch");
  source.delete();saved.delete();
  result.putString("result","PASS USB command roundtrip; Chinese output; four-digit native UI pairing; JSON import; Android upload/download 256KB exact byte match");finish(-1,result);
 }catch(Exception e){result.putString("error",e.toString());finish(0,result);}}
}
