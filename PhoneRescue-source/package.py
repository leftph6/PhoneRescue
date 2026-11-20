import pathlib,shutil,zipfile,hashlib,json,sys
root=pathlib.Path(__file__).resolve().parent;release=root/'release';dest=release/'windows'
sys.path.insert(0,str(root/'scripts'))
from build_support import adb_path
if not (dest/'PhoneRescue.exe').exists():raise SystemExit('Publish Windows first; see docs/BUILD.md')
for name in ['start-usb.cmd','start-lan.cmd','connect-usb.cmd','connect-usb.ps1','README.md','AUTOMATION.md','VALIDATION.md','LICENSE','THIRD_PARTY_NOTICES.md','AGENTS.md','CONTRIBUTING.md','SECURITY.md','CHANGELOG.md']:
 shutil.copy2(root/name,dest/name)
shutil.copytree(root/'docs',dest/'docs',dirs_exist_ok=True)
tools=adb_path().parent
for source in tools.rglob('*'):
 if source.is_file():
  target=dest/'platform-tools'/source.relative_to(tools)
  target.parent.mkdir(parents=True,exist_ok=True)
  if source.resolve()!=target.resolve() and (not target.exists() or hashlib.sha256(source.read_bytes()).digest()!=hashlib.sha256(target.read_bytes()).digest()):
   shutil.copy2(source,target)
# Remove only the old generated toolchain content accidentally copied by the first publish.
old=(dest/'toolchain').resolve()
assert old.parent==dest.resolve()
if old.exists():shutil.rmtree(old)
archive=release/'PhoneRescue-Windows-x64.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for p in dest.rglob('*'):
  if p.is_file():z.write(p,pathlib.Path('PhoneRescue-Windows')/p.relative_to(dest))
files=[release/'PhoneRescue-0.3.apk',archive]
(release/'SHA256SUMS.txt').write_text('\n'.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name for p in files)+'\n',encoding='ascii')
for p in files:print(p.name,p.stat().st_size)
