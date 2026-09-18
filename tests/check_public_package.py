"""Check distributable assets, never developer files or browsing restrictions.

Also refuses site-specific adaptations for piracy / adult hosts anywhere in the
source tree, in the shipped assets and inside the compiled code of the APK. The
browser must work on any page by the generic rules; it must not carry a recipe
for one named site.

The APK scan reads every entry, including classes.dex, because a hostname that
lives in a Kotlin string ends up compiled into the code and would otherwise slip
past a check that only looks at the home page.
"""
from pathlib import Path
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
source = ROOT / 'src/main/kotlin/si/safeer/tv'

# Hostnames that must not appear in the published code or assets. This list is the
# guard, not a catalogue: it exists so a removed adaptation cannot silently return.
# Entries that are ordinary English fragments carry their dot, so that a class name
# such as java.io.ObjectStreamException is not mistaken for a host.
FORBIDDEN_HOSTS = [
    'hydrahd', 'streamnexus', 'vidsrc', 'vidlink', 'vidgod', 'streamex.',
    'autoembed', '2embed.', 'multiembed', '111movies', 'megacloud', 'rabbitstream',
    'dokicloud', 'vizcloud', 'filemoon', 'streamtape', 'streamwish', 'doodstream',
    'cinemanos', 'ythd.org', 'pornhub', 'phncdn',
]

# A host must stand on its own: not glued to neighbouring letters or digits.
PATTERNS = [
    (host, re.compile(r'(?<![0-9a-z])' + re.escape(host) + r'(?![0-9a-z])', re.I))
    for host in FORBIDDEN_HOSTS
]

TEXT_SUFFIXES = ('.js', '.html', '.css', '.json', '.md', '.kt')

# A named service may appear only where the user picked it: a bookmark, a start tile or
# a voice shortcut. Anywhere else it would mean the browser carries a recipe for that one
# site instead of a generic rule - which is the thing this guard exists to prevent.
SITE_NAMES = ['xplore']
SITE_NAME_PATTERNS = [
    (name, re.compile(r'(?<![0-9a-z])' + re.escape(name) + r'(?![0-9a-z])', re.I))
    for name in SITE_NAMES
]
BOOKMARK_FILES = {
    'assets/brave_home.html',
    'assets/link/daljinec.js',
    'src/main/kotlin/si/safeer/tv/BrowserRepository.kt',
    'src/main/kotlin/si/safeer/tv/HomeTilesStore.kt',
    'src/main/kotlin/si/safeer/tv/PortalManager.kt',
}
# The generic agent is the only site script left: it works by what a page IS, not by who
# publishes it. A new site_<name>.js would be a recipe for one site and fails this check.
ALLOWED_SITE_SCRIPTS = {'site_agent.js'}


def scan_site_names(label, text):
    if label in BOOKMARK_FILES:
        return
    for name, pattern in SITE_NAME_PATTERNS:
        match = pattern.search(text)
        assert match is None, (
            label, name, 'site-specific adaptation outside the bookmark files',
            text[max(0, match.start() - 60):match.end() + 60])


def scan_text(label, text):
    for host, pattern in PATTERNS:
        match = pattern.search(text)
        assert match is None, (label, host, text[max(0, match.start() - 60):match.end() + 60])


def scan_bytes(label, data):
    for host, pattern in PATTERNS:
        match = pattern.search(data.decode('latin-1'))
        assert match is None, (label, host)


for kotlin in source.rglob('*.kt'):
    label = str(kotlin.relative_to(ROOT))
    text = kotlin.read_text(encoding='utf-8')
    scan_text(label, text)
    scan_site_names(label, text)
for asset in (ROOT / 'assets').rglob('*'):
    if asset.is_file():
        assert not any(part in asset.name.lower() for part in ['auth', '.local.', '.jks', '.keystore']), asset.name
        if asset.name.startswith('site_') and asset.name.endswith('.js'):
            assert asset.name in ALLOWED_SITE_SCRIPTS, ('new per-site script', asset.name)
        if asset.suffix.lower() in TEXT_SUFFIXES:
            label = str(asset.relative_to(ROOT))
            text = asset.read_text(encoding='utf-8', errors='ignore')
            scan_text(label, text)
            scan_site_names(label, text)
for doc in [ROOT / 'README.md', ROOT / 'docs' / 'SKILL.md']:
    if doc.exists():
        scan_text(doc.name, doc.read_text(encoding='utf-8'))
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
        assert '192.168.' not in archive.read(homes[0]).decode(), file
        # Every entry, code included: a hostname in a Kotlin string lands in classes.dex.
        for name in names:
            scan_bytes(file + '!' + name, archive.read(name))
        assert not any(name.endswith(('.so', '.db', '.sqlite', '.jks', '.keystore')) for name in names), file
        print('PASS public assets:', file)
print('PASS public defaults; no site-specific adaptations; user navigation remains unrestricted.')
