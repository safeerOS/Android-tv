// Ime naprave v seznamu Safeer Linka: pokazemo ime, ki ga naprava pove; skrijemo samo nas tehnicni ID.
// Lastnik 10. 11. 2026: prenosnik z imenom gostitelja z vezaji je bil v seznamu pod »Zaslon«.
const fs = require("fs"), path = require("path"), assert = require("assert");
const js = fs.readFileSync(path.join(__dirname, "..", "assets", "link", "link.js"), "utf8");
const m = /function jeTehnicniId\(ime\) \{[\s\S]*?\n  \}/.exec(js);
assert.ok(m, "funkcija jeTehnicniId v link.js");
const jeTehnicniId = new Function(m[0] + "; return jeTehnicniId;")();
let n = 0;
for (const ime of ["uporabnik-Extensa-215-54", "moj-prenosnik", "safeer-test", "Dnevna-soba-TV", "PC-1234"]) {
  assert.strictEqual(jeTehnicniId(ime), false, ime + " je ime, ne ID"); n++;
}
for (const id of ["n-96bfc3da3b52ea80-control", "n-50d2bd37cb42e8eb", "tv-0123456789abcdef", "N-F1D0F648EA4E5602"]) {
  assert.strictEqual(jeTehnicniId(id), true, id + " je nas ID"); n++;
}
const telo = /function prijaznoIme\(naprava\) \{[\s\S]*?\n  \}/.exec(js)[0];
assert.ok(telo.includes("jeTehnicniId(ime)"), "prijaznoIme uporablja jeTehnicniId"); n++;
assert.ok(!telo.includes("[a-z0-9]+-[a-z0-9-]{4,}"), "stari vzorec ni vec v prijaznoIme"); n++;
console.log("link_ime_naprave_test: OK (" + n + " preverb)");
