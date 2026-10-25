package si.safeer.tv.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import si.safeer.tv.R

/**
 * Safeer Link na televizorju tece kot storitev, ne kot del brskalnika.
 *
 * Prej je Hub zivel v dejavnosti: ko je uporabnik zaprl brskalnik, je izginil, in
 * "Poslji na TV" na telefonu ni imel kam poslati. Televizor je zaslon v dnevni sobi -
 * uporabnik pricakuje, da je dosegljiv takrat, ko hoce nekaj poslati nanj, ne takrat,
 * ko je na njem slucajno odprt brskalnik.
 *
 * Zato: kdor Safeer Link na televizorju prizge, ga prizge za stalno. Storitev tece v
 * ospredju (Android drugace strezniski vticnik v ozadju ubije), ZagonPrejemnik pa jo
 * po ponovnem vklopu televizorja zazene znova. Ugasne jo samo uporabnik.
 *
 * Vrsta storitve je "connectedDevice": strezemo napravam, ki so seznanjene s tem
 * televizorjem. To je edina vrsta, ki jo Android dovoli zagnati tudi po vklopu naprave
 * (dataSync in mediaPlayback sta iz BOOT_COMPLETED prepovedana).
 */
class HubStoritev : Service() {

    companion object {
        private const val TAG = "SafeerHubStoritev"
        private const val KANAL = "safeer_link_hub"
        private const val OBVESTILO = 4042
        private const val KANAL_PRIJAVA = "safeer_link_prijava"
        private const val OBVESTILO_PRIJAVA = 4048
        private const val KANAL_VARNOST = "safeer_link_varnost"
        private const val OBVESTILO_ZAPORA = 4049
        private const val OBVESTILO_NAPAD = 4050
        private const val OBVESTILO_KODA = 4051
        private const val KANAL_VIRI = "safeer_link_viri"
        private const val OBVESTILO_VIRI = 4052
        private const val OBVESTILO_DRUGA = 4053

        /**
         * Odjemalec (CastReceiverService, LinkOdjemalec, odhodni sosed v HubMesh): druga naprava v Linku je odgovorila
         * ni_virov - nima virov za to napravo. Uporabnik izve v svojem jeziku, katera naprava (ime iz odgovora ali
         * [privzetoIme]): najvec enkrat na 10 min za isto napravo (ViriNaprave.zavrnilaDruga), ne ob vsakem ponovnem
         * poskusu; ponovna povezava ostane samodejna. [lastna]: sredisce na tej napravi - obvestilo je dalo ze samo.
         * Vrne true, ce je bil odgovor ni_virov.
         */
        fun preveriZavrnitevVirov(context: Context, odgovor: String?, privzetoIme: String, lastna: Boolean = false): Boolean {
            val ime = ViriNaprave.zavrnitevIzOdgovora(odgovor) ?: return false
            if (lastna) return true
            val koga = ime.ifBlank { privzetoIme }.ifBlank { "?" }.take(64)
            if (!ViriNaprave.zavrnilaDruga(koga)) return true
            try {
                val upravitelj = context.getSystemService(NotificationManager::class.java) ?: return true
                if (upravitelj.getNotificationChannel(KANAL_VIRI) == null) {
                    upravitelj.createNotificationChannel(NotificationChannel(KANAL_VIRI, context.getString(R.string.link_viri_kanal),
                        NotificationManager.IMPORTANCE_DEFAULT).apply { description = context.getString(R.string.link_viri_kanal_opis) })
                }
                val telo = context.getString(R.string.link_viri_zaseden, koga)
                upravitelj.notify(OBVESTILO_DRUGA, Notification.Builder(context, KANAL_VIRI)
                    .setContentTitle(context.getString(R.string.link_viri_zaseden_naslov))
                    .setContentText(telo)
                    .setStyle(Notification.BigTextStyle().bigText(telo))
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setAutoCancel(true)
                    .build())
            } catch (e: Throwable) {
                Log.w(TAG, "Obvestila o zasedeni napravi ni bilo mogoce prikazati: ${e.message}")
            }
            return true
        }

        /**
         * Clan kroga ni bil sprejet, ker tej napravi zmanjkuje virov (ViriNaprave; isto kot core/link_viri.py na
         * racunalniku): uporabnik to izve v svojem jeziku, ne ugiba, zakaj se naprava ne poveze. Klice ga ViriNaprave
         * najvec enkrat na OBVESTILO_VSAKIH_MS (10 min); deluje tudi, ko storitev ne tece.
         */
        fun obvestiOVirih(context: Context, razlog: String, zavrnjenih: Int) {
            try {
                val upravitelj = context.getSystemService(NotificationManager::class.java) ?: return
                if (upravitelj.getNotificationChannel(KANAL_VIRI) == null) {
                    upravitelj.createNotificationChannel(NotificationChannel(KANAL_VIRI, context.getString(R.string.link_viri_kanal),
                        NotificationManager.IMPORTANCE_DEFAULT).apply { description = context.getString(R.string.link_viri_kanal_opis) })
                }
                val (naslov, besedilo) = when (razlog) {
                    ViriNaprave.DESKRIPTORJI -> R.string.link_viri_deskriptorji_naslov to R.string.link_viri_deskriptorji
                    ViriNaprave.NITI -> R.string.link_viri_niti_naslov to R.string.link_viri_niti
                    else -> R.string.link_viri_pomnilnik_naslov to R.string.link_viri_pomnilnik
                }
                val telo = context.getString(besedilo, zavrnjenih)
                upravitelj.notify(OBVESTILO_VIRI, Notification.Builder(context, KANAL_VIRI)
                    .setContentTitle(context.getString(naslov))
                    .setContentText(telo)
                    .setStyle(Notification.BigTextStyle().bigText(telo))
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setAutoCancel(true)
                    .build())
            } catch (e: Throwable) {
                Log.w(TAG, "Obvestila o virih ni bilo mogoce prikazati: ${e.message}")
            }
        }

        const val AKCIJA_ZACNI = "si.safeer.tv.cast.HUB_ZACNI"
        const val AKCIJA_KONCAJ = "si.safeer.tv.cast.HUB_KONCAJ"

        /** Gumba na obvestilu obrambe: sprosti ustavljeno napravo (dodatek [DODATEK_VIR]) in odpri povezovanje s kodo. */
        const val AKCIJA_SPROSTI = "si.safeer.tv.cast.OBRAMBA_SPROSTI"
        const val AKCIJA_ODPRI_KODO = "si.safeer.tv.cast.OBRAMBA_ODPRI_KODO"
        const val DODATEK_VIR = "vir"
        const val DODATEK_OBVESTILO = "obvestilo"
        const val DODATEK_IME = "ime"

        /** Stevilka obvestila za zaporo vira: vsak vir svoje (zapora drugega ne sme prekriti prve). */
        internal fun obvestiloZapore(vir: String): Int = OBVESTILO_ZAPORA + 16 + (vir.hashCode() and 0xfff)

        /**
         * Uporabnik je Safeer Link prizgal. Hub zazenemo takoj (da vmesnik lahko pove,
         * ali je uspelo), storitev pa poskrbi, da tece naprej, ko brskalnika ni vec.
         */
        fun vklopi(context: Context): Boolean {
            val app = context.applicationContext
            if (!si.safeer.tv.os.Sosed.vodimLink(app)) return false
            val uspelo = HubKrmilnik.zazeni(app, zapomni = true)
            if (uspelo) zazeniStoritev(app, AKCIJA_ZACNI)
            return uspelo
        }

        /**
         * Uporabnik je Safeer Link ugasnil: Hub ustavimo in zelje si ne zapomnimo vec. Storitev ustavimo neposredno -
         * zagon s startForegroundService samo zato, da se ustavi, je Safeer OS sesul (»Nadaljuj brez povezave naprav«
         * ob prvem zagonu: ForegroundServiceDidNotStartInTimeException, 19. 10. 2026).
         */
        fun izklopi(context: Context) {
            val app = context.applicationContext
            HubKrmilnik.ustavi(app, zapomni = true)
            try { app.stopService(Intent(app, HubStoritev::class.java)) } catch (e: Throwable) {
                Log.w(TAG, "Storitve ni bilo mogoce ustaviti: ${e.message}")
            }
        }

        /**
         * Ob zagonu brskalnika in po vklopu televizorja: ce je uporabnik Link ze prizgal,
         * poskrbi, da tece. Ce ga ni, se ne zgodi nic - nobenega obvestila in nobenega
         * omreznega prometa.
         */
        fun zagotovi(context: Context) {
            val app = context.applicationContext
            if (!si.safeer.tv.os.Sosed.vodimLink(app)) { HubKrmilnik.predajLinkLastniku(app); return }
            if (!HubKrmilnik.jeZazelen(app)) return
            zazeniStoritev(app, AKCIJA_ZACNI)
        }

        private fun zazeniStoritev(app: Context, akcija: String) {
            val namera = Intent(app, HubStoritev::class.java).apply { action = akcija }
            try {
                app.startForegroundService(namera)
            } catch (e: Throwable) {
                // Android lahko zagon iz ozadja zavrne (npr. tik pred ugasnjenjem naprave).
                // Hub v tem primeru tece naprej v procesu brskalnika, dokler ta zivi.
                Log.w(TAG, "Storitve ni bilo mogoce zagnati: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Gumba na obvestilu obrambe; storitev potem tece naprej kot prej.
        when (intent?.action) {
            AKCIJA_SPROSTI -> sprostiZObvestila(intent)
            AKCIJA_ODPRI_KODO -> odpriKodoZObvestila()
        }
        if (intent?.action == AKCIJA_KONCAJ || !si.safeer.tv.os.Sosed.vodimLink(this)) {
            HubKrmilnik.ustavi(applicationContext, zapomni = false)
            return koncajTakoj()
        }
        if (!HubKrmilnik.jeZazelen(applicationContext)) return koncajTakoj()
        try {
            pripraviKanal()
            startForeground(OBVESTILO, obvestilo())
        } catch (e: Throwable) {
            Log.w(TAG, "Obvestila ni bilo mogoce prikazati: ${e.message}")
        }
        si.safeer.tv.os.VklopTelevizorja.namesti(applicationContext)
        HubKrmilnik.naPrijavoZaObvestilo = { obvestiOPrijavi() }
        HubKrmilnik.naZaporo = { vir, trajanjeMs, razlog, ime -> obvestiOZapori(vir, trajanjeMs, razlog, ime) }
        HubKrmilnik.naNapad = { viri -> obvestiONapadu(viri) }
        HubKrmilnik.naZaporoKode = { trajanjeMs, razlog, viri -> obvestiOZaporiKode(trajanjeMs, razlog, viri) }
        // Naprava, ki se je umaknila izvoljenemu hubu, je njegov odjemalec: huba tu ne zaganjamo znova
        // (prej je storitev, zagnana tik po vklopu, hub prizgala se enkrat in izvolitev se je ponovila).
        // Ce izvoljeni hub izgine, gosti naprava spet sama (HubKrmilnik.izvoljeniHubIzgubljen).
        val odjemalecIzvoljenega = HubKrmilnik.izvoljeniHub(applicationContext) != null
        if (!HubKrmilnik.tece() && !odjemalecIzvoljenega && !HubKrmilnik.zazeni(applicationContext, zapomni = false)) {
            Log.w(TAG, "Huba ni bilo mogoce zagnati; storitev koncujem.")
            return koncajTakoj()
        }
        if (odjemalecIzvoljenega) HubKrmilnik.poveziNaIzvoljeni(applicationContext)
        // Safeer Link tece: Safeer Vnos sledi ozjemu krogu (ena privolitev, link/VnosVklop; brez dovoljenja nic).
        si.safeer.tv.link.VnosVklop.uskladi(applicationContext, "link")
        // START_STICKY: ce Android storitev ubije zaradi pomnilnika, naj jo po sprostitvi
        // zazene znova - uporabnik je povedal, da naj bo televizor dosegljiv.
        return START_STICKY
    }

    /**
     * Nova naprava se pridruzuje in Safeer OS ni na zaslonu (npr. tece druga aplikacija): brez tega uporabnik
     * kode ne bi videl nikjer. Obvestilo s kodo; ce je dovoljen prikaz cez druge aplikacije, Safeer OS
     * odpremo, da pokaze kodo v velikem oknu. Ko prijav ni vec, obvestilo umaknemo.
     */
    private fun sprostiZObvestila(namera: Intent) {
        val vir = namera.getStringExtra(DODATEK_VIR).orEmpty()
        if (vir.isEmpty()) return
        HubKrmilnik.sprostiVir(vir)
        val kdo = namera.getStringExtra(DODATEK_IME).orEmpty().ifBlank { vir }
        // Isto obvestilo zamenjamo s potrditvijo (brez gumba), da uporabnik vidi, da je dejanje uspelo.
        obvestiVarnost(namera.getIntExtra(DODATEK_OBVESTILO, obvestiloZapore(vir)), getString(R.string.link_obramba_naslov),
            getString(R.string.link_obramba_sproscena, kdo))
    }

    private fun odpriKodoZObvestila() {
        HubKrmilnik.odpriPovezovanjeSKodo()
        obvestiVarnost(OBVESTILO_KODA, getString(R.string.link_varovalka_naslov), getString(R.string.link_varovalka_odprto))
    }

    /** Gumb na obvestilu: namera za to storitev (ze tece, zato je to samo ukaz). */
    private fun dejanje(stevilka: Int, napis: Int, akcija: String, dodatki: Intent.() -> Unit = {}): Notification.Action {
        val namera = Intent(this, HubStoritev::class.java).setAction(akcija).apply(dodatki)
        val cakajoca = android.app.PendingIntent.getService(this, stevilka, namera,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, getString(napis), cakajoca).build()
    }

    private fun obvestiOPrijavi() {
        val upravitelj = getSystemService(NotificationManager::class.java) ?: return
        val prijave = try { HubKrmilnik.cakajocePrijave() } catch (_: Throwable) { emptyList() }
        val p = prijave.lastOrNull()
        if (p == null) { upravitelj.cancel(OBVESTILO_PRIJAVA); return }
        if (HubKrmilnik.naPrijavoZaZaslon != null) return // Safeer OS je odprt in kodo ze kaze
        try {
            if (upravitelj.getNotificationChannel(KANAL_PRIJAVA) == null) {
                upravitelj.createNotificationChannel(NotificationChannel(KANAL_PRIJAVA, getString(R.string.link_prijava_kanal),
                    NotificationManager.IMPORTANCE_HIGH).apply { description = getString(R.string.link_prijava_kanal_opis) })
            }
            val odpri = packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: packageManager.getLaunchIntentForPackage(packageName)
            val cakajoca = odpri?.let {
                android.app.PendingIntent.getActivity(this, OBVESTILO_PRIJAVA, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            }
            val koda = p.pin.take(3) + " " + p.pin.drop(3)
            val ime = p.ime.ifBlank { getString(R.string.link_prijava_nova_naprava) }
            val gradnik = Notification.Builder(this, KANAL_PRIJAVA)
                .setContentTitle(getString(R.string.link_prijava_naslov, ime))
                .setContentText(getString(R.string.link_prijava_besedilo, koda))
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setAutoCancel(true)
            if (cakajoca != null) gradnik.setContentIntent(cakajoca)
            upravitelj.notify(OBVESTILO_PRIJAVA, gradnik.build())
            if (odpri != null && android.provider.Settings.canDrawOverlays(this)) startActivity(odpri)
        } catch (e: Throwable) {
            Log.w(TAG, "Obvestila o prijavi ni bilo mogoce prikazati: ${e.message}")
        }
    }

    /**
     * Obramba sredisca je zaprla vir: uporabnik izve, katero napravo, zakaj in za koliko casa - in da njegove naprave
     * delajo naprej. Ce sredisce napravo s tega naslova pozna, je to najbrz njegova naprava s pokvarjeno prijavo.
     */
    private fun obvestiOZapori(vir: String, trajanjeMs: Long, razlog: String, ime: String) {
        val minut = maxOf(1, Math.round(trajanjeMs / 60_000.0).toInt())
        val besedilo = if (ime.isNotBlank()) {
            getString(R.string.link_obramba_znana, ime.trim().take(40), vir, minut)
        } else {
            val kaj = getString(when (razlog) {
                HubObramba.SEZNANITEV, HubObramba.POSKUS_KODE -> R.string.link_obramba_r_seznanitev
                HubObramba.ZACETEK_SEZNANITVE -> R.string.link_obramba_r_zacetek
                HubObramba.BREZ_ZAUPANJA -> R.string.link_obramba_r_brez_zaupanja
                HubObramba.POVEZAVA -> R.string.link_obramba_r_povezava
                HubObramba.ROKOVANJE -> R.string.link_obramba_r_rokovanje
                HubObramba.TIPANJE -> R.string.link_obramba_r_tipanje
                HubObramba.OKVIR -> R.string.link_obramba_r_okvir
                else -> R.string.link_obramba_r_drugo
            })
            getString(R.string.link_obramba_besedilo, vir, kaj, minut)
        }
        val stevilka = obvestiloZapore(vir)
        obvestiVarnost(stevilka, getString(R.string.link_obramba_naslov), besedilo,
            dejanje(stevilka, R.string.link_obramba_sprosti, AKCIJA_SPROSTI) {
                putExtra(DODATEK_VIR, vir)
                putExtra(DODATEK_OBVESTILO, stevilka)
                putExtra(DODATEK_IME, ime.trim().take(40))
            })
    }

    /** Varovalka je zaprla povezovanje s kodo: uporabnik izve zakaj, za koliko casa in kako zdaj doda napravo. */
    private fun obvestiOZaporiKode(trajanjeMs: Long, razlog: String, viri: List<String>) {
        // Zapore so tri: ena ura, en dan, en teden (HubVarovalka.ZAPORE_MS).
        val trajanje = getString(when {
            trajanjeMs <= 3_600_000L -> R.string.link_varovalka_ura
            trajanjeMs <= 86_400_000L -> R.string.link_varovalka_dan
            else -> R.string.link_varovalka_teden
        })
        val kaj = getString(if (razlog == HubVarovalka.RAZLOG_KODE) R.string.link_varovalka_r_kode else R.string.link_varovalka_r_zacetki)
        val odKod = if (viri.isEmpty()) "" else " (" + viri.take(3).joinToString(", ") + ")"
        obvestiVarnost(OBVESTILO_KODA, getString(R.string.link_varovalka_naslov),
            getString(R.string.link_varovalka_besedilo, kaj + odKod, trajanje),
            dejanje(OBVESTILO_KODA, R.string.link_varovalka_odpri, AKCIJA_ODPRI_KODO))
    }

    private fun obvestiONapadu(viri: List<String>) {
        obvestiVarnost(OBVESTILO_NAPAD, getString(R.string.link_obramba_napad_naslov),
            getString(R.string.link_obramba_napad_besedilo, viri.take(6).joinToString(", ")))
    }

    private fun obvestiVarnost(id: Int, naslov: String, besedilo: String, gumb: Notification.Action? = null) {
        try {
            val upravitelj = getSystemService(NotificationManager::class.java) ?: return
            if (upravitelj.getNotificationChannel(KANAL_VARNOST) == null) {
                upravitelj.createNotificationChannel(NotificationChannel(KANAL_VARNOST, getString(R.string.link_obramba_kanal),
                    NotificationManager.IMPORTANCE_DEFAULT).apply { description = getString(R.string.link_obramba_kanal_opis) })
            }
            val gradnik = Notification.Builder(this, KANAL_VARNOST)
                .setContentTitle(naslov)
                .setContentText(besedilo)
                .setStyle(Notification.BigTextStyle().bigText(besedilo))
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setAutoCancel(true)
            if (gumb != null) gradnik.addAction(gumb)
            upravitelj.notify(id, gradnik.build())
        } catch (e: Throwable) {
            Log.w(TAG, "Obvestila obrambe ni bilo mogoce prikazati: ${e.message}")
        }
    }

    override fun onDestroy() {
        HubKrmilnik.naPrijavoZaObvestilo = null
        HubKrmilnik.naZaporo = null
        HubKrmilnik.naNapad = null
        HubKrmilnik.naZaporoKode = null
        // Hub zivi v istem procesu; ko gre storitev, gre z njo tudi vticnik.
        HubKrmilnik.ustavi(applicationContext, zapomni = false)
        super.onDestroy()
    }

    /**
     * Storitev, zagnana s startForegroundService, mora poklicati startForeground tudi, ce se takoj ustavi - sicer
     * Android ob ustavitvi aplikacijo sesuje (ForegroundServiceDidNotStartInTimeException; npr. telefon, na katerem
     * Link vodi druga aplikacija Safeer). Obvestilo se pokaze le za trenutek.
     */
    private fun koncajTakoj(): Int {
        try {
            pripraviKanal()
            startForeground(OBVESTILO, obvestilo())
        } catch (e: Throwable) {
            Log.w(TAG, "Obvestila ni bilo mogoce prikazati: ${e.message}")
        }
        ustaviOspredje()
        stopSelf()
        return START_NOT_STICKY
    }

    private fun ustaviOspredje() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
    }

    private fun pripraviKanal() {
        si.safeer.tv.TihaObvestila.kanal(this)
    }

    private fun obvestilo(): Notification {
        val gradnik = Notification.Builder(this, si.safeer.tv.TihaObvestila.kanal(this))
        return gradnik
            .setContentTitle(getString(R.string.hub_obvestilo_naslov))
            .setContentText(getString(R.string.hub_obvestilo_besedilo))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }
}
