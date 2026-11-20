import json,pathlib,re,subprocess,time,urllib.request,urllib.error,urllib.parse,uuid,os,tempfile
root=pathlib.Path(__file__).resolve().parent.parent
temp=tempfile.TemporaryDirectory(prefix='phonerescue-test-')
port=18766
exe=root/os.environ.get('PHONERESCUE_TEST_EXE','release/staging-windows/PhoneRescue.exe')
p=subprocess.Popen([str(exe)],env=dict(os.environ,PHONERESCUE_PORT=str(port),PHONERESCUE_DATA=temp.name),stdout=subprocess.PIPE,stderr=subprocess.STDOUT,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW)
def call(path,data=None,token=None,origin=None):
 headers={'X-Rescue-Token':token if token is not None else secret,'Content-Type':'application/json'}
 if origin:headers['Origin']=origin
 req=urllib.request.Request(f'http://127.0.0.1:{port}/'+path,data=None if data is None else json.dumps(data).encode(),headers=headers)
 try:
  with urllib.request.urlopen(req,timeout=10) as r:return r.status,json.loads(r.read() or b'{}')
 except urllib.error.HTTPError as e:return e.code,e.read().decode()
def command(text,seconds=30):return {'id':str(uuid.uuid4()),'command':text,'workingDirectory':str(root),'timeoutSeconds':seconds}
def wait(j):
 for _ in range(200):
  code,v=call('api/jobs/'+j['id']);assert code==200
  if v['state']!='running':return v
  time.sleep(.1)
 raise AssertionError('job did not finish')
try:
 secret=''
 for _ in range(10):
  line=p.stdout.readline()
  m=re.search(r'/#([0-9]{4})\b',line)
  if m:secret=m[1];break
 assert secret,'no pairing token'
 for _ in range(40):
  try:assert call('api/status')[0]==200;break
  except (OSError,AssertionError):time.sleep(.1)
 assert call('api/status',token='bad')[0]==401
 assert call('api/status',origin='http://evil.example')[0]==403
 with urllib.request.urlopen(f'http://127.0.0.1:{port}/') as r:assert '电脑急救桥' in r.read().decode()
 print('PASS authentication, origin rejection, static UI')
 req=command("Write-Output '中文测试'; Write-Output (Get-Location).Path")
 assert call('api/jobs',req)[0]==202
 v=wait(req);assert v['exitCode']==0 and '中文测试' in v['stdout'] and str(root) in v['stdout'],v
 assert call('api/jobs',req)[0]==200
 changed=dict(req,command='Write-Output wrong');assert call('api/jobs',changed)[0]==409
 print('PASS Unicode, working directory, idempotency, changed-payload rejection')
 time.sleep(.2)
 req=command('Start-Sleep -Seconds 20',1);assert call('api/jobs',req)[0]==202
 assert call('api/jobs',command('Write-Output busy'))[0]==409
 assert wait(req)['state']=='timed_out'
 print('PASS timeout and concurrency limit')
 time.sleep(.2)
 req=command('Start-Sleep -Seconds 20');assert call('api/jobs',req)[0]==202
 assert call('api/jobs/'+req['id']+'/cancel',{})[0]==200
 assert wait(req)['state']=='cancelled'
 print('PASS cancellation')
 time.sleep(.2)
 req=command("throw 'expected-error'");assert call('api/jobs',req)[0]==202
 v=wait(req);assert v['exitCode']==1 and 'expected-error' in v['stderr'],v
 print('PASS failure reporting')
 time.sleep(.2)
 req=command("Write-Output ('x' * 200000)");assert call('api/jobs',req)[0]==202
 v=wait(req);assert v['truncated'] and len(v['stdout'])==131072
 assert call('api/jobs',command('echo no',301))[0]==400
 print('PASS output bound and input validation')
 def upload(name,data):
  req=urllib.request.Request(f'http://127.0.0.1:{port}/api/files/upload?name='+urllib.parse.quote(name),data=data,headers={'X-Rescue-Token':secret,'Content-Type':'application/octet-stream'})
  with urllib.request.urlopen(req) as r:assert r.status==201;return json.loads(r.read())
 def download(path):
  req=urllib.request.Request(f'http://127.0.0.1:{port}/api/files/download?path='+urllib.parse.quote(path,safe=''),headers={'X-Rescue-Token':secret})
  with urllib.request.urlopen(req) as r:return r.read()
 payload=bytes(range(256))*1000
 f=upload('binary.dat',payload);assert download(f['path'])==payload
 other=upload('binary.dat',b'other');assert other['path']!=f['path'] and download(f['path'])==payload
 for bad in ['../session.json','outbox/../../session.json','C:/Windows/win.ini','outbox/x:stream','outbox/../inbox/x']:
  assert call('api/files/download?path='+urllib.parse.quote(bad,safe=''))[0]==404,bad
 code,listing=call('api/files');assert code==200 and len(listing['files'])==2
 print('PASS four-digit USB pairing; binary upload/download; duplicate names; path rejection')
 # Compile a real uploaded C# source with the offline Windows compiler, run it,
 # then fetch the produced executable through the same download API as Android.
 cs=upload('hello.cs',b'class Hello { static void Main() { System.Console.WriteLine(6 * 7); } }')
 ws=pathlib.Path(listing['workspace']);out=ws/'outbox'/'hello.exe'
 compiler=pathlib.Path(os.environ['WINDIR'])/'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
 assert compiler.exists(),'Windows C# compiler missing'
 def quote(s):return "'"+str(s).replace("'","''")+"'"
 time.sleep(.2)
 req=command('& '+quote(compiler)+' /nologo /out:'+quote(out)+' '+quote(cs['fullPath'])+'; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }; & '+quote(out))
 assert call('api/jobs',req)[0]==202;v=wait(req);assert v['exitCode']==0 and '42' in v['stdout'],v
 assert download('outbox/hello.exe').startswith(b'MZ')
 print('PASS upload source -> compile -> run (42) -> download executable')
 csv=upload('input.csv','name,value\n中文,42\n'.encode('utf-8'));target=ws/'outbox'/'converted.json'
 time.sleep(.2)
 req=command('Import-Csv -LiteralPath '+quote(csv['fullPath'])+' -Encoding UTF8 | ConvertTo-Json | Set-Content -LiteralPath '+quote(target)+' -Encoding UTF8')
 assert call('api/jobs',req)[0]==202;v=wait(req);assert v['exitCode']==0,v
 assert json.loads(download('outbox/converted.json').decode('utf-8-sig'))=={'name':'中文','value':'42'}
 print('PASS upload Unicode CSV -> convert JSON -> download and validate content')
 cli=[os.sys.executable,str(root/'scripts/bridge_cli.py'),'--session',str(pathlib.Path(temp.name)/'session.json')]
 def cli_call(*args):
  result=subprocess.run([*cli,*map(str,args)],capture_output=True,text=True,encoding='utf-8',check=True)
  return [json.loads(line) for line in result.stdout.splitlines()]
 assert cli_call('status')[0]['version']=='0.3.0'
 local=pathlib.Path(temp.name)/'cli-input.bin';local.write_bytes(payload)
 uploaded=cli_call('upload',local)[0];saved=pathlib.Path(temp.name)/'cli-output.bin'
 cli_call('download',uploaded['path'],saved);assert saved.read_bytes()==payload
 script=pathlib.Path(temp.name)/'cli-test.ps1';script.write_text("Write-Output 'CLI_OK'",encoding='utf-8')
 time.sleep(.2)
 lines=cli_call('run','--script',script,'--timeout','10');assert lines[0]['state']=='submitting' and lines[-1]['exitCode']==0 and 'CLI_OK' in lines[-1]['stdout']
 assert cli_call('job',lines[0]['id'])[0]['state']=='completed'
 print('PASS agent CLI session discovery; file upload/download; script run; job lookup')
finally:
 p.terminate();p.wait(timeout=10)
 temp.cleanup()
