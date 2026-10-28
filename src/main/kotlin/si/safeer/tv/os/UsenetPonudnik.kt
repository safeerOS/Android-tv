package si.safeer.tv.os

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import si.safeer.tv.os.UsenetPonudnikPravila as P

/**
 * Android shramba ponudnika Usenet (okno "Moji viri -> Usenet ponudnik"). Vsa pravila so v UsenetPonudnikPravila;
 * tu je samo Android: Keystore, zasebne nastavitve, glavna nit, ura.
 *
 * Zakaj Keystore + AES-GCM in ne EncryptedSharedPreferences: brez nove knjiznice (androidx.security je opuscen),
 * kljuc ostane v strojni zbirki in ne zapusti naprave; v nastavitvah je samo sifrat, ki brez kljuca ni berljiv.
 *
 * Nic se ne zgodi v ozadju: razred nima opravil, alarmov ali WorkManagerja; preveri() klice samo gumb.
 * Vkljucitev v GlasbaActivity in povezavo na UsenetNntp naredi vodja (prijava je podana kot lambda).
 */
class UsenetPonudnik private constructor(context: Context) {
    private val app: Context = context.applicationContext
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE)

    private val nastavitve = object : P.Nastavitve {
        override fun beri(kljuc: String): String? = prefs.getString(kljuc, null)
        override fun pisi(kljuc: String, vrednost: String?) {
            // commit (ne apply): izid "shranjeno" mora pomeniti, da je na disku, sicer bi zapah lahko izginil.
            val e = prefs.edit()
            if (vrednost == null) e.remove(kljuc) else e.putString(kljuc, vrednost)
            e.commit()
        }
    }

    private val zbirka = object : P.ZbirkaGesel {
        override fun shrani(kljuc: String, geslo: P.Geslo): Boolean {
            val k = kljuc() ?: return false
            val c = Cipher.getInstance(SIFRA)
            c.init(Cipher.ENCRYPT_MODE, k)
            // AAD = kljuc vnosa: sifrata ni mogoce prestaviti pod drug racun (zamenjava vnosov v nastavitvah).
            c.updateAAD(kljuc.toByteArray(Charsets.UTF_8))
            val s = c.doFinal(geslo.vrednost().toByteArray(Charsets.UTF_8))
            val v = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(s, Base64.NO_WRAP)
            return prefs.edit().putString(PREDPONA_SIFRATA + kljuc, v).commit()
        }

        override fun preberi(kljuc: String): String? {
            val v = prefs.getString(PREDPONA_SIFRATA + kljuc, null) ?: return null
            val d = v.split(":")
            if (d.size != 2) return null
            val k = kljuc() ?: return null
            return try {
                val c = Cipher.getInstance(SIFRA)
                c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, Base64.decode(d[0], Base64.NO_WRAP)))
                c.updateAAD(kljuc.toByteArray(Charsets.UTF_8))
                String(c.doFinal(Base64.decode(d[1], Base64.NO_WRAP)), Charsets.UTF_8)
            } catch (e: Exception) {
                null  // kljuc razveljavljen (nov zaklep zaslona, obnova) - uporabnik vpise geslo znova (NI_GESLA)
            }
        }

        override fun pozabi(kljuc: String) {
            prefs.edit().remove(PREDPONA_SIFRATA + kljuc).commit()
        }
    }

    private val shrambaZapaha = object : P.ShrambaZapaha {
        override fun beri(): String? = prefs.getString(P.KLJUC_ZAPAHA, null)
        override fun pisi(besedilo: String) { prefs.edit().putString(P.KLJUC_ZAPAHA, besedilo).commit() }
    }

    private val shramba = P.ShrambaPonudnika(
        nastavitve, zbirka,
        P.ZapahRacunov(shrambaZapaha, { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } },
            { System.currentTimeMillis() / 1000 }),
        // elapsedRealtime: monotona ura, sprememba casa naprave ne odpre nove preverbe.
        P.OmejevalecPreverb { SystemClock.elapsedRealtime() },
        { System.currentTimeMillis() / 1000 },
    )

    private fun naGlavniNiti(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun kljuc(): SecretKey? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build())
            generateKey()
        }
    } catch (e: Exception) {
        null  // brez Keystore: geslo samo v pomnilniku procesa (ShrambaPonudnika), nikoli v navadno datoteko
    }

    // ------------------------------------------------------------------ API za okno in pogon

    fun preberi(): P.Ponudnik? = shramba.preberi()

    /** Gumb "Shrani" v oknu. Vrne false, ce geslo ni trajno (ni Keystore) - okno to pove. */
    fun shrani(p: P.Ponudnik, geslo: P.Geslo): Boolean {
        val prej = shramba.preberi()
        val trajno = shramba.shrani(p, geslo)
        // Kot Safeer-linux (pregled 1.0.134 A7): zamenjan racun - seja lastnega pogona starega racuna gre stran.
        if (prej != null && prej.kljucRacuna != p.kljucRacuna) try { UsenetLastni.ustavi() } catch (_: Throwable) { }
        // Nov vpis odklene tudi trajni zapah pogona (UsenetNntp, Keystore - prezivi posodobitev); prej ga je odklenil samo
        // »Preveri«, zato je lasten pogon po ponovnem vpisu istih poverilnic ostal na NapakaNntp(PRIJAVA) brez povezave.
        try {
            UsenetZapahShramba.namesti(app)
            UsenetNntp.dovoliPrijavoZnova(p.gostitelj, p.uporabnik, geslo.vrednost())
        } catch (_: Throwable) { }
        return trajno
    }

    /** Gumb "Pozabi ponudnika". Kot Safeer-linux (pregled 1.0.134 A7): tudi seja lastnega pogona (geslo v pomnilniku) gre stran. */
    fun pozabi() {
        shramba.pozabi()
        try { UsenetLastni.ustavi() } catch (_: Throwable) { }
    }

    fun stanje(): P.StanjeVmesnika = shramba.stanje()

    /**
     * Gumb "Preveri". Klici z delovne niti (na glavni niti NapakaPonudnika pred omrezjem). prijava: ENA povezava
     * UsenetNntp s privzeto zbirko potrdil in P.varniParametri; vrne izid in fazo pogona.
     */
    fun preveri(p: P.Ponudnik, geslo: P.Geslo, prijava: (P.Ponudnik, P.Geslo, List<String>) -> P.Prijava): String =
        shramba.preveri(p, geslo, naGlavniNiti(), P.Tls.SISTEMSKI, prijava)

    /** Pogon pred bazenom: geslo samo, ce ni ovire (TLS, geslo, zapah). */
    fun zaBazen(p: P.Ponudnik): Pair<P.Geslo?, String?> {
        val ovira = shramba.oviraBazena(p)
        return if (ovira == null) shramba.geslo(p) to null else null to ovira
    }

    /** Trda meja povezav na racun - pogon klice zasedi pred vsako novo povezavo in sprosti ob zaprtju. */
    val meje: P.MejeRacunov get() = MEJE

    /** Samo za secrets.sync (sifrirano, ozji krog). */
    fun zaKrog(): P.ZapisKroga? = shramba.zaKrog()

    /** Iz secrets.sync. Ne odklene zapaha. */
    fun prejetoIzKroga(ponudnik: Any?, geslo: String?): Boolean = shramba.prejetoIzKroga(ponudnik, geslo)

    companion object {
        private const val DATOTEKA = "usenet_ponudnik"
        private const val ALIAS = "safeer-usenet-ponudnik"
        private const val SIFRA = "AES/GCM/NoPadding"
        private const val PREDPONA_SIFRATA = "sifrat-"
        // Ena meja za ves proces: vsi bazeni istega racuna stejejo skupaj (lastnik: meja na RACUN, ne na bazen).
        private val MEJE = P.MejeRacunov()

        @Volatile private var primerek: UsenetPonudnik? = null

        /** En primerek: zapah v pomnilniku in omejitev preverb morata veljati za ves proces. */
        fun get(context: Context): UsenetPonudnik =
            primerek ?: synchronized(this) { primerek ?: UsenetPonudnik(context).also { primerek = it } }
    }
}
