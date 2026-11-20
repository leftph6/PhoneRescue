"""Shared Windows Android tool and signing discovery (Python standard library)."""
import os
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]


def first(paths, description):
    for path in paths:
        if path.is_file():
            return path
    raise SystemExit(f'Missing {description}; see docs/BUILD.md')


def android_tools():
    if os.name != 'nt':
        raise SystemExit('This build script currently supports Windows only.')
    tc = ROOT / 'toolchain'
    jdk = pathlib.Path(os.environ.get('JAVA_HOME', str(tc / 'jdk')))
    javac = first([jdk / 'bin/javac.exe', *sorted(jdk.glob('*/bin/javac.exe'))], 'JDK 17 (JAVA_HOME)')
    sdk_value = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if sdk_value:
        sdk = pathlib.Path(sdk_value)
        android = first([sdk / 'platforms/android-35/android.jar'], 'Android platform 35')
        aapt = first([sdk / 'build-tools/35.0.0/aapt2.exe'], 'Android build-tools 35.0.0')
    else:
        android = first(sorted((tc / 'platform').rglob('android.jar')), 'Android platform 35')
        aapt = first(sorted((tc / 'build-tools').rglob('aapt2.exe')), 'Android build-tools 35.0.0')
    return javac, android, aapt.parent


def adb_path():
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    candidates = [pathlib.Path(sdk) / 'platform-tools/adb.exe'] if sdk else []
    candidates += [ROOT / 'toolchain/platform-tools/platform-tools/adb.exe', ROOT / 'release/windows/platform-tools/adb.exe']
    return first(candidates, 'Android platform-tools / adb')


def signing_args(keytool):
    custom = os.environ.get('PHONERESCUE_KEYSTORE')
    if custom:
        for name in ('PHONERESCUE_KEY_ALIAS', 'PHONERESCUE_STORE_PASS', 'PHONERESCUE_KEY_PASS'):
            if not os.environ.get(name):
                raise SystemExit('Missing signing environment variable: ' + name)
        return ['--ks', custom, '--ks-key-alias', os.environ['PHONERESCUE_KEY_ALIAS'],
                '--ks-pass', 'env:PHONERESCUE_STORE_PASS', '--key-pass', 'env:PHONERESCUE_KEY_PASS']
    key = ROOT / 'android/local-test.keystore'
    if not key.exists():
        subprocess.run([str(keytool), '-genkeypair', '-keystore', str(key), '-storepass', 'android',
                        '-keypass', 'android', '-alias', 'localtest', '-keyalg', 'RSA', '-keysize',
                        '2048', '-validity', '3650', '-dname', 'CN=PhoneRescue Local Test'], check=True)
    print('Signing with local DEVELOPMENT key (not a production release identity).')
    return ['--ks', str(key), '--ks-key-alias', 'localtest', '--ks-pass', 'pass:android', '--key-pass', 'pass:android']
