"""Check actual release ZIP contents, including the universal APK."""
import pathlib
import sys
import zipfile

allowed = {'armeabi-v7a', 'arm64-v8a'}
paths = [pathlib.Path(value) for value in sys.argv[1:]]
if not paths:
    raise SystemExit('Pass release APK paths')
for path in paths:
    with zipfile.ZipFile(path) as archive:
        abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
        if any(name.endswith('.apk') for name in archive.namelist()):
            raise SystemExit(f'Unexpected nested APK in {path.name}')
        if any(f'lib/{abi}/{library}' not in archive.namelist() for abi in abis for library in ['libyangshipin.so', 'libh5estream.so']):
            raise SystemExit(f'Missing integrated live library in {path.name}')
    if not abis or not abis <= allowed:
        raise SystemExit(f'Unexpected native architectures in {path.name}: {sorted(abis)}')
    if 'universal' in path.name and abis != allowed:
        raise SystemExit(f'Missing ARM architecture in {path.name}: {sorted(abis)}')
    print(f'{path.name}: {path.stat().st_size:,} bytes; {", ".join(sorted(abis))}')
