import si.safeer.tv.os.UradniPosnetek as U
import si.safeer.tv.os.UradniPosnetek.Zadetek

private var napak = 0
private fun preveri(ime: String, ok: Boolean) { if (!ok) { napak++; println("FAIL $ime") } }

fun main() {
    preveri("ravno stresice", U.ravno("Čšž Éé!") == "csz ee")
    preveri("golo brez oklepajev", U.golo("Trevo (Tu) (part. X)") == "trevo")
    preveri("kanal enak", U.jeKanalIzvajalca("Nirvana", "Nirvana"))
    preveri("kanal VEVO", U.jeKanalIzvajalca("NirvanaVEVO", "Nirvana"))
    preveri("kanal Topic", U.jeKanalIzvajalca("Nirvana - Topic", "Nirvana"))
    preveri("kanal obozevalec", !U.jeKanalIzvajalca("Nirvana Fan Club", "Nirvana"))
    preveri("kanal v seznamu", U.jeKanalIzvajalca("Foo, Nirvana", "Nirvana"))
    preveri("prazen izvajalec", !U.jeKanalIzvajalca("X", ""))

    val clip = Zadetek("clip", "Nirvana - Lithium (Official Music Video)", "Nirvana", 257)
    val audio = Zadetek("audio", "Nirvana - Lithium (Official Audio)", "Nirvana", 257)
    val live = Zadetek("live", "Nirvana - Lithium (Live at Reading)", "Nirvana", 260)
    val cover = Zadetek("cover", "Lithium (Cover)", "Nirvana Cover Channel", 257)
    val drugaSkladba = Zadetek("drug", "Nirvana - Heart-Shaped Box (Official Music Video)", "Nirvana", 280)
    val dolg = Zadetek("dolg", "Nirvana - Lithium (Official Music Video)", "Nirvana", 900)

    preveri("izbere videospot pred zvocnim", U.izberi(listOf(audio, clip), "Lithium", "Nirvana", 257) == "clip")
    preveri("zavrne koncert", U.izberi(listOf(live), "Lithium", "Nirvana", 257) == null)
    preveri("zavrne tuj kanal", U.izberi(listOf(cover), "Lithium", "Nirvana", 257) == null)
    preveri("zavrne drugo skladbo", U.izberi(listOf(drugaSkladba), "Lithium", "Nirvana", 257) == null)
    preveri("zvocni, ce ni videa", U.izberi(listOf(audio), "Lithium", "Nirvana", 257) == "audio")
    preveri("prava dolzina pred napacno", U.izberi(listOf(dolg, clip), "Lithium", "Nirvana", 257) == "clip")
    preveri("brez zadetkov", U.izberi(emptyList(), "Lithium", "Nirvana", 257) == null)
    preveri("prvi izvajalec iz seznama", U.izberi(listOf(clip), "Lithium", "Nirvana, Neko Drug", 257) == "clip")
    // Skladba, ki sama vsebuje »live«, ni kaznovana.
    val zivo = Zadetek("z", "Nirvana - Live Forever", "Nirvana", 200)
    preveri("naslov z live ni rdeca zastavica", U.izberi(listOf(zivo), "Live Forever", "Nirvana", 200) == "z")
    println(if (napak == 0) "UradniPosnetekTest: VSE V REDU" else "UradniPosnetekTest: $napak napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
