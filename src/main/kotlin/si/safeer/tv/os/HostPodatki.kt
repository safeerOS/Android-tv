package si.safeer.tv.os

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import org.json.JSONObject
import si.safeer.tv.R

/**
 * Koliko moci je na voljo: podatki hosta (racunalnika, ki televizorju daje datoteke in moc) ali,
 * ce hosta ni, samega televizorja. Procesor, pomnilnik, prostor - skupaj in se prosto.
 *
 * Racunalnik jih poslje na ukaz `host.info` po Safeer Linku (core/link_daljinec.py). Nic o
 * vsebini, samo stevilke o zmogljivosti.
 */
object HostPodatki {
    private const val TAG = "SafeerOsHostPodatki"

    class Podatki(val ime: String, val sistem: String, val cpu: String, val ram: String, val disk: String,
                  val jeTelevizor: Boolean, val grafika: String = "")

    /**
     * Vpraša računalnik v Safeer Linku; ce ga ni (ali ne odgovori), vrne podatke televizorja.
     * [naprej] se poklice na glavni niti, najvec enkrat.
     */
    fun preberi(context: Context, link: LinkUpravitelj, naprej: (Podatki) -> Unit) {
        val racunalnik = link.racunalnikiZDatotekami().firstOrNull()
            ?: link.naprave.firstOrNull { it.zmoznosti.contains("remote") && it.id != Identiteta.id(context) }
        if (racunalnik == null) {
            naprej(televizor(context))
            return
        }
        var koncano = false
        link.ukaz(racunalnik.id, "host.info", JSONObject(), 8_000, LinkOdjemalec.Odgovor { izid, napaka ->
            if (koncano) return@Odgovor
            koncano = true
            val podatki = izid?.optJSONObject("data")
            if (podatki == null) {
                Log.i(TAG, "Racunalnik ni dal podatkov ($napaka); kazem televizor.")
                naprej(televizor(context))
            } else {
                naprej(izJson(context, podatki, racunalnik.ime))
            }
        })
    }

    private fun izJson(context: Context, p: JSONObject, imeNaprave: String): Podatki {
        val cpu = p.optJSONObject("cpu")
        val ram = p.optJSONObject("ram")
        val disk = p.optJSONObject("disk")
        val jedra = cpu?.optInt("jedra", 0) ?: 0
        val model = cpu?.optString("model").orEmpty()
        val obremenitev = cpu?.optDouble("obremenitev", -1.0) ?: -1.0
        val cpuBesedilo = buildString {
            if (model.isNotBlank()) append(model.replace(Regex("\\s*\\(R\\)|\\s*\\(TM\\)"), "")).append(" · ")
            if (jedra > 0) append(context.getString(R.string.os_host_jedra, jedra))
            if (obremenitev >= 0) append(" · ").append(context.getString(R.string.os_host_obremenitev, obremenitev))
        }
        // Graficna kartica: od nje je odvisno, kako tekoca je slika racunalnika na televizorju,
        // zato zraven povemo tudi, ali zna sliko kodirati strojno.
        val gpu = p.optJSONObject("gpu")
        val grafika = buildString {
            val model = gpu?.optString("model").orEmpty().replace(" Corporation", "")
            if (model.isNotBlank()) append(model)
            val gonilnik = gpu?.optString("gonilnik").orEmpty()
            if (gonilnik.isNotBlank()) { if (isNotEmpty()) append(" · "); append(gonilnik) }
            if (gpu != null && gpu.has("strojno")) {
                if (isNotEmpty()) append(" · ")
                append(context.getString(
                    if (gpu.optBoolean("strojno")) R.string.os_moc_gpu_strojno else R.string.os_moc_gpu_brez))
            }
        }
        return Podatki(
            ime = p.optString("hostname").ifBlank { imeNaprave },
            sistem = p.optString("sistem"),
            cpu = cpuBesedilo,
            ram = paraBajtov(context, ram?.optLong("skupaj", 0L) ?: 0L, ram?.optLong("prosto", -1L) ?: -1L),
            disk = paraBajtov(context, disk?.optLong("skupaj", 0L) ?: 0L, disk?.optLong("prosto", -1L) ?: -1L),
            jeTelevizor = false,
            grafika = grafika,
        )
    }

    /** Ce racunalnika ni, povemo, kaj ima televizor sam - to je takrat ves host, ki ga imamo. */
    private fun televizor(context: Context): Podatki {
        val ram = try {
            val info = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            paraBajtov(context, info.totalMem, info.availMem)
        } catch (e: Throwable) { Log.w(TAG, "Pomnilnika ni bilo mogoce prebrati: ${e.message}"); "" }
        val disk = try {
            val s = StatFs(Environment.getDataDirectory().path)
            paraBajtov(context, s.blockCountLong * s.blockSizeLong, s.availableBlocksLong * s.blockSizeLong)
        } catch (e: Throwable) { Log.w(TAG, "Prostora ni bilo mogoce prebrati: ${e.message}"); "" }
        val jedra = Runtime.getRuntime().availableProcessors()
        // Grafika televizorja: razlicica OpenGL ES in ime strojne osnove - vec Android ne pove.
        val grafika = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val gles = am.deviceConfigurationInfo.glEsVersion
            listOf("OpenGL ES " + gles, android.os.Build.HARDWARE).filter { it.isNotBlank() }.joinToString(" · ")
        } catch (e: Throwable) { Log.w(TAG, "Grafike ni bilo mogoce prebrati: ${e.message}"); "" }
        return Podatki(android.os.Build.MODEL, "Android " + android.os.Build.VERSION.RELEASE,
            context.getString(R.string.os_host_jedra, jedra), ram, disk, true, grafika)
    }

    /** "7,6 GB (prosto 2,1 GB)"; ce prostega ne vemo, samo skupno. */
    private fun paraBajtov(context: Context, skupaj: Long, prosto: Long): String {
        if (skupaj <= 0) return ""
        val vse = velikost(skupaj)
        if (prosto < 0) return vse
        return context.getString(R.string.os_host_prosto, vse, velikost(prosto))
    }

    private fun velikost(bajti: Long): String {
        val gb = bajti / 1024.0 / 1024.0 / 1024.0
        val jezik = java.util.Locale.getDefault()
        if (gb >= 10) return String.format(jezik, "%.0f GB", gb)
        if (gb >= 1) return String.format(jezik, "%.1f GB", gb)
        return String.format(jezik, "%.0f MB", bajti / 1024.0 / 1024.0)
    }

    /**
     * Podrobnosti za svoje okno: vsak podatek v svojo vrstico. Prej je bila vse ena sama vrstica
     * na dnu nastavitev - dolga, odrezana in tik ob drugih moznostih.
     */
    fun podrobnosti(context: Context, p: Podatki): String {
        val vrstice = ArrayList<String>()
        // Samo ime naprave; "Host:" je ze v oznaki vrstice, dvojni napis je bil videti kot napaka.
        vrstice.add(context.getString(R.string.os_moc_ime) + ": " + p.ime)
        vrstice.add(context.getString(R.string.os_moc_kje) + ": " + context.getString(
            if (p.jeTelevizor) R.string.os_krajevno_ta_tv else R.string.os_host_racunalnik_kratko))
        if (p.sistem.isNotBlank()) vrstice.add(context.getString(R.string.os_moc_sistem) + ": " + p.sistem)
        if (p.cpu.isNotBlank()) vrstice.add(context.getString(R.string.os_moc_cpu) + ": " + p.cpu)
        if (p.grafika.isNotBlank()) vrstice.add(context.getString(R.string.os_moc_gpu) + ": " + p.grafika)
        if (p.ram.isNotBlank()) vrstice.add(context.getString(R.string.os_moc_ram) + ": " + p.ram)
        if (p.disk.isNotBlank()) vrstice.add(context.getString(R.string.os_moc_disk) + ": " + p.disk)
        return vrstice.joinToString("\n")
    }

    /** Ena vrstica za nastavitve. */
    fun vrstica(context: Context, p: Podatki): String {
        val deli = ArrayList<String>()
        val naslov = if (p.jeTelevizor) context.getString(R.string.os_host_ta_televizor, p.ime)
        else context.getString(R.string.os_host_racunalnik, p.ime)
        deli.add(naslov)
        if (p.sistem.isNotBlank()) deli.add(p.sistem)
        if (p.cpu.isNotBlank()) deli.add(p.cpu)
        if (p.ram.isNotBlank()) deli.add(context.getString(R.string.os_host_ram, p.ram))
        if (p.disk.isNotBlank()) deli.add(context.getString(R.string.os_host_disk, p.disk))
        return deli.joinToString(" · ")
    }
}
