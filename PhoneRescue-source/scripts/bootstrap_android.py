"""Download pinned Windows SDK packages. Install JDK 17 separately; see docs/BUILD.md."""
import argparse
import hashlib
import pathlib
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1] / 'toolchain'
PACKAGES = [
    ('platform', 'platform-35_r02.zip', '0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0'),
    ('build-tools', 'build-tools_r35_windows.zip', '5753c679a1b90bcf6fbc9945a2ce39dfb9e74f1df0831a1e1866ae5b594326f0'),
    ('platform-tools', 'platform-tools_r37.0.3-win.zip', 'b5d2015f5d5b7a24a2ac646a245f20f63fb501e4174c61863c74bed1df6de4bd'),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accept-sdk-license', action='store_true', help='Confirm you have read and accepted the Android SDK license')
    args = parser.parse_args()
    if not args.accept_sdk_license:
        parser.error('Read https://developer.android.com/studio/terms then pass --accept-sdk-license, or use your own ANDROID_HOME SDK.')
    ROOT.mkdir(exist_ok=True)
    for folder, filename, expected in PACKAGES:
        archive = ROOT / (folder + '.zip')
        if not archive.exists():
            partial = archive.with_suffix('.part')
            with urllib.request.urlopen('https://dl.google.com/android/repository/' + filename, timeout=60) as response, partial.open('wb') as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
            partial.replace(archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
            raise SystemExit('SHA256 mismatch: ' + archive.name + '; remove this cached archive and retry.')
        target = ROOT / folder
        with zipfile.ZipFile(archive) as bundle:
            for member in bundle.infolist():
                if not (target / member.filename).resolve().is_relative_to(target.resolve()):
                    raise SystemExit('Unsafe archive member')
            bundle.extractall(target)
        print('READY', folder)


if __name__ == '__main__':
    main()
