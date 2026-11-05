"""Peti slepi pregled 5. 11. 2026: tests/check_public_package.py mora ujeti tudi seznam gostiteljev (ime pred »tv«).

Zazene samo definicije vzorcev iz check_public_package.py (do BOOKMARK_FILES), brez pregleda drevesa.
   python3 tests/check_public_package_test.py [pot do check_public_package.py]
"""
import sys
from pathlib import Path

pot = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent / 'check_public_package.py'
vir = pot.read_text(encoding='utf-8')
okolje = {'__file__': str(pot)}
exec(compile(vir[:vir.index('BOOKMARK_FILES = {')], str(pot), 'exec'), okolje)


def ujame(besedilo):
    return any(vzorec.search(besedilo) for _, vzorec in okolje['SITE_NAME_PATTERNS'])


PRIMERI = [
    ("var gs = ['xploretv.si', 'rtvslo.si'];", True),            # seznam gostiteljev v tv_spatial.js (2c0a3715)
    ('Regex("streamer03\\\\.xploretv\\\\.si(?::\\\\d+)?")', True),     # ubezana pika v nizu Kotlina
    ('hevristika za Xplore TV in RTV 365', True),                # ime samo (kot prej)
    ('https://www.XPLORETV.si/livetv', True),
    ('"explorer", "files"', False),                              # navadna beseda
    ('lazniexploretv.si', False),
    ('xplore_tv_button', True),
]
padlo = [(t, p) for t, p in PRIMERI if ujame(t) != p]
for t, p in PRIMERI:
    print(('  OK   ' if ujame(t) == p else '  PADLO ') + repr(t) + ' -> ' + ('ujeto' if p else 'dovoljeno'))
if padlo:
    print('check_public_package_test: PADLO %d' % len(padlo))
    sys.exit(1)
print('check_public_package_test: OK (%d)' % len(PRIMERI))
