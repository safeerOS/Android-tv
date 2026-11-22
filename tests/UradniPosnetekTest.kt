import si.safeer.tv.os.UradniPosnetek as U
import si.safeer.tv.os.UradniPosnetek.Zadetek

private var napakUradni = 0
private fun preveriUradni(ime: String, ok: Boolean) { if (!ok) { napakUradni++; println("FAIL $ime") } }

fun main() {
    preveriUradni("ravno stresice", U.ravno("Čšž Éé!") == "csz ee")
    preveriUradni("golo brez oklepajev", U.golo("Trevo (Tu) (part. X)") == "trevo")
    preveriUradni("kanal enak", U.jeKanalIzvajalca("Nirvana", "Nirvana"))
    preveriUradni("kanal VEVO", U.jeKanalIzvajalca("NirvanaVEVO", "Nirvana"))
    preveriUradni("kanal Topic", U.jeKanalIzvajalca("Nirvana - Topic", "Nirvana"))
    preveriUradni("kanal obozevalec", !U.jeKanalIzvajalca("Nirvana Fan Club", "Nirvana"))
    preveriUradni("kanal v seznamu", U.jeKanalIzvajalca("Foo, Nirvana", "Nirvana"))
    preveriUradni("prazen izvajalec", !U.jeKanalIzvajalca("X", ""))

    val clip = Zadetek("clip", "Nirvana - Lithium (Official Music Video)", "Nirvana", 257)
    val audio = Zadetek("audio", "Nirvana - Lithium (Official Audio)", "Nirvana", 257)
    val live = Zadetek("live", "Nirvana - Lithium (Live at Reading)", "Nirvana", 260)
    val cover = Zadetek("cover", "Lithium (Cover)", "Nirvana Cover Channel", 257)
    val drugaSkladba = Zadetek("drug", "Nirvana - Heart-Shaped Box (Official Music Video)", "Nirvana", 280)
    val dolg = Zadetek("dolg", "Nirvana - Lithium (Official Music Video)", "Nirvana", 900)

    preveriUradni("izbere videospot pred zvocnim", U.izberi(listOf(audio, clip), "Lithium", "Nirvana", 257) == "clip")
    preveriUradni("zavrne koncert", U.izberi(listOf(live), "Lithium", "Nirvana", 257) == null)
    preveriUradni("zavrne tuj kanal", U.izberi(listOf(cover), "Lithium", "Nirvana", 257) == null)
    preveriUradni("zavrne drugo skladbo", U.izberi(listOf(drugaSkladba), "Lithium", "Nirvana", 257) == null)
    preveriUradni("zvocni, ce ni videa", U.izberi(listOf(audio), "Lithium", "Nirvana", 257) == "audio")
    preveriUradni("prava dolzina pred napacno", U.izberi(listOf(dolg, clip), "Lithium", "Nirvana", 257) == "clip")
    preveriUradni("brez zadetkov", U.izberi(emptyList(), "Lithium", "Nirvana", 257) == null)
    preveriUradni("prvi izvajalec iz seznama", U.izberi(listOf(clip), "Lithium", "Nirvana, Neko Drug", 257) == "clip")
    // Skladba, ki sama vsebuje »live«, ni kaznovana.
    val zivo = Zadetek("z", "Nirvana - Live Forever", "Nirvana", 200)
    preveriUradni("naslov z live ni rdeca zastavica", U.izberi(listOf(zivo), "Live Forever", "Nirvana", 200) == "z")
    // Najdbe CodeRabbit 22. 11. 2026 (isti primeri kot Linux tests/test_uradni_posnetek.py).
    preveriUradni("cirilica ostane", U.ravno("Кино") == "кино" && U.jeKanalIzvajalca("Кино", "Кино"))
    preveriUradni("one ni v stone", U.izberi(listOf(Zadetek("s", "Queen - Stone Cold Crazy (Official Video)", "Queen", 140)), "One", "Queen", 140) == null)
    preveriUradni("metallica one", U.izberi(listOf(Zadetek("o", "Metallica - One (Official Music Video)", "Metallica", 446)), "One", "Metallica", 446) == "o")
    preveriUradni("live dvakrat je koncert", U.izberi(listOf(Zadetek("k", "Paul McCartney - Live and Let Die (Live)", "Paul McCartney", 190)), "Live and Let Die", "Paul McCartney", 190) == null)
    preveriUradni("remix ne dovoli karaoke", U.izberi(listOf(Zadetek("r", "Nirvana - Lithium (Remix) (Karaoke)", "Nirvana", 257)), "Lithium (Remix)", "Nirvana", 257) == null)
    println(if (napakUradni == 0) "UradniPosnetekTest: VSE V REDU" else "UradniPosnetekTest: $napakUradni napak")
    if (napakUradni > 0) kotlin.system.exitProcess(1)
}
