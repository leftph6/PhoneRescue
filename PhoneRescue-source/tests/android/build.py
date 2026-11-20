import pathlib,subprocess,zipfile,sys
root=pathlib.Path(__file__).resolve().parents[2];src=root/'tests/android';out=src/'build';out.mkdir(exist_ok=True)
sys.path.insert(0,str(root/'scripts'))
from build_support import android_tools,signing_args
javac,android,bt=android_tools();java=javac.with_name('java.exe');jar=javac.with_name('jar.exe')
def run(*args):subprocess.run([str(x) for x in args],check=True)
classes=out/'classes';classes.mkdir(exist_ok=True)
run(javac,'-encoding','UTF-8','--release','8','-classpath',str(android)+';'+str(root/'android/build/classes.jar'),'-d',classes,*src.glob('*.java'))
run(jar,'cf',out/'classes.jar','-C',classes,'.')
run(java,'-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--lib',android,'--classpath',root/'android/build/classes.jar','--min-api','26','--output',out,out/'classes.jar')
run(bt/'aapt2.exe','link','-I',android,'--manifest',src/'AndroidManifest.xml','-o',out/'unsigned.apk')
with zipfile.ZipFile(out/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:z.write(out/'classes.dex','classes.dex')
run(bt/'zipalign.exe','-f','4',out/'unsigned.apk',out/'aligned.apk')
run(java,'-jar',bt/'lib/apksigner.jar','sign',*signing_args(javac.with_name('keytool.exe')),'--out',out/'test.apk',out/'aligned.apk')
