"""Opt-in physical Android tests against an already-running local bridge."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlsplit

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'scripts'))
from build_support import adb_path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', help='ADB serial; auto-selects only when exactly one authorized physical phone exists')
    parser.add_argument('--install-app', action='store_true', help='Install/upgrade the built main APK before testing')
    args = parser.parse_args()
    adb = adb_path()
    serial = args.serial
    if not serial:
        output = subprocess.check_output([str(adb), 'devices'], text=True)
        devices = [line.split()[0] for line in output.splitlines() if line.endswith('\tdevice') and not line.startswith('emulator-')]
        if len(devices) != 1:
            raise SystemExit('Connect exactly one authorized physical phone or pass --serial.')
        serial = devices[0]

    def run(*command):
        result = subprocess.run([str(adb), '-s', serial, *map(str, command)], capture_output=True, text=True, encoding='utf-8', errors='replace')
        if result.returncode:
            # Do not include argv: instrumentation arguments contain the local PIN.
            raise RuntimeError('ADB failed: ' + (result.stdout + result.stderr).replace(token, '[hidden]'))
        return result.stdout

    session = Path(os.environ['LOCALAPPDATA']) / 'PhoneRescue/session.json'
    uri = urlsplit(json.loads(session.read_text(encoding='utf-8-sig'))['url'])
    token = uri.fragment
    if uri.hostname != '127.0.0.1' or uri.port != 18765 or not token.isdigit() or len(token) != 4:
        raise SystemExit('Start a 0.3 USB bridge first. This test requires a four-digit USB session.')
    installed = False
    try:
        if args.install_app:
            print(run('install', '--user', '0', '-r', root / 'release/PhoneRescue-0.3.apk'))
        print(run('install', '--user', '0', '-r', root / 'tests/android/build/test.apk'))
        installed = True
        run('reverse', 'tcp:18765', 'tcp:18765')
        for runner in ('ProtocolTest', 'UsbTest'):
            output = run('shell', 'am', 'instrument', '--user', '0', '-w', '-e', 'token', token,
                         'local.phonerescue.test/local.phonerescue.app.' + runner)
            print(output.replace(token, '[hidden]'))
            if 'PASS ' not in output or 'INSTRUMENTATION_CODE: -1' not in output:
                raise RuntimeError(runner + ' did not pass')
    finally:
        if installed:
            print(run('uninstall', 'local.phonerescue.test'))


if __name__ == '__main__':
    main()
