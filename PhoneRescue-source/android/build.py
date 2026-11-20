"""Build the dependency-free native Android APK with the official SDK/JDK."""
import pathlib, subprocess, zipfile, sys
root=pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0,str(root/'scripts'))
from build_support import android_tools, signing_args
tc=root/'toolchain'; src=root/'android'; out=src/'build';out.mkdir(exist_ok=True)
javac,android,bt=android_tools();java=javac.with_name('java.exe');jar=javac.with_name('jar.exe');keytool=javac.with_name('keytool.exe')
classes=out/'classes';classes.mkdir(exist_ok=True)
def run(*args):subprocess.run([str(x) for x in args],check=True)
run(javac,'-encoding','UTF-8','--release','8','-classpath',android,'-d',classes,*src.glob('src/**/*.java'))
run(jar,'cf',out/'classes.jar','-C',classes,'.')
run(java,'-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--lib',android,'--min-api','26','--output',out,out/'classes.jar')
run(bt/'aapt2.exe','compile','--dir',src/'res','-o',out/'resources.zip')
run(bt/'aapt2.exe','link','-I',android,'--manifest',src/'AndroidManifest.xml','-o',out/'unsigned.apk',out/'resources.zip')
with zipfile.ZipFile(out/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:z.write(out/'classes.dex','classes.dex')
run(bt/'zipalign.exe','-f','4',out/'unsigned.apk',out/'aligned.apk')
signing=signing_args(keytool)
dest=root/'release';dest.mkdir(exist_ok=True)
run(java,'-jar',bt/'lib/apksigner.jar','sign',*signing,'--out',dest/'PhoneRescue-0.3.apk',out/'aligned.apk')
run(java,'-jar',bt/'lib/apksigner.jar','verify','--verbose',dest/'PhoneRescue-0.3.apk')
print('APK:',dest/'PhoneRescue-0.3.apk')
