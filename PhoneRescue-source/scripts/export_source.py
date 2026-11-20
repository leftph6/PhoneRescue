"""Audit and export an explicit source allowlist, excluding local/private artifacts."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = {
    '.gitignore', '.gitattributes', 'LICENSE', 'README.md', 'AGENTS.md',
    'AUTOMATION.md', 'VALIDATION.md', 'CONTRIBUTING.md', 'SECURITY.md',
    'THIRD_PARTY_NOTICES.md', 'CHANGELOG.md', 'PhoneRescue.csproj', 'Program.cs',
    'package.py', 'start-usb.cmd', 'start-lan.cmd', 'connect-usb.cmd', 'connect-usb.ps1',
}
TREES = {
    'docs': {'.md'}, 'scripts': {'.py', '.ps1'}, '.github': {'.md', '.yml', '.yaml'},
    'wwwroot': {'.html', '.css', '.js'}, 'examples': {'.ps1', '.csv'},
    'android/src': {'.java'}, 'android/res': {'.xml'},
}
EXTRA = {'android/build.py', 'android/AndroidManifest.xml', 'tests/integration.py',
         'tests/usb_device.py', 'tests/android/build.py', 'tests/android/AndroidManifest.xml'}


def source_files():
    paths = {ROOT / name for name in ROOT_FILES | EXTRA}
    paths.update((ROOT / 'tests/android').glob('*.java'))
    for tree, extensions in TREES.items():
        paths.update(p for p in (ROOT / tree).rglob('*') if p.is_file() and p.suffix in extensions)
    for path in sorted(paths):
        if not path.is_file() or path.is_symlink() or not path.resolve().is_relative_to(ROOT):
            raise ValueError('Missing or unsafe source: ' + str(path.relative_to(ROOT)))
        text = path.read_text(encoding='utf-8-sig')
        if re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----', text):
            raise ValueError('Private key block in ' + str(path.relative_to(ROOT)))
        if re.search(r'http://127\.0\.0\.1:18765/#[A-Fa-f0-9]{48}\b', text):
            raise ValueError('Literal session credential in ' + str(path.relative_to(ROOT)))
        for match in re.finditer(r'C:[\\/]+Users[\\/]+([A-Za-z0-9_.-]+)', text):
            if match[1] not in {'Public', 'YOUR_USER', 'example'}:
                raise ValueError('Personal absolute path in ' + str(path.relative_to(ROOT)))
    return sorted(paths)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Audit source selection without writing output')
    args = parser.parse_args()
    files = source_files()
    if args.check:
        print(f'PASS source allowlist: {len(files)} files; no private artifacts selected')
        return
    release = ROOT / 'release'; release.mkdir(exist_ok=True)
    folder = release / 'PhoneRescue-source'
    # Only replace our own previously generated directory, never arbitrary files.
    if folder.exists():
        if folder.is_symlink() or folder.resolve().parent != release.resolve():
            raise ValueError('Unexpected export target')
        marker = folder / 'SOURCE_MANIFEST.json'
        if not marker.is_file():
            raise ValueError('Refusing to replace an unmanaged export directory')
        previous = json.loads(marker.read_text(encoding='utf-8'))
        existing = {p.relative_to(folder).as_posix() for p in folder.rglob('*') if p.is_file()}
        if existing - (set(previous) | {'SOURCE_MANIFEST.json'}):
            raise ValueError('Export directory contains extra files; move it before exporting again')
        shutil.rmtree(folder)
    folder.mkdir()
    manifest = {}
    for source in files:
        relative = source.relative_to(ROOT)
        target = folder / relative; target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        manifest[relative.as_posix()] = hashlib.sha256(target.read_bytes()).hexdigest()
    (folder / 'SOURCE_MANIFEST.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    archive = release / 'PhoneRescue-source.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as bundle:
        for file in sorted(folder.rglob('*')):
            if file.is_file():
                bundle.write(file, Path('PhoneRescue') / file.relative_to(folder))
    print(f'Exported {len(files)} source files to {folder}')
    print(f'Source ZIP: {archive} ({archive.stat().st_size} bytes)')


if __name__ == '__main__':
    main()
