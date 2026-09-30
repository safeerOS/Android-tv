package si.safeer.tv.os

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Krajevna hramba Sporocil (kanali, pogovori, sporocila). Brez skrivnosti - te so v SporocilaSkrivnosti. */
class SporocilaShramba(c: Context) : SQLiteOpenHelper(c.applicationContext, "sporocila.db", null, 2) {

    data class Kanal(val id: String, val vrsta: String, val ime: String, val stanje: String, val nastavitve: JSONObject)
    data class Pogovor(val id: String, val kanalId: String, val oseba: String, val ime: String, val zadeva: String,
                       val zadnje: String, val neprebrano: Int, val cas: String)
    data class Sporocilo(val id: String, val pogovorId: String, val smer: String, val besedilo: String, val cas: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE kanali(id TEXT PRIMARY KEY, vrsta TEXT, ime TEXT, stanje TEXT, nastavitve TEXT, stanje_kanala TEXT)")
        db.execSQL("CREATE TABLE pogovori(id TEXT, kanal_id TEXT, oseba TEXT, ime TEXT, zadeva TEXT, zadnje TEXT, neprebrano INTEGER, cas TEXT, PRIMARY KEY(id, kanal_id))")
        db.execSQL("CREATE TABLE sporocila(id TEXT, kanal_id TEXT, pogovor_id TEXT, smer TEXT, besedilo TEXT, cas TEXT, PRIMARY KEY(id, kanal_id))")
        osebeTabele(db)
    }

    /** v2: osebe (zdruzene identitete + lastno ime) in oznake pogovorov - vse lokalno, uporabnikovo. */
    private fun osebeTabele(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS identitete(kljuc TEXT PRIMARY KEY, oseba TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS osebe(id TEXT PRIMARY KEY, lastno_ime TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS oznake(kanal_id TEXT, pogovor_id TEXT, oznaka TEXT, PRIMARY KEY(kanal_id, pogovor_id, oznaka))")
    }

    override fun onUpgrade(db: SQLiteDatabase, stara: Int, nova: Int) { if (stara < 2) osebeTabele(db) }

    // ------------------------------------------------------------------ osebe (uporabnikovo urejanje)
    /** Kljuc identitete pogovora: ista oseba na razlicnih kanalih dobi isti kljuc (e-posta ne glede na velikost crk). */
    fun kljucIdentitete(vrstaKanala: String, p: Pogovor): String {
        val o = p.oseba.trim()
        return when {
            o.contains("@") && !o.startsWith("@") -> "email:" + o.lowercase()
            o.startsWith("+") -> "telefon:" + o.filter { it.isDigit() || it == '+' }
            vrstaKanala == "matrix" -> "matrix:" + o
            vrstaKanala == "telegram_bot" -> "tg:" + o
            else -> vrstaKanala + ":" + o
        }
    }

    /** Oseba, h kateri spada identiteta (po zdruzitvi), sicer identiteta sama. */
    fun osebaZa(kljuc: String): String = readableDatabase.rawQuery("SELECT oseba FROM identitete WHERE kljuc=?", arrayOf(kljuc)).use {
        if (it.moveToFirst()) it.getString(0) ?: kljuc else kljuc
    }

    fun lastnoIme(oseba: String): String = readableDatabase.rawQuery("SELECT lastno_ime FROM osebe WHERE id=?", arrayOf(oseba)).use {
        if (it.moveToFirst()) it.getString(0) ?: "" else ""
    }

    fun preimenujOsebo(oseba: String, ime: String) {
        if (ime.isBlank()) writableDatabase.delete("osebe", "id=?", arrayOf(oseba))
        else writableDatabase.insertWithOnConflict("osebe", null, ContentValues().apply { put("id", oseba); put("lastno_ime", ime.trim()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Vse identitete osebe [drugi] se preselijo k [cilj]; lastno ime cilja ostane. */
    fun zdruziOsebi(cilj: String, drugi: String, kljuciDrugega: List<String>) {
        if (cilj == drugi) return
        val db = writableDatabase; db.beginTransaction()
        try {
            for (k in kljuciDrugega) db.insertWithOnConflict("identitete", null, ContentValues().apply { put("kljuc", k); put("oseba", cilj) }, SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL("UPDATE identitete SET oseba=? WHERE oseba=?", arrayOf(cilj, drugi))
            if (lastnoIme(cilj).isBlank()) lastnoIme(drugi).takeIf { it.isNotBlank() }?.let { preimenujOsebo(cilj, it) }
            db.delete("osebe", "id=?", arrayOf(drugi))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun razdruzi(kljuc: String) { writableDatabase.delete("identitete", "kljuc=?", arrayOf(kljuc)) }

    // ------------------------------------------------------------------ oznake
    fun oznake(kanalId: String, pogovorId: String): List<String> = readableDatabase.rawQuery(
        "SELECT oznaka FROM oznake WHERE kanal_id=? AND pogovor_id=? ORDER BY oznaka COLLATE NOCASE", arrayOf(kanalId, pogovorId)).use { k ->
        buildList { while (k.moveToNext()) add(k.getString(0)) }
    }

    fun vseOznake(): List<String> = readableDatabase.rawQuery("SELECT DISTINCT oznaka FROM oznake ORDER BY oznaka COLLATE NOCASE", null).use { k ->
        buildList { while (k.moveToNext()) add(k.getString(0)) }
    }

    fun nastaviOznake(kanalId: String, pogovorId: String, oznake: List<String>) {
        val db = writableDatabase; db.beginTransaction()
        try {
            db.delete("oznake", "kanal_id=? AND pogovor_id=?", arrayOf(kanalId, pogovorId))
            for (o in oznake.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
                db.insertWithOnConflict("oznake", null, ContentValues().apply { put("kanal_id", kanalId); put("pogovor_id", pogovorId); put("oznaka", o) }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    data class Zadetek(val sporocilo: Sporocilo, val kanalId: String, val izsek: String)

    /** Iskanje po besedilu sporocil v danih pogovorih (npr. vsi pogovori ene osebe); najnovejsi najprej. */
    fun isciSporocila(niz: String, pogovori: List<Pogovor>, najvec: Int = 60): List<Zadetek> {
        val n = niz.trim(); if (n.isEmpty() || pogovori.isEmpty()) return emptyList()
        val vzorec = "%" + n.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val pogoj = pogovori.joinToString(" OR ") { "(kanal_id=? AND pogovor_id=?)" }
        val args = arrayOf(vzorec) + pogovori.flatMap { listOf(it.kanalId, it.id) } + najvec.toString()
        return readableDatabase.rawQuery("SELECT id,pogovor_id,smer,besedilo,cas,kanal_id FROM sporocila WHERE besedilo LIKE ? ESCAPE '\\' AND ($pogoj) ORDER BY cas DESC, rowid DESC LIMIT ?", args).use { k ->
            buildList { while (k.moveToNext()) {
                val b = k.getString(3) ?: ""; val i = b.lowercase().indexOf(n.lowercase()); val zac = if (i > 40) i - 40 else 0
                add(Zadetek(Sporocilo(k.getString(0), k.getString(1), k.getString(2), b, k.getString(4) ?: ""), k.getString(5),
                    (if (zac > 0) "…" else "") + b.substring(zac, minOf(b.length, zac + 160)) + (if (b.length > zac + 160) "…" else "")))
            } }
        }
    }

    fun kanali(): List<Kanal> = readableDatabase.rawQuery("SELECT id,vrsta,ime,stanje,nastavitve FROM kanali ORDER BY ime", null).use { k ->
        buildList { while (k.moveToNext()) add(Kanal(k.getString(0), k.getString(1), k.getString(2), k.getString(3) ?: "",
            try { JSONObject(k.getString(4) ?: "{}") } catch (_: Throwable) { JSONObject() })) }
    }

    fun dodajKanal(k: Kanal) {
        writableDatabase.insertWithOnConflict("kanali", null, ContentValues().apply {
            put("id", k.id); put("vrsta", k.vrsta); put("ime", k.ime); put("stanje", k.stanje); put("nastavitve", k.nastavitve.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun stanje(id: String, stanje: String) {
        writableDatabase.execSQL("UPDATE kanali SET stanje=? WHERE id=?", arrayOf(stanje, id))
    }

    fun stanjeKanala(id: String): JSONObject = readableDatabase.rawQuery("SELECT stanje_kanala FROM kanali WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) try { JSONObject(it.getString(0) ?: "{}") } catch (_: Throwable) { JSONObject() } else JSONObject()
    }

    fun shraniStanjeKanala(id: String, s: JSONObject) {
        writableDatabase.execSQL("UPDATE kanali SET stanje_kanala=? WHERE id=?", arrayOf(s.toString(), id))
    }

    fun odstraniKanal(id: String) {
        writableDatabase.apply {
            beginTransaction()
            try {
                delete("kanali", "id=?", arrayOf(id)); delete("pogovori", "kanal_id=?", arrayOf(id))
                delete("sporocila", "kanal_id=?", arrayOf(id)); setTransactionSuccessful()
            } finally { endTransaction() }
        }
    }

    /** Nov/posodobljen pogovor: neprebrana se pristejejo, zadnje sporocilo se zamenja. */
    fun posodobiPogovor(p: Pogovor, pristej: Boolean) {
        val db = writableDatabase
        val prej = db.rawQuery("SELECT neprebrano, ime FROM pogovori WHERE id=? AND kanal_id=?", arrayOf(p.id, p.kanalId)).use {
            if (it.moveToFirst()) it.getInt(0) to (it.getString(1) ?: "") else 0 to ""
        }
        db.insertWithOnConflict("pogovori", null, ContentValues().apply {
            put("id", p.id); put("kanal_id", p.kanalId); put("oseba", p.oseba); put("ime", p.ime.ifBlank { prej.second })
            put("zadeva", p.zadeva); put("zadnje", p.zadnje); put("cas", p.cas)
            put("neprebrano", p.neprebrano + if (pristej) prej.first else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun shraniSporocilo(kanalId: String, s: Sporocilo) {
        writableDatabase.insertWithOnConflict("sporocila", null, ContentValues().apply {
            put("id", s.id); put("kanal_id", kanalId); put("pogovor_id", s.pogovorId); put("smer", s.smer)
            put("besedilo", s.besedilo); put("cas", s.cas)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Pogovor [od] se pridruzi pogovoru [v] (ista naprava pod drugim id): sporocila se preselijo,
     * neprebrana se sestejejo, ostane novejse zadnje sporocilo. Vrne true, ce se je kaj spremenilo.
     */
    fun zdruziPogovor(kanalId: String, od: String, v: String, ime: String): Boolean {
        if (od == v) return false
        val vsi = pogovori().filter { it.kanalId == kanalId }
        val star = vsi.firstOrNull { it.id == od } ?: return false
        val cilj = vsi.firstOrNull { it.id == v }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE OR REPLACE sporocila SET pogovor_id=? WHERE kanal_id=? AND pogovor_id=?", arrayOf(v, kanalId, od))
            db.delete("pogovori", "id=? AND kanal_id=?", arrayOf(od, kanalId))
            val novejsi = if (cilj == null || star.cas > cilj.cas) star else cilj
            db.insertWithOnConflict("pogovori", null, ContentValues().apply {
                put("id", v); put("kanal_id", kanalId); put("oseba", v); put("ime", ime.ifBlank { cilj?.ime ?: star.ime })
                put("zadeva", ""); put("zadnje", novejsi.zadnje); put("cas", novejsi.cas)
                put("neprebrano", star.neprebrano + (cilj?.neprebrano ?: 0))
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return true
    }

    fun preimenujPogovor(kanalId: String, id: String, ime: String) {
        writableDatabase.execSQL("UPDATE pogovori SET ime=? WHERE id=? AND kanal_id=?", arrayOf(ime, id, kanalId))
    }

    fun oznaciPrebrano(kanalId: String, pogovorId: String) {
        writableDatabase.execSQL("UPDATE pogovori SET neprebrano=0 WHERE id=? AND kanal_id=?", arrayOf(pogovorId, kanalId))
    }

    fun posodobiZadnje(kanalId: String, pogovorId: String, besedilo: String, cas: String) {
        writableDatabase.execSQL("UPDATE pogovori SET zadnje=?, cas=? WHERE id=? AND kanal_id=?",
            arrayOf(besedilo.take(240), cas, pogovorId, kanalId))
    }

    fun pogovori(): List<Pogovor> = readableDatabase.rawQuery(
        "SELECT id,kanal_id,oseba,ime,zadeva,zadnje,neprebrano,cas FROM pogovori ORDER BY cas DESC", null).use { k ->
        buildList { while (k.moveToNext()) add(Pogovor(k.getString(0), k.getString(1), k.getString(2), k.getString(3) ?: "",
            k.getString(4) ?: "", k.getString(5) ?: "", k.getInt(6), k.getString(7) ?: "")) }
    }

    /** Enak cas (e-posta ima natancnost sekunde): vrstni red prihoda (rowid) odloci. */
    fun sporocila(kanalId: String, pogovorId: String, najvec: Int = 200): List<Sporocilo> = readableDatabase.rawQuery(
        "SELECT id,pogovor_id,smer,besedilo,cas FROM sporocila WHERE kanal_id=? AND pogovor_id=? ORDER BY cas DESC, rowid DESC LIMIT ?",
        arrayOf(kanalId, pogovorId, najvec.toString())).use { k ->
        buildList { while (k.moveToNext()) add(Sporocilo(k.getString(0), k.getString(1), k.getString(2), k.getString(3) ?: "", k.getString(4) ?: "")) }
    }.reversed()
}
