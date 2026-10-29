# Facebook na TV (assets/site_facebook.js): namigi skriti, oglasi skriti, navadne objave ostanejo.  python3 tests/site_facebook_preizkus.py
# Potrebuje google-chrome; brez njega preizkus preskoci.
import pathlib, subprocess, sys, tempfile, re
skripta = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent.parent / "assets" / "site_facebook.js").read_text(encoding="utf-8")
html = """<!doctype html><html><head><meta charset=utf-8></head><body>
<div role="tooltip" id="namig">Igranje iger</div>
<div role="complementary">
  <h3 id="h">Sponzorirano</h3>
  <a id="oglasD" href="https://l.facebook.com/l.php?u=x">Trgovina</a>
  <h3 id="rojst">Rojstni dnevi</h3><a id="prijatelj" href="/ana">Ana</a>
</div>
<div role="feed">
  <div id="wrapperVira">
    <div id="obj1" aria-posinset="1"><a href="/maja">Maja</a><span>Lep dan</span></div>
    <div id="obj2" aria-posinset="2"><a aria-label="Sponzorirano" href="#">x</a><span>Kupite</span></div>
    <div id="obj3" data-pagelet="FeedUnit_1"><a href="https://l.facebook.com/l.php?u=y">stran</a><div role="button">Več o tem</div></div>
    <div id="obj4" role="article"><a href="https://l.facebook.com/l.php?u=z">clanek</a><div role="button">Všeč mi je</div></div>
    <div id="obj5" aria-posinset="5"><span id="lbl">Sponsored</span><a aria-labelledby="lbl" href="#">.</a></div>
  </div>
</div>
<script>""" + skripta + """</script>
<script>
document.body.setAttribute('data-izid', JSON.stringify({
  namig: getComputedStyle(document.getElementById('namig')).display,
  skriti: Array.prototype.map.call(document.querySelectorAll('[data-safeer-oglas]'), function(e){return e.id;})
}));
</script></body></html>"""
import shutil
if not shutil.which("google-chrome"):
    print("google-chrome ni namescen - preskocim"); sys.exit(0)
d = tempfile.mkdtemp()
p = pathlib.Path(d) / "fb.html"
p.write_text(html, encoding="utf-8")
out = subprocess.run(["google-chrome", "--headless=new", "--no-sandbox", "--disable-gpu", "--user-data-dir=" + d + "/profil",
                      "--dump-dom", p.as_uri()], capture_output=True, text=True, timeout=60).stdout
m = re.search(r'data-izid="([^"]*)"', out)
import html as h, json
izid = json.loads(h.unescape(m.group(1)))
print(izid)
pric = {"namig": "none", "skriti": ["h", "oglasD", "obj2", "obj3", "obj5"]}
assert izid == pric, ("pricakoval", pric)
print("FB preizkus OK")
