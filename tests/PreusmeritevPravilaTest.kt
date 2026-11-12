package si.safeer.tv.os

private var stevilo = 0
private fun preveri(pogoj: Boolean, sporocilo: String) { check(pogoj) { sporocilo }; stevilo++ }

/** Neodvisni pregled 12. 11. 2026 (S1): skrivne glave dodatka ne smejo na tujega gostitelja. */
fun main() {
    val P = PreusmeritevPravila
    preveri(P.jePreusmeritev(302) && P.jePreusmeritev(307) && !P.jePreusmeritev(304) && !P.jePreusmeritev(200), "kode preusmeritev")
    preveri(P.naslednji("https://a.example/m.json", "/b/m.json") == "https://a.example/b/m.json", "relativen Location")
    preveri(P.naslednji("https://a.example/m.json", "https://b.example/x") == "https://b.example/x", "tuj gostitelj se razresi (glava ga ne dobi)")
    preveri(P.naslednji("https://a.example/m.json", "http://a.example/x") == null, "https -> http se ne sledi")
    preveri(P.naslednji("http://a.example/m.json", "https://a.example/x") == "https://a.example/x", "http -> https je dovoljeno")
    preveri(P.naslednji("https://a.example/", "file:///etc/passwd") == null, "shema file")
    preveri(P.naslednji("https://a.example/", "intent://x#Intent;end") == null, "shema intent")
    preveri(P.naslednji("https://a.example/", "https://u:p@b.example/") == null, "userinfo")
    preveri(P.naslednji("https://a.example/", null) == null && P.naslednji("https://a.example/", " ") == null, "brez Location")
    preveri(P.glavaSmeNaprej("https://a.example/m.json", "https://A.example/drugo"), "isti gostitelj")
    preveri(!P.glavaSmeNaprej("https://a.example/m.json", "https://b.example/m.json"), "drug gostitelj brez glave")
    preveri(!P.glavaSmeNaprej("https://a.example/m.json", "https://a.example:8443/m.json"), "druga vrata brez glave")
    preveri(!P.glavaSmeNaprej("https://a.example/m.json", "http://a.example/m.json"), "druga shema brez glave")
    println("PreusmeritevPravilaTest: OK ($stevilo preverb)")
}
