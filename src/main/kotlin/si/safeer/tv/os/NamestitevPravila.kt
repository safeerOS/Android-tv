package si.safeer.tv.os

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Pravila namestitve APK prek seje PackageInstaller (os/NamestitevSeja) - brez Androida, preizkus
 * tests/NamestitevPravilaTest.kt.
 *
 * Zakaj seja (lastnik 25. 10. 2026: »enkrat uporabnik da vsa dovoljenja aplikaciji, ob posodobitvi ni potrebno
 * znova«): prej smo APK predali sistemskemu namestitelju z ACTION_VIEW; ta zapise vir LOCAL_FILE (izmerjeno na OnePlus 9,
 * Android 14: packageSource=3). AOSP 13 in 14 (InstallPackageHelper.updateSettingsInternalLI) ob VSAKI namestitvi z
 * virom LOCAL_FILE ali DOWNLOADED_FILE nastavi ACCESS_RESTRICTED_SETTINGS na MODE_ERRORED - tudi ob posodobitvi, zato
 * je vsaka posodobitev znova zaklenila »omejene nastavitve« (dostopnost, dostop do obvestil). Seja z virom
 * PACKAGE_SOURCE_OTHER stanja ne spremeni: kar je uporabnik enkrat dovolil, ostane.
 *
 * Vir povemo posteno (lastnik: varnost uporabnika pred udobjem): samo Safeerjeve aplikacije (isti podpisni kljuc kot
 * Safeer, ki namesca) in posodobitev iz safeer.si (SHA-256 iz os/razlicice.json) dobijo OTHER. Tuj APK iz Datotek ostane
 * LOCAL_FILE, iz spleta DOWNLOADED_FILE - zascita Androida pred zlonamernimi aplikacijami zanj velja naprej. STORE ne
 * uporabimo: Safeer ni trgovina (posodablja samo sebe in svoje aplikacije), za omejitve pa je v AOSP 13-16 OTHER enak
 * STORE (odloca samo LOCAL_FILE/DOWNLOADED_FILE oziroma zaupanje v namestitelja).
 */
object NamestitevPravila {

    // Vrednosti iz android.content.pm.PackageInstaller (AOSP android14-release, preverjeno 25. 10. 2026).
    const val VIR_NEDOLOCEN = 0      // PACKAGE_SOURCE_UNSPECIFIED
    const val VIR_DRUGO = 1          // PACKAGE_SOURCE_OTHER
    const val VIR_TRGOVINA = 2       // PACKAGE_SOURCE_STORE
    const val VIR_DATOTEKA = 3       // PACKAGE_SOURCE_LOCAL_FILE
    const val VIR_PRENOS = 4         // PACKAGE_SOURCE_DOWNLOADED_FILE

    const val UPORABNIK_NEDOLOCENO = 0   // SessionParams.USER_ACTION_UNSPECIFIED
    const val UPORABNIK_NI_POTREBEN = 2  // SessionParams.USER_ACTION_NOT_REQUIRED

    const val STATUS_POTRDITEV = -1      // STATUS_PENDING_USER_ACTION
    const val STATUS_USPEH = 0
    const val STATUS_NAPAKA = 1          // STATUS_FAILURE (notranja napaka, neznan vzrok)
    const val STATUS_BLOKIRANO = 2       // STATUS_FAILURE_BLOCKED (pravilnik naprave)
    const val STATUS_PREKINJENO = 3      // STATUS_FAILURE_ABORTED (zavrnil je uporabnik ali preverjevalnik, npr. Play Protect)

    /** Od kod je APK. */
    enum class Izvor {
        /** Posodobitev Safeerja iz safeer.si (os/Posodobitve): vsota SHA-256 iz os/razlicice.json. */
        POSODOBITEV,
        /** Datoteka na napravi (Datoteke, content://). */
        DATOTEKA,
        /** Prenos iz brskalnika ali od ponudnika aplikacij (TrgovineAplikacij). */
        SPLET
    }

    /** Podpis APK v primerjavi s podpisom Safeerja, ki namesca. */
    enum class Podpis { NAS, TUJ, NEZNAN }

    /**
     * NAS: APK ima natanko iste podpisnike kot Safeer (odtisi SHA-256 potrdil). Prazen seznam (Android podpisa ni dal)
     * je NEZNAN - nikoli NAS.
     */
    fun podpis(nasi: Collection<String>, apk: Collection<String>): Podpis {
        val n = nasi.map { it.lowercase() }.toSet()
        val a = apk.map { it.lowercase() }.toSet()
        return when {
            n.isEmpty() || a.isEmpty() -> Podpis.NEZNAN
            n == a -> Podpis.NAS
            else -> Podpis.TUJ
        }
    }

    /**
     * Vir paketa za SessionParams.setPackageSource; null = ne namestimo. Posodobitev s tujim podpisom zavrnemo (Android
     * bi jo prav tako, a uporabnik naj izve zakaj); neznan podpis pri posodobitvi gre naprej - pristnost dokazuje vsota
     * SHA-256, isti podpis pa Android pri posodobitvi istega paketa preveri sam.
     */
    fun virPaketa(izvor: Izvor, podpis: Podpis): Int? = when {
        izvor == Izvor.POSODOBITEV -> if (podpis == Podpis.TUJ) null else VIR_DRUGO
        podpis == Podpis.NAS -> VIR_DRUGO
        izvor == Izvor.DATOTEKA -> VIR_DATOTEKA
        else -> VIR_PRENOS
    }

    /**
     * SessionParams.setRequireUserAction: Safeerjeve aplikacije (posodobitev iz safeer.si ali nas podpis) brez
     * dodatnega okna Androida - uporabnik je »Namesti« ze potrdil v Safeerju. Android okno vseeno pokaze, ce pogoji niso
     * izpolnjeni (prva namestitev, Safeer ni namestitelj te aplikacije, targetSdk) - takrat pride STATUS_PENDING_USER_ACTION.
     * Tuj APK: vedno potrditev Androida (okno sistema, zasciteno pred prekrivanjem).
     */
    fun zahtevaUporabnika(izvor: Izvor, podpis: Podpis): Int =
        if (izvor == Izvor.POSODOBITEV || podpis == Podpis.NAS) UPORABNIK_NI_POTREBEN else UPORABNIK_NEDOLOCENO

    /** Kaj storiti ob stanju seje (PackageInstaller.EXTRA_STATUS). */
    enum class Odziv {
        /** Pokazi okno Androida (EXTRA_INTENT). */
        POTRDITEV,
        USPEH,
        /** Uporabnik je zavrnil: nic vec. */
        PREKLIC,
        /** Seja na tej napravi ne dela (notranja napaka): rezerva - sistemski namestitelj (ACTION_VIEW). */
        STARA_POT,
        /**
         * APK ne gre na to napravo (neveljaven, spor podpisa, prostor, nezdruzljiv) ali ga je zavrnil pravilnik naprave
         * (BLOCKED): sporocilo uporabniku, brez stare poti.
         */
        NAPAKA
    }

    fun odziv(status: Int): Odziv = when (status) {
        STATUS_POTRDITEV -> Odziv.POTRDITEV
        STATUS_USPEH -> Odziv.USPEH
        STATUS_PREKINJENO -> Odziv.PREKLIC
        STATUS_NAPAKA -> Odziv.STARA_POT
        // BLOCKED: namestitev je zavrnil pravilnik naprave - druga pot bi ga obsla, zato samo sporocilo. Zavrnitev
        // preverjevalnika (Google Play Protect, okno se ob seji pokaze - OnePlus 9, 25. 10. 2026) pride kot ABORTED
        // (AOSP 14 installStatusToPublicStatus: VERIFICATION_FAILURE -> STATUS_FAILURE_ABORTED) in je PREKLIC.
        STATUS_BLOKIRANO -> Odziv.NAPAKA
        else -> Odziv.NAPAKA
    }

    /**
     * Prepise [vhod] v [izhod] in vrne SHA-256 PREPISANIH bajtov - vsoto preverimo na istih bajtih, ki gredo v sejo,
     * ne na datoteki, prebrani prej (TOCTOU). [prekinjeno] ustavi prepis (InterruptedException).
     */
    fun prepisi(vhod: InputStream, izhod: OutputStream, prekinjeno: () -> Boolean = { false }, napredek: (Long) -> Unit = {}): String {
        val h = MessageDigest.getInstance("SHA-256")
        val b = ByteArray(1 shl 16)
        var skupaj = 0L
        while (true) {
            val n = vhod.read(b)
            if (n < 0) break
            if (prekinjeno()) throw InterruptedException("prekinjeno")
            if (n == 0) continue
            izhod.write(b, 0, n); h.update(b, 0, n); skupaj += n
            napredek(skupaj)
        }
        return hex(h.digest())
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** Primerjava vsot v stalnem casu (64 znakov hex, velike/male crke enako). Prazna ali napacne dolzine ni enaka. */
    fun enakaVsota(a: String?, b: String?): Boolean {
        val x = (a ?: "").trim().lowercase(); val y = (b ?: "").trim().lowercase()
        if (x.length != 64 || y.length != 64) return false
        return MessageDigest.isEqual(x.toByteArray(Charsets.US_ASCII), y.toByteArray(Charsets.US_ASCII))
    }
}
