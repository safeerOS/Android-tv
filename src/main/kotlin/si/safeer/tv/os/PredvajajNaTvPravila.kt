package si.safeer.tv.os

/**
 * »Predvajaj na TV« (lastnik 10. 11. 2026: »da lahko iz telefona in racunalnika izberem, kateri film zelim na TV gledati«).
 * Posiljatelj poslje samo IDENTITETO vsebine, TV jo predvaja z lastnimi viri. Sporocilo pride z druge naprave in je
 * nezaupanja vredno: tu ne sprejmemo nobenega naslova, poti ali ukaza - samo ozko preverjena polja, in se to sele, ko je
 * TV sama ugotovila, da je posiljatelj v ozjem krogu (vOzjemKrogu pride od TV, nikoli iz sporocila). Naprava zunaj kroga
 * dobi vedno isti odgovor, da iz razloga zavrnitve ne izve nicesar o vsebini.
 * Ista pravila za posiljatelje na namizju: assets/os_skupno/predvajaj_na_tv_pravila.js - spreminjaj skupaj.
 */
object PredvajajNaTvPravila {
    enum class Vrsta { FILM, EPIZODA, KANAL }
    data class Zahteva(val vrsta: Vrsta, val id: String, val sezona: Int?, val epizoda: Int?, val naslov: String, val polozajMs: Long)
    enum class Zavrnitev { NI_V_KROGU, NEZNANA_VRSTA, NEVELJAVEN_ID, NEVELJAVNA_EPIZODA, PREDOLGO, NEVELJAVEN_POLOZAJ }
    sealed class Izid {
        data class Sprejeto(val zahteva: Zahteva) : Izid()
        data class Zavrnjeno(val razlog: Zavrnitev) : Izid()
    }

    fun preveri(vOzjemKrogu: Boolean, polja: Map<String, String?>): Izid {
        if (!vOzjemKrogu) return Izid.Zavrnjeno(Zavrnitev.NI_V_KROGU)

        val vrstaStr = polja["vrsta"]
        val vrsta = when (vrstaStr) {
            "film" -> Vrsta.FILM
            "epizoda" -> Vrsta.EPIZODA
            "kanal" -> Vrsta.KANAL
            else -> return Izid.Zavrnjeno(Zavrnitev.NEZNANA_VRSTA)
        }

        val idStr = polja["id"]
        if (idStr == null) return Izid.Zavrnjeno(Zavrnitev.NEVELJAVEN_ID)
        
        if (vrsta == Vrsta.FILM || vrsta == Vrsta.EPIZODA) {
            if (idStr.length !in 9..12 || !idStr.startsWith("tt")) return Izid.Zavrnjeno(Zavrnitev.NEVELJAVEN_ID)
            for (i in 2 until idStr.length) {
                if (idStr[i] !in '0'..'9') return Izid.Zavrnjeno(Zavrnitev.NEVELJAVEN_ID)
            }
        } else { // KANAL
            if (idStr.isEmpty() || idStr.length > 64) return Izid.Zavrnjeno(Zavrnitev.NEVELJAVEN_ID)
            if (idStr.startsWith(".")) return Izid.Zavrnjeno(Zavrnitev.NEVELJAVEN_ID)
            if (idStr.contains("..")) return Izid.Zavrnjeno(Zavrnitev.NEVELJAVEN_ID)
            for (i in 0 until idStr.length) {
                val c = idStr[i]
                if (!(c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '-')) {
                    return Izid.Zavrnjeno(Zavrnitev.NEVELJAVEN_ID)
                }
            }
        }

        var sezona: Int? = null
        var epizoda: Int? = null
        if (vrsta == Vrsta.EPIZODA) {
            val sezonaStr = polja["sezona"]
            if (sezonaStr == null || sezonaStr.isEmpty() || sezonaStr.length > 3) return Izid.Zavrnjeno(Zavrnitev.NEVELJAVNA_EPIZODA)
            for (i in 0 until sezonaStr.length) {
                if (sezonaStr[i] !in '0'..'9') return Izid.Zavrnjeno(Zavrnitev.NEVELJAVNA_EPIZODA)
            }
            sezona = sezonaStr.toIntOrNull()
            if (sezona == null || sezona !in 0..999) return Izid.Zavrnjeno(Zavrnitev.NEVELJAVNA_EPIZODA)

            val epizodaStr = polja["epizoda"]
            if (epizodaStr == null || epizodaStr.isEmpty() || epizodaStr.length > 4) return Izid.Zavrnjeno(Zavrnitev.NEVELJAVNA_EPIZODA)
            for (i in 0 until epizodaStr.length) {
                if (epizodaStr[i] !in '0'..'9') return Izid.Zavrnjeno(Zavrnitev.NEVELJAVNA_EPIZODA)
            }
            epizoda = epizodaStr.toIntOrNull()
            if (epizoda == null || epizoda !in 1..9999) return Izid.Zavrnjeno(Zavrnitev.NEVELJAVNA_EPIZODA)
        }

        val surovNaslov = polja["naslov"] ?: ""
        val naslovBuilder = StringBuilder()
        for (i in 0 until surovNaslov.length) {
            val c = surovNaslov[i]
            if (c.code >= 32 && c.code != 127) {
                naslovBuilder.append(c)
            }
        }
        val naslov = naslovBuilder.toString()
        if (naslov.length > 200) return Izid.Zavrnjeno(Zavrnitev.PREDOLGO)

        val polozajMs: Long
        if (vrsta == Vrsta.KANAL) {
            polozajMs = 0L
        } else {
            val polozajStr = polja["polozajMs"]
            if (polozajStr == null || polozajStr.isEmpty()) {
                polozajMs = 0L
            } else {
                if (polozajStr.length > 9) return Izid.Zavrnjeno(Zavrnitev.NEVELJAVEN_POLOZAJ)
                for (i in 0 until polozajStr.length) {
                    if (polozajStr[i] !in '0'..'9') return Izid.Zavrnjeno(Zavrnitev.NEVELJAVEN_POLOZAJ)
                }
                polozajMs = polozajStr.toLongOrNull() ?: return Izid.Zavrnjeno(Zavrnitev.NEVELJAVEN_POLOZAJ)
                if (polozajMs !in 0..86400000L) return Izid.Zavrnjeno(Zavrnitev.NEVELJAVEN_POLOZAJ)
            }
        }

        return Izid.Sprejeto(Zahteva(vrsta, idStr, sezona, epizoda, naslov, polozajMs))
    }

    fun sporocilo(z: Zahteva): Map<String, String> {
        val map = mutableMapOf<String, String>()
        map["vrsta"] = when (z.vrsta) {
            Vrsta.FILM -> "film"
            Vrsta.EPIZODA -> "epizoda"
            Vrsta.KANAL -> "kanal"
        }
        map["id"] = z.id
        if (z.vrsta == Vrsta.EPIZODA) {
            z.sezona?.let { map["sezona"] = it.toString() }
            z.epizoda?.let { map["epizoda"] = it.toString() }
        }
        map["naslov"] = z.naslov
        if (z.vrsta != Vrsta.KANAL) {
            map["polozajMs"] = z.polozajMs.toString()
        }
        return map
    }

    fun besediloZavrnitve(r: Zavrnitev, jezik: String): String {
        return when (jezik) {
            "sl" -> when (r) {
                Zavrnitev.NI_V_KROGU -> "Naprava ni v ožjem krogu."
                Zavrnitev.NEZNANA_VRSTA -> "Neznana vrsta vsebine."
                Zavrnitev.NEVELJAVEN_ID -> "Neveljaven identifikator vsebine."
                Zavrnitev.NEVELJAVNA_EPIZODA -> "Neveljavna sezona ali epizoda."
                Zavrnitev.PREDOLGO -> "Naslov je predolg."
                Zavrnitev.NEVELJAVEN_POLOZAJ -> "Neveljaven položaj predvajanja."
            }
            "de" -> when (r) {
                Zavrnitev.NI_V_KROGU -> "Das Gerät gehört nicht zum engen Kreis."
                Zavrnitev.NEZNANA_VRSTA -> "Unbekannter Inhaltstyp."
                Zavrnitev.NEVELJAVEN_ID -> "Ungültige Inhaltskennung."
                Zavrnitev.NEVELJAVNA_EPIZODA -> "Ungültige Staffel oder Episode."
                Zavrnitev.PREDOLGO -> "Der Titel ist zu lang."
                Zavrnitev.NEVELJAVEN_POLOZAJ -> "Ungültige Wiedergabeposition."
            }
            "es" -> when (r) {
                Zavrnitev.NI_V_KROGU -> "El dispositivo no está en el círculo íntimo."
                Zavrnitev.NEZNANA_VRSTA -> "Tipo de contenido desconocido."
                Zavrnitev.NEVELJAVEN_ID -> "Identificador de contenido no válido."
                Zavrnitev.NEVELJAVNA_EPIZODA -> "Temporada o episodio no válido."
                Zavrnitev.PREDOLGO -> "El título es demasiado largo."
                Zavrnitev.NEVELJAVEN_POLOZAJ -> "Posición de reproducción no válida."
            }
            "fr" -> when (r) {
                Zavrnitev.NI_V_KROGU -> "L'appareil ne fait pas partie du cercle restreint."
                Zavrnitev.NEZNANA_VRSTA -> "Type de contenu inconnu."
                Zavrnitev.NEVELJAVEN_ID -> "Identifiant de contenu non valide."
                Zavrnitev.NEVELJAVNA_EPIZODA -> "Saison ou épisode non valide."
                Zavrnitev.PREDOLGO -> "Le titre est trop long."
                Zavrnitev.NEVELJAVEN_POLOZAJ -> "Position de lecture non valide."
            }
            "it" -> when (r) {
                Zavrnitev.NI_V_KROGU -> "Il dispositivo non fa parte della cerchia ristretta."
                Zavrnitev.NEZNANA_VRSTA -> "Tipo di contenuto sconosciuto."
                Zavrnitev.NEVELJAVEN_ID -> "Identificatore di contenuto non valido."
                Zavrnitev.NEVELJAVNA_EPIZODA -> "Stagione o episodio non valido."
                Zavrnitev.PREDOLGO -> "Il titolo è troppo lungo."
                Zavrnitev.NEVELJAVEN_POLOZAJ -> "Posizione di riproduzione non valida."
            }
            else -> when (r) {
                Zavrnitev.NI_V_KROGU -> "Device is not in the inner circle."
                Zavrnitev.NEZNANA_VRSTA -> "Unknown content type."
                Zavrnitev.NEVELJAVEN_ID -> "Invalid content identifier."
                Zavrnitev.NEVELJAVNA_EPIZODA -> "Invalid season or episode."
                Zavrnitev.PREDOLGO -> "Title is too long."
                Zavrnitev.NEVELJAVEN_POLOZAJ -> "Invalid playback position."
            }
        }
    }
}
