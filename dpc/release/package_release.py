"""Package the verified APKs, a Windows installer and an allowlisted source tree."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path

# 下载镜像：第三方 GitHub 加速前缀。客户端按「官方 → 这几条」的顺序试，
# 所以这里只放真的能转发 releases/latest/download 的。加一条 = 改这一行再发版。
MIRROR_PREFIXES = (
    'https://gh-proxy.com/',
    'https://gh.nxnow.top/',
)

def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def build_version(root: Path) -> tuple[str, int]:
    """版本号的唯一真相在 dpc/build.sh：没显式给参数就跟着它走，避免默认值漂移。"""
    text = (root/'dpc/build.sh').read_text(encoding='utf-8')
    code = re.search(r'AFS_VERSION_CODE:-\s*(\d+)', text)
    name = re.search(r'AFS_VERSION_NAME:-\s*([\d][\d.]*)', text)
    if not code or not name:
        raise SystemExit('Cannot read default version from dpc/build.sh')
    return name.group(1), int(code.group(1))

def mirror_urls(repo: str, prefixes: list[str]) -> list[str]:
    """manifest.mirrors：同一份 APK 的镜像直链，客户端下载时挨个试。"""
    latest = f'https://github.com/{repo}/releases/latest/download/afs-guard.apk'
    return [prefix + latest for prefix in prefixes]

def read_notes(root: Path, version: str, inline: str, path: Path | None) -> str:
    """发版说明：命令行 > 指定文件 > dpc/release/notes/<版本>.md，都没有就空着。"""
    if inline.strip():
        return inline.strip()
    if path is not None:
        if not path.is_file():
            raise SystemExit(f'Notes file not found: {path}')
        return path.read_text(encoding='utf-8').strip()
    default = root/'dpc/release/notes'/f'{version}.md'
    return default.read_text(encoding='utf-8').strip() if default.is_file() else ''

def main() -> None:
    root = Path(__file__).resolve().parents[2]
    default_version, default_code = build_version(root)
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', required=True, type=Path)
    parser.add_argument('--version', default=default_version)
    parser.add_argument('--version-code', type=int, default=default_code)
    parser.add_argument('--maintenance-version-code', type=int, default=0)
    parser.add_argument('--repo', default='sdgfdii/AwayFromShorts-Phone',
                        help='owner/repo, used to build mirror download links')
    parser.add_argument('--notes', default='', help='release notes written into manifest.json')
    parser.add_argument('--notes-file', type=Path, help='read release notes from this file')
    parser.add_argument('--mirrors', default=','.join(MIRROR_PREFIXES),
                        help='comma separated accelerator prefixes; empty means no mirrors')
    args = parser.parse_args()
    if not args.maintenance_version_code:
        args.maintenance_version_code = args.version_code + 1
    if not re.fullmatch(r'\d+\.\d+\.\d+', args.version) or args.version_code < 1 or args.maintenance_version_code <= args.version_code:
        parser.error('Invalid release version or version codes')
    if not re.fullmatch(r'[\w.-]+/[\w.-]+', args.repo):
        parser.error('Invalid --repo, expected owner/repo')
    prefixes = []
    for prefix in (p.strip() for p in args.mirrors.split(',')):
        if not prefix:
            continue
        if not re.fullmatch(r'https://[^\s]+/', prefix):
            parser.error(f'Mirror prefix must be https://host/ : {prefix}')
        prefixes.append(prefix)
    out = root / 'dist' / args.version
    cert = (root/'dpc/release/signing-certificate.sha256').read_text().strip()
    apk = out/'afs-guard.apk'
    recovery = out/'maintenance/afs-recovery.apk'
    bt = args.sdk/'build-tools/34.0.0'
    for file, maintenance in [(apk, False), (recovery, True)]:
        info = subprocess.check_output([str(bt/'apksigner.bat'), 'verify', '--print-certs', str(file)], text=True, encoding='utf-8')
        if f'certificate SHA-256 digest: {cert}' not in info:
            raise SystemExit('Unexpected signing certificate')
        xml = subprocess.check_output([str(bt/'aapt2.exe'), 'dump', 'xmltree', '--file', 'AndroidManifest.xml', str(file)], text=True, encoding='utf-8')
        if ('".RecoveryReceiver"' in xml) != maintenance:
            raise SystemExit('Production / maintenance receiver isolation failed')
        if 'testOnly' in xml or 'debuggable' in xml:
            raise SystemExit('Test/debug manifest flag is not permitted')
        if maintenance and '"android.permission.DUMP"' not in xml:
            raise SystemExit('Maintenance receiver is not protected')
        badging = subprocess.check_output([str(bt/'aapt2.exe'), 'dump', 'badging', str(file)], text=True, encoding='utf-8')
        expected_version = args.maintenance_version_code if maintenance else args.version_code
        if (f"name='com.afs.guard' versionCode='{expected_version}' versionName='{args.version}'" not in badging
                or "sdkVersion:'24'" not in badging):
            raise SystemExit('APK identity or Android minimum version differs from the release manifest')
        with zipfile.ZipFile(file) as z:
            for name in z.namelist():
                data = z.read(name)
                if b'455b0ef2f1e04db50dcd0b67aa9e51ec' in data or re.search(rb'(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})', data):
                    raise SystemExit(f'Personal sync target or credential found in {name}')
    metadata = {
        'name': 'AwayFromShorts', 'version': args.version, 'package': 'com.afs.guard',
        'minSdk': 24, 'versionCode': args.version_code, 'certificateSha256': cert,
        'apk': {'file': 'afs-guard.apk', 'sha256': sha(apk.read_bytes())},
        'maintenance': {'file': 'maintenance/afs-recovery.apk', 'versionCode': args.maintenance_version_code, 'sha256': sha(recovery.read_bytes())},
    }
    notes = read_notes(root, args.version, args.notes, args.notes_file)
    if notes:
        metadata['notes'] = notes
    mirrors = mirror_urls(args.repo, prefixes)
    if mirrors:
        metadata['mirrors'] = mirrors
    metadata_bytes = (json.dumps(metadata, ensure_ascii=False, indent=2)+'\n').encode()
    (out/'manifest.json').write_bytes(metadata_bytes)
    if notes:
        (out/'notes.md').write_text(notes + '\n', encoding='utf-8')
    if mirrors:
        print('Mirrors: ' + ', '.join(mirrors))
    else:
        print('Mirrors: none (client falls back to the official source only)')
    readme = (root/'dpc/release/README.md').read_bytes()
    windows = {'README.md': readme, 'manifest.json': metadata_bytes,
               'afs-guard.apk': apk.read_bytes(), 'maintenance/afs-recovery.apk': recovery.read_bytes(),
               'LICENSE': (root/'LICENSE').read_bytes()}
    for p in (root/'dpc/deploy').iterdir():
        if p.is_file() and p.suffix in {'.ps1','.psm1','.cmd'}:
            windows[p.name] = p.read_bytes()
    for name in ['adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','libwinpthread-1.dll','NOTICE.txt','source.properties']:
        windows['tools/'+name] = (args.sdk/'platform-tools'/name).read_bytes()
    source = {'README.md': readme, 'LICENSE': (root/'LICENSE').read_bytes(),
              '.gitignore': b'/dist/\ndpc/build/\ndpc/keystore/\n*.keystore\n*.jks\n*.idsig\n*.local\n__pycache__/\n'}
    for folder in ['dpc/java','dpc/res','dpc/assets','dpc/libs','dpc/test','dpc/deploy','dpc/release','docs/examples','docs/memo-complete']:
        for p in (root/folder).rglob('*'):
            if p.is_file() and '__pycache__' not in p.parts and p.suffix not in {'.pyc','.log','.apk','.idsig','.keystore','.jks'}:
                source[p.relative_to(root).as_posix()] = p.read_bytes()
    for name in ['AndroidManifest.xml','build.sh','deploy.sh','uninstall.sh']:
        source['dpc/'+name] = (root/'dpc'/name).read_bytes()
    for name in ['LOCAL-DATA-BACKUP.md','CARD-MANAGEMENT.md','MEMO.md','MEMO-REFERENCE.md','MEMORY.md','MEMORY-CARD-SCRIPT.md','MATH-QUIZ.md','QUIZ-IMPORT-SCRIPT.md','THEME.md','AMAP-STYLE.md','SWISS-LAYOUT.md','PERF.md','TOOLBOX.md','VIRTUAL-TESTING.md','RENDERING-OPTIMIZATION.md']:
        source['docs/'+name] = (root/'docs'/name).read_bytes()
    source['dpc/README.md'] = b'See the repository README for deployment, recovery, building and verification.\n'
    for p in (root/'scripts').iterdir():
        if p.name.startswith(('import_quiz','import-quiz','test_import_quiz','make_memory_cards','make-memory-cards','test_make_memory_cards')):
            source['scripts/'+p.name] = p.read_bytes()
    forbidden = re.compile(r'(?:^|/)(?:keystore|build|\.git)(?:/|$)|(?:\.local|\.keystore|\.jks|\.apk)$')
    for name, data in source.items():
        if forbidden.search(name) or re.search(rb'(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})',data):
            raise SystemExit('Private/generated source file found: '+name)
    for label, files in [('Windows',windows),('source',source)]:
        dest = out/f'AwayFromShorts-{label}-{args.version}.zip'
        with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED) as z:
            for name in sorted(files):
                info = zipfile.ZipInfo(f'AwayFromShorts-{label}-{args.version}/'+name)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                z.writestr(info,files[name])
        print(f'{dest.name}: {sha(dest.read_bytes())}')
    sums = []
    for p in sorted(out.glob('*.zip')) + [apk,recovery]:
        sums.append(f'{sha(p.read_bytes())}  {p.relative_to(out).as_posix()}')
    (out/'SHA256SUMS.txt').write_text('\n'.join(sums)+'\n', encoding='ascii')
    print(f'Allowlisted source files: {len(source)}; Windows package files: {len(windows)}')

if __name__ == '__main__':
    main()
