package si.safeer.tv.os

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import java.io.File

/**
 * Naloga 87 (57c): predpomnilnik Media Center v ENI datoteki SQLite (android.database.sqlite, vgrajen) na enem mestu - notranja
 * baza na TV (majhna, [PredpomnilnikPravila.NOTRANJA_MEJA]) ali baza na USB (velika, meja iz nastavitve).
 *
 * Tabela `vnosi(kljuc PK, vrsta, bajti BLOB, datoteka, velikost, ustvarjeno, zadnja_uporaba, poteka)` z indeksi za TTL in LRU.
 * Vrednost nad [PredpomnilnikPravila.BLOB_NAJVEC] je datoteka v mapi `<baza>-velike` (stolpec datoteka).
 *
 * - journal_mode=TRUNCATE (ne WAL: na exFAT/FAT32 brez deljenega pomnilnika in z eno datoteko manj), synchronous=NORMAL.
 * - Napaka odprtja ali pokvarjena baza: baza se izbrise in ustvari znova (je samo predpomnilnik) - nikoli sesutje.
 * - Nikoli z glavne niti (vrne null / ne naredi nicesar); pisanje gre na eno nit ([pisi]), branje tece vzporedno.
 * - VACUUM samo ob »Pocisti predpomnilnik«.
 */
class PredpomnilnikBaza(private val datoteka: File, private val obNapaki: () -> Unit = {}) {
    private val mapaVelikih = File(datoteka.parentFile, datoteka.name + "-velike")
    @Volatile private var baza: SQLiteDatabase? = null
    private val zaklep = Any()
    private val pisalec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "safeer-predpomnilnik").apply { isDaemon = true }
    }

    val pot: String get() = datoteka.path

    private fun glavna() = Looper.myLooper() == Looper.getMainLooper()

    /** Odprta baza ali null (napaka - pokvarjeno bazo pobrise in poskusi enkrat znova). */
    private fun odpri(): SQLiteDatabase? {
        baza?.let { if (it.isOpen) return it }
        synchronized(zaklep) {
            baza?.let { if (it.isOpen) return it }
            for (poskus in 0..1) {
                try {
                    datoteka.parentFile?.mkdirs()
                    val b = SQLiteDatabase.openDatabase(datoteka.path, null, SQLiteDatabase.CREATE_IF_NECESSARY)
                    // Pragme vracajo vrstico - rawQuery, ne execSQL.
                    b.rawQuery("PRAGMA journal_mode=TRUNCATE", null).use { it.moveToFirst() }
                    b.execSQL("PRAGMA synchronous=NORMAL")
                    b.execSQL("CREATE TABLE IF NOT EXISTS vnosi(kljuc TEXT PRIMARY KEY, vrsta TEXT NOT NULL, bajti BLOB, datoteka TEXT, " +
                        "velikost INTEGER NOT NULL, ustvarjeno INTEGER NOT NULL, zadnja_uporaba INTEGER NOT NULL, poteka INTEGER NOT NULL)")
                    b.execSQL("CREATE INDEX IF NOT EXISTS vnosi_uporaba ON vnosi(zadnja_uporaba)")
                    b.execSQL("CREATE INDEX IF NOT EXISTS vnosi_poteka ON vnosi(poteka)")
                    baza = b
                    return b
                } catch (e: Throwable) {
                    android.util.Log.w("SafeerOsMedia", "predpomnilnik: baza se ne odpre (${e.javaClass.simpleName}), ustvarim znova")
                    if (poskus == 0) zbrisi() else { obNapaki(); return null }
                }
            }
            return null
        }
    }

    /** Pokvarjena ali nedosegljiva baza: zapremo in pobrisemo (tudi velike datoteke). */
    private fun zbrisi() {
        try { baza?.close() } catch (_: Throwable) { }
        baza = null
        for (pripona in listOf("", "-journal", "-wal", "-shm")) try { File(datoteka.path + pripona).delete() } catch (_: Throwable) { }
        try { mapaVelikih.deleteRecursively() } catch (_: Throwable) { }
    }

    /** Napaka med delom: pokvarjena baza -> znova; drugo (odklop USB) -> klicatelj preide na notranji. */
    private fun napaka(e: Throwable) {
        if (e is android.database.sqlite.SQLiteDatabaseCorruptException) {
            android.util.Log.w("SafeerOsMedia", "predpomnilnik: pokvarjena baza ${datoteka.name}, ustvarim znova")
            synchronized(zaklep) { zbrisi() }
        } else {
            synchronized(zaklep) { try { baza?.close() } catch (_: Throwable) { }; baza = null }
            obNapaki()
        }
    }

    /** (ustvarjeno, bajti) ali null (ni, zastarel, napaka, glavna nit). */
    fun daj(kljuc: String): Pair<Long, ByteArray>? {
        if (glavna()) return null
        val b = odpri() ?: return null
        val zdaj = System.currentTimeMillis()
        return try {
            b.rawQuery("SELECT bajti, datoteka, ustvarjeno, zadnja_uporaba, poteka FROM vnosi WHERE kljuc = ?", arrayOf(kljuc)).use { c ->
                if (!c.moveToFirst() || c.getLong(4) <= zdaj) return null
                val bajti = if (!c.isNull(0)) c.getBlob(0) else {
                    val f = File(mapaVelikih, c.getString(1) ?: return null)
                    if (!f.isFile) return null
                    f.readBytes()
                }
                if (PredpomnilnikPravila.osveziUporabo(c.getLong(3), zdaj)) pisi { it.execSQL("UPDATE vnosi SET zadnja_uporaba = ? WHERE kljuc = ?", arrayOf<Any>(zdaj, kljuc)) }
                c.getLong(2) to bajti
            }
        } catch (e: Throwable) { napaka(e); null }
    }

    /** Shrani v ozadju (ena nit za pisanje); [ustvarjeno] = cas vsebine (za veljavnost pri branju). */
    fun shrani(kljuc: String, vrsta: String, bajti: ByteArray, ustvarjeno: Long = System.currentTimeMillis(), poZapisu: (Long) -> Unit = {}) {
        if (bajti.isEmpty()) return
        pisi { b ->
            val zdaj = System.currentTimeMillis()
            val v = ContentValues().apply {
                put("kljuc", kljuc); put("vrsta", vrsta); put("velikost", bajti.size.toLong())
                put("ustvarjeno", ustvarjeno); put("zadnja_uporaba", zdaj); put("poteka", PredpomnilnikPravila.poteka(vrsta, ustvarjeno))
            }
            val ime = PredpomnilnikPravila.imeVelike(kljuc)
            if (bajti.size > PredpomnilnikPravila.BLOB_NAJVEC) {
                mapaVelikih.mkdirs()
                val f = File(mapaVelikih, ime)
                val tmp = File(mapaVelikih, "$ime.tmp")
                tmp.writeBytes(bajti)
                if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.delete(); return@pisi } }
                v.putNull("bajti"); v.put("datoteka", ime)
            } else {
                v.put("bajti", bajti); v.putNull("datoteka")
                File(mapaVelikih, ime).takeIf { it.exists() }?.delete()
            }
            b.insertWithOnConflict("vnosi", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            poZapisu(bajti.size.toLong())
        }
    }

    fun pozabi(kljuc: String) = pisi { b ->
        b.rawQuery("SELECT datoteka FROM vnosi WHERE kljuc = ?", arrayOf(kljuc)).use { c -> if (c.moveToFirst() && !c.isNull(0)) File(mapaVelikih, c.getString(0)).delete() }
        b.delete("vnosi", "kljuc = ?", arrayOf(kljuc))
    }

    /** Skupna velikost vnosov (bajti), za prikaz zasedenosti; klic iz ozadja. */
    fun velikost(): Long {
        if (glavna()) return 0L
        val b = odpri() ?: return 0L
        return try { b.rawQuery("SELECT COALESCE(SUM(velikost), 0) FROM vnosi", null).use { if (it.moveToFirst()) it.getLong(0) else 0L } }
            catch (e: Throwable) { napaka(e); 0L }
    }

    /** TTL in LRU do [meja] bajtov ([PredpomnilnikPravila.zaBrisanjeVnosov]); v niti za pisanje. */
    fun pocisti(meja: Long) = pisi { b ->
        val zdaj = System.currentTimeMillis()
        val vnosi = ArrayList<PredpomnilnikPravila.Vnos>()
        val datoteke = HashMap<String, String>()
        b.rawQuery("SELECT kljuc, velikost, zadnja_uporaba, poteka, datoteka FROM vnosi", null).use { c ->
            while (c.moveToNext()) {
                vnosi += PredpomnilnikPravila.Vnos(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3))
                if (!c.isNull(4)) datoteke[c.getString(0)] = c.getString(4)
            }
        }
        val ven = PredpomnilnikPravila.zaBrisanjeVnosov(vnosi, meja, zdaj)
        if (ven.isEmpty()) return@pisi
        b.beginTransaction()
        try {
            for (k in ven) { b.delete("vnosi", "kljuc = ?", arrayOf(k)); datoteke[k]?.let { File(mapaVelikih, it).delete() } }
            b.setTransactionSuccessful()
        } finally { b.endTransaction() }
        android.util.Log.i("SafeerOsMedia", "predpomnilnik ${datoteka.name}: pocisceno ${ven.size} od ${vnosi.size} vnosov")
    }

    /** »Pocisti predpomnilnik«: vse vnose in velike datoteke, nato VACUUM (datoteka se skrci). */
    fun izprazni() = pisi { b ->
        b.delete("vnosi", null, null)
        try { mapaVelikih.deleteRecursively() } catch (_: Throwable) { }
        b.execSQL("VACUUM")
    }

    /** Baza je na mediju, ki ga ni vec (odklop) ali je zamenjana: zapri. */
    fun zapri() { synchronized(zaklep) { try { baza?.close() } catch (_: Throwable) { }; baza = null } }

    /** Delo na niti za pisanje; napaka ne podre procesa. */
    private fun pisi(delo: (SQLiteDatabase) -> Unit) {
        try {
            pisalec.execute {
                val b = odpri() ?: return@execute
                try { delo(b) } catch (e: Throwable) { napaka(e) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
}
