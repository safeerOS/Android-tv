package si.safeer.tv.os

import si.safeer.tv.os.NamestitevPravila.Izvor
import si.safeer.tv.os.NamestitevPravila.Odziv
import si.safeer.tv.os.NamestitevPravila.Podpis
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Namestitev prek seje PackageInstaller (os/NamestitevPravila): vir paketa, potrditev uporabnika, odziv na stanje seje in
 * vsota na bajtih, ki gredo v sejo. Izmerjeno 25. 10. 2026 (OnePlus 9, Android 14): stara pot ACTION_VIEW je dala
 * packageSource=3 (LOCAL_FILE) in zaklenjene omejene nastavitve.
 */
fun main() {
    val nas = listOf("AB".repeat(32))
    val tuj = listOf("cd".repeat(32))

    // Podpis: isti odtisi (velike/male crke enako) = NAS; prazno = NEZNAN, nikoli NAS.
    check(NamestitevPravila.podpis(nas, listOf("ab".repeat(32))) == Podpis.NAS)
    check(NamestitevPravila.podpis(nas, tuj) == Podpis.TUJ)
    check(NamestitevPravila.podpis(nas, emptyList()) == Podpis.NEZNAN)
    check(NamestitevPravila.podpis(emptyList(), nas) == Podpis.NEZNAN)
    // Vec podpisnikov: samo enak nabor je nas (APK z nasim IN tujim podpisom ni nas).
    check(NamestitevPravila.podpis(nas, nas + tuj) == Podpis.TUJ)
    check(NamestitevPravila.podpis(nas + tuj, nas) == Podpis.TUJ)

    // Vir paketa: Safeerjeve aplikacije OTHER (brez omejenih nastavitev), tuje posteno datoteka/prenos. Nikoli STORE.
    check(NamestitevPravila.virPaketa(Izvor.POSODOBITEV, Podpis.NAS) == NamestitevPravila.VIR_DRUGO)
    check(NamestitevPravila.virPaketa(Izvor.POSODOBITEV, Podpis.NEZNAN) == NamestitevPravila.VIR_DRUGO)
    check(NamestitevPravila.virPaketa(Izvor.POSODOBITEV, Podpis.TUJ) == null)       // tuj podpis posodobitve: ne namestimo
    check(NamestitevPravila.virPaketa(Izvor.DATOTEKA, Podpis.NAS) == NamestitevPravila.VIR_DRUGO)
    check(NamestitevPravila.virPaketa(Izvor.SPLET, Podpis.NAS) == NamestitevPravila.VIR_DRUGO)
    check(NamestitevPravila.virPaketa(Izvor.DATOTEKA, Podpis.TUJ) == NamestitevPravila.VIR_DATOTEKA)
    check(NamestitevPravila.virPaketa(Izvor.DATOTEKA, Podpis.NEZNAN) == NamestitevPravila.VIR_DATOTEKA)
    check(NamestitevPravila.virPaketa(Izvor.SPLET, Podpis.TUJ) == NamestitevPravila.VIR_PRENOS)
    check(NamestitevPravila.virPaketa(Izvor.SPLET, Podpis.NEZNAN) == NamestitevPravila.VIR_PRENOS)
    for (i in Izvor.values()) for (p in Podpis.values()) check(NamestitevPravila.virPaketa(i, p) != NamestitevPravila.VIR_TRGOVINA)
    // Vrednosti so iz android.content.pm.PackageInstaller (AOSP 14) - napacna stevilka bi Androidu povedala drug vir.
    check(NamestitevPravila.VIR_NEDOLOCEN == 0 && NamestitevPravila.VIR_DRUGO == 1 && NamestitevPravila.VIR_TRGOVINA == 2)
    check(NamestitevPravila.VIR_DATOTEKA == 3 && NamestitevPravila.VIR_PRENOS == 4)

    // Potrditev: Safeerjeve brez okna Androida (uporabnik je potrdil v Safeerju); tuj APK vedno z oknom Androida.
    check(NamestitevPravila.zahtevaUporabnika(Izvor.POSODOBITEV, Podpis.NEZNAN) == NamestitevPravila.UPORABNIK_NI_POTREBEN)
    check(NamestitevPravila.zahtevaUporabnika(Izvor.DATOTEKA, Podpis.NAS) == NamestitevPravila.UPORABNIK_NI_POTREBEN)
    check(NamestitevPravila.zahtevaUporabnika(Izvor.SPLET, Podpis.TUJ) == NamestitevPravila.UPORABNIK_NEDOLOCENO)
    check(NamestitevPravila.zahtevaUporabnika(Izvor.DATOTEKA, Podpis.NEZNAN) == NamestitevPravila.UPORABNIK_NEDOLOCENO)

    // Stanje seje -> odziv. Zavrnitev uporabnika ne gre na staro pot (ne vprasamo dvakrat).
    check(NamestitevPravila.odziv(-1) == Odziv.POTRDITEV)
    check(NamestitevPravila.odziv(0) == Odziv.USPEH)
    check(NamestitevPravila.odziv(3) == Odziv.PREKLIC)
    check(NamestitevPravila.odziv(1) == Odziv.STARA_POT)    // STATUS_FAILURE
    check(NamestitevPravila.odziv(2) == Odziv.NAPAKA)       // STATUS_FAILURE_BLOCKED (pravilnik naprave): ne obidemo s staro potjo
    for (s in listOf(4, 5, 6, 7, 8)) check(NamestitevPravila.odziv(s) == Odziv.NAPAKA)   // neveljaven, spor, prostor, nezdruzljiv, rok
    check(NamestitevPravila.odziv(Int.MIN_VALUE) == Odziv.NAPAKA)                       // brez EXTRA_STATUS
    check(NamestitevPravila.odziv(-2) == Odziv.NAPAKA)                                  // STATUS_PENDING_STREAMING ni nas primer

    // Prepis: vsota je vsota PREPISANIH bajtov (isti bajti kot v seji) in enaka MessageDigest nad vsebino.
    val podatki = ByteArray(200_000) { (it * 31 + 7).toByte() }
    val izhod = ByteArrayOutputStream()
    var zadnjiNapredek = 0L
    val h = NamestitevPravila.prepisi(ByteArrayInputStream(podatki), izhod, { false }) { zadnjiNapredek = it }
    check(izhod.toByteArray().contentEquals(podatki))
    check(h == NamestitevPravila.hex(MessageDigest.getInstance("SHA-256").digest(podatki)))
    check(zadnjiNapredek == podatki.size.toLong())
    check(NamestitevPravila.prepisi(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream()) ==
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    // Prekinitev ustavi prepis.
    var ustavljeno = false
    try { NamestitevPravila.prepisi(ByteArrayInputStream(podatki), ByteArrayOutputStream(), { true }) } catch (_: InterruptedException) { ustavljeno = true }
    check(ustavljeno)

    // Primerjava vsot: velike/male crke enako, napacna dolzina ali prazno nikoli enako.
    check(NamestitevPravila.enakaVsota(h, h.uppercase()))
    check(!NamestitevPravila.enakaVsota(h, h.dropLast(1) + (if (h.last() == '0') "1" else "0")))
    check(!NamestitevPravila.enakaVsota(h, h.dropLast(2)))
    check(!NamestitevPravila.enakaVsota("", ""))
    check(!NamestitevPravila.enakaVsota(null, h))

    println("NamestitevPravilaTest: OK")
}
