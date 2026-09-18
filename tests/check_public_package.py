"""Check distributable assets, never developer files or browsing restrictions.

Also refuses site-specific adaptations for piracy / adult hosts anywhere in the
source tree or in the shipped assets. The browser must work on any page by the
generic rules; it must not carry a recipe for one named site.
"""
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
source = ROOT / 'src/main/kotlin/si/safeer/tv'

# Hostnames that must not appear in the published code or assets. This list is the
# guard, not a catalogue: it exists so a removed adaptation cannot silently return.
FORBIDDEN_HOSTS = [
    'hydrahd', 'streamnexus+hd', 'vidsrc', 'vidlink', 'vidgod', 'streamex',
    'autoembed', '2embed', 'multiembed', '111movies', 'megacloud', 'rabbitstream',
    'dokicloud', 'vizcloud', 'filemoon', 'streamtape', 'streamwish', 'doodstream',
    'cinemanos', 'ythd.org', 'pornhub', 'phncdn',
]

SELF = Path(__file__).name


def scan(path, text, forbidden):
    lower = text.lower()
    for host in forbidden:
        assert host not in lower, (str(path.relative_to(ROOT)), host)


for kotlin in source.rglob('*.kt'):
    scan(kotlin, kotlin.read_text(encoding='utf-8'), FORBIDDEN_HOSTS)
for asset in (ROOT / 'assets').rglob('*'):
    if asset.is_file():
        assert not any(part in asset.name.lower() for part in ['auth', '.local.', '.jks', '.keystore']), asset.name
        if asset.suffix.lower() in ('.js', '.html', '.css', '.json'):
            scan(asset, asset.read_text(encoding='utf-8', errors='ignore'), FORBIDDEN_HOSTS)
for doc in [ROOT / 'README.md', ROOT / 'docs' / 'SKILL.md']:
    if doc.exists():
        scan(doc, doc.read_text(encoding='utf-8'), FORBIDDEN_HOSTS)
for name in ['HomeTilesStore.kt', 'PortalManager.kt', 'BrowserRepository.kt']:
    text = (source / name).read_text()
    assert '192.168.' not in text, (name, '192.168.')
for file in sys.argv[1:]:
    with zipfile.ZipFile(file) as archive:
        names = archive.namelist()
        assets = [name for name in names if '/assets/' in '/' + name]
        for name in assets:
            assert not any(part in name.lower() for part in ['auth', '.local.', '.jks', '.keystore']), name
        homes = [name for name in assets if name.endswith('/brave_home.html')]
        assert len(homes) == 1, file
        home = archive.read(homes[0]).decode()
        assert '192.168.' not in home, file
        for host in FORBIDDEN_HOSTS:
            assert host not in home.lower(), (file, host)
        assert not any(name.endswith(('.so', '.db', '.sqlite', '.jks', '.keystore')) for name in names), file
        print('PASS public assets:', file)
print('PASS public defaults; no site-specific adaptations; user navigation remains unrestricted.')
