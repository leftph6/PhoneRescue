package local.phonerescue.app;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
public class ProtocolTest extends Instrumentation {
 public void onCreate(Bundle b){start();}
 void check(boolean condition,String message)throws Exception{if(!condition)throw new Exception(message);}
 String reply(String nonce,String action){return "RESCUE_REPLY_"+nonce+"\n"+action+"\nRESCUE_END_"+nonce;}
 void reject(String action)throws Exception{boolean rejected=false;try{RepairProtocol.parse(reply("test",action),"test");}catch(Exception e){rejected=true;}check(rejected,"unsafe action accepted: "+action);}
 public void onStart(){Bundle out=new Bundle();try{
   String prompt=RepairProtocol.prompt("test","diagnose","",1,8);
   check(RepairProtocol.parse(prompt,"test")==null,"request must not parse as a reply");
   check(RepairProtocol.parse(reply("old","{\"kind\":\"done\",\"summary\":\"old\"}"),"test")==null,"stale reply accepted");
   check(RepairProtocol.parse("RESCUE_REPLY_test\n{\"kind\":\"done\"}","test")==null,"partial response accepted");
   JSONObject v=RepairProtocol.parse(reply("test","{\"kind\":\"diagnose\",\"check\":\"system\",\"command\":\"Remove-Item X\"}"),"test");
   check(RepairProtocol.CHECKS.get(v.getString("check")).startsWith("Get-CimInstance"),"catalog did not override injected command");
   reject("{\"kind\":\"diagnose\",\"check\":\"Get-Content secret\"}");
   reject("{\"kind\":\"shell\",\"command\":\"echo x\",\"workingDirectory\":\"https://evil\",\"timeoutSeconds\":5,\"reason\":\"test\"}");
   reject("{\"kind\":\"shell\",\"command\":\"echo x\",\"workingDirectory\":\"C:\\\\Users\",\"timeoutSeconds\":999,\"reason\":\"test\"}");
   reject("{\"kind\":\"unknown\"}");
   check(RepairProtocol.parse(reply("test","{\"kind\":\"files\"}"),"test").getString("kind").equals("files"),"files rejected");
   check(RepairProtocol.CHECKS.containsKey("build_tools"),"missing compiler inventory");
   check(RepairProtocol.scrub("value=1234", "1234").equals("value=1234"),"PIN corrupted ordinary numeric output");
   JSONObject result=new JSONObject().put("state","completed").put("exitCode",0).put("stdout","pairing=ABCSECRET password=hunter2\n"+new String(new char[7000]).replace('\0','x'));
   String compact=RepairProtocol.resultForModel(result,"ABCSECRET");check(!compact.contains("ABCSECRET")&&!compact.contains("hunter2")&&compact.length()<5500,"redaction/truncation failed");
   JSONObject shell=RepairProtocol.parse(reply("test","{\"kind\":\"shell\",\"command\":\"Write-Output ok\",\"workingDirectory\":\"C:\\\\Users\\\\Public\",\"timeoutSeconds\":30,\"reason\":\"read-only test\"}"),"test");check(shell.getString("kind").equals("shell"),"valid shell rejected");
   out.putString("result","PASS nonce isolation; prompt/partial-reply rejection; fixed diagnostic catalog; schema validation; credential masking; output bounds");finish(-1,out);
 }catch(Exception e){out.putString("error",e.toString());finish(0,out);}}
}
