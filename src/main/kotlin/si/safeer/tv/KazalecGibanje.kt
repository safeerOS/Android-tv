package si.safeer.tv

/**
 * Gibanje navideznega kazalca na TV kot cista funkcija casa (Ai Lina tv browser, 14. 11. 2026).
 * Zakaj: stari kazalec je bil vezan na ponavljanje tipke (skoki 24-110 px na dogodek) in je lastnik do roba rabil
 * prevec pritiskov. Tu: prvi pritisk = natancen korak, drzanje = gibanje po slicicah s pospeskom (zaprta oblika poti,
 * zato enaka pot pri 30 in 60 slicicah/s), stran drsi samo s potiskom ob rob. Vse stevilke so parametri, ker se obcutek
 * da izmeriti samo na pravem TV. Brez Androida, brez ure sistema, brez stanja zunaj [Stanje]. Preizkus: tests/KazalecGibanjeTest.kt.
 */
object KazalecGibanje {
    const val LEVO = 1; const val DESNO = 2; const val GOR = 4; const val DOL = 8

    data class Nastavitve(
        val korak: Float = 24f,
        val zamikDrzanjaMs: Long = 160,
        val zacetnaHitrost: Float = 240f,
        val najvecjaHitrost: Float = 3300f, // Hi Browser 5.5 na TV ~3300 px/s pri 1920 (meritev 14. 11. 2026, ogled/hi-kazalec-1411)
        val pospesevanjeMs: Long = 600,
        val korakDrsenja: Float = 120f,
        val zacetnoDrsenje: Float = 600f,
        val najvecjeDrsenje: Float = 2400f,
        val pospesevanjeDrsenjaMs: Long = 800,
        val rokPrvePonovitveMs: Long = 700,
        val rokPonovitveMs: Long = 200,
    )

    fun hitrost(stopnja: Int): Nastavitve {
        return when (stopnja) {
            0 -> Nastavitve(zacetnaHitrost = 180f, najvecjaHitrost = 2200f, pospesevanjeMs = 800)
            2 -> Nastavitve(zacetnaHitrost = 320f, najvecjaHitrost = 4400f, pospesevanjeMs = 500)
            else -> Nastavitve()
        }
    }

    data class Stanje(
        val x: Float, 
        val y: Float, 
        val smeri: Int = 0,
        val zadnjiCasMs: Long = 0L,
        val zacetekDrzanjaMs: Long = 0L,
        val startDrsenjaXMs: Long = -1L,
        val startDrsenjaYMs: Long = -1L,
        val zadnjiDogodekTipkeMs: Long = 0L,
        val jeBilaPonovitev: Boolean = false
    )

    data class Izid(val stanje: Stanje, val drsiX: Float, val drsiY: Float, val tece: Boolean)

    private fun pot(t_ms: Long, v0: Float, vMax: Float, T_ms: Long): Float {
        if (t_ms <= 0L) return 0f
        val t = t_ms / 1000f
        val t_posp = T_ms / 1000f
        if (t <= t_posp) {
            val a = (vMax - v0) / t_posp
            return v0 * t + 0.5f * a * t * t
        } else {
            val potPosp = v0 * t_posp + 0.5f * (vMax - v0) * t_posp
            return potPosp + vMax * (t - t_posp)
        }
    }

    private fun inverzPot(d: Float, v0: Float, vMax: Float, T_ms: Long): Float {
        if (d <= 0f) return 0f
        val t_posp = T_ms / 1000f
        val potPosp = v0 * t_posp + 0.5f * (vMax - v0) * t_posp
        if (d <= potPosp) {
            val a = (vMax - v0) / t_posp
            if (a == 0f) return d / v0
            val diskriminanta = v0 * v0 + 2f * a * d
            if (diskriminanta < 0f) return 0f
            return (-v0 + kotlin.math.sqrt(diskriminanta)) / a
        } else {
            val preostanek = d - potPosp
            return t_posp + preostanek / vMax
        }
    }

    fun pritisk(s: Stanje, smer: Int, ponovitev: Boolean, zdajMs: Long, sirina: Int, visina: Int, n: Nastavitve = Nastavitve()): Izid {
        if (sirina <= 0 || visina <= 0) return Izid(s.copy(x = 0f, y = 0f), 0f, 0f, false)
        // Cas dogodka tipke je lahko starejsi od zadnje slicice: ura stanja ne gre nikoli nazaj, sicer bi isti odsek
        // poti stel dvakrat.
        val zdaj = if (zdajMs > s.zadnjiCasMs) zdajMs else s.zadnjiCasMs

        val vmesni = if (zdaj > s.zadnjiCasMs) tik(s, zdaj, sirina, visina, n) else Izid(s, 0f, 0f, s.smeri != 0)
        var st = vmesni.stanje
        
        val bilaZeta = (st.smeri and smer) == smer
        val noveSmeri = st.smeri or smer
        
        var dodatenDrsiX = 0f
        var dodatenDrsiY = 0f
        
        if (!ponovitev && !bilaZeta) {
            val zacetek = if (st.smeri == 0) zdaj else st.zacetekDrzanjaMs
            st = st.copy(
                smeri = noveSmeri,
                zacetekDrzanjaMs = zacetek,
                zadnjiDogodekTipkeMs = zdaj,
                jeBilaPonovitev = false,
                zadnjiCasMs = zdaj
            )
            
            var korakSmerX = 0
            if ((smer and LEVO) != 0) korakSmerX -= 1
            if ((smer and DESNO) != 0) korakSmerX += 1
            
            var korakSmerY = 0
            if ((smer and GOR) != 0) korakSmerY -= 1
            if ((smer and DOL) != 0) korakSmerY += 1
            
            val stariX = st.x
            val stariY = st.y
            
            var novX = stariX + korakSmerX * n.korak
            var novY = stariY + korakSmerY * n.korak
            
            if (korakSmerX > 0 && stariX >= sirina - 1f) dodatenDrsiX = n.korakDrsenja
            if (korakSmerX < 0 && stariX <= 0f) dodatenDrsiX = -n.korakDrsenja
            if (korakSmerY > 0 && stariY >= visina - 1f) dodatenDrsiY = n.korakDrsenja
            if (korakSmerY < 0 && stariY <= 0f) dodatenDrsiY = -n.korakDrsenja
            
            novX = kotlin.math.max(0f, kotlin.math.min(sirina - 1f, novX))
            novY = kotlin.math.max(0f, kotlin.math.min(visina - 1f, novY))
            
            // Smer, pritisnjena v rob, na katerem kazalec ze stoji: zvezno drsenje te osi zacne po zamiku OD TEGA pritiska.
            // Brez tega bi druga smer med dolgim drzanjem prve takoj drsela z najvecjo hitrostjo.
            val drsenjeOd = zdaj + n.zamikDrzanjaMs
            st = st.copy(
                x = novX, y = novY,
                startDrsenjaXMs = if (dodatenDrsiX != 0f) drsenjeOd else st.startDrsenjaXMs,
                startDrsenjaYMs = if (dodatenDrsiY != 0f) drsenjeOd else st.startDrsenjaYMs
            )
        } else {
            // Ponovitev smeri, ki ni vec drzana (po ustavi() ali po varovalu): tipka je se pritisnjena, a brez koraka in
            // z uro drzanja od zdaj - sicer bi kazalec po vrnitvi fokusa stekel z najvecjo hitrostjo.
            val zacetek = if (st.smeri == 0) zdaj else st.zacetekDrzanjaMs
            st = st.copy(
                smeri = noveSmeri,
                zacetekDrzanjaMs = zacetek,
                zadnjiDogodekTipkeMs = zdaj,
                jeBilaPonovitev = ponovitev || st.jeBilaPonovitev,
                zadnjiCasMs = zdaj
            )
        }
        
        return Izid(st, vmesni.drsiX + dodatenDrsiX, vmesni.drsiY + dodatenDrsiY, true)
    }

    fun spust(s: Stanje, smer: Int, zdajMs: Long, sirina: Int, visina: Int, n: Nastavitve = Nastavitve()): Izid {
        if (sirina <= 0 || visina <= 0) return Izid(s.copy(x = 0f, y = 0f), 0f, 0f, false)
        val zdaj = if (zdajMs > s.zadnjiCasMs) zdajMs else s.zadnjiCasMs   // ura ne gre nazaj (glej pritisk)
        val vmesni = if (zdaj > s.zadnjiCasMs) tik(s, zdaj, sirina, visina, n) else Izid(s, 0f, 0f, s.smeri != 0)
        val noveSmeri = vmesni.stanje.smeri and smer.inv()
        val novStanje = vmesni.stanje.copy(smeri = noveSmeri, zadnjiCasMs = zdaj)
        return Izid(novStanje, vmesni.drsiX, vmesni.drsiY, noveSmeri != 0)
    }

    fun ustavi(s: Stanje): Stanje {
        return s.copy(smeri = 0)
    }

    fun tik(s: Stanje, zdajMs: Long, sirina: Int, visina: Int, n: Nastavitve = Nastavitve()): Izid {
        if (sirina <= 0 || visina <= 0) return Izid(s.copy(x = 0f, y = 0f), 0f, 0f, false)
        if (zdajMs <= s.zadnjiCasMs) return Izid(s, 0f, 0f, s.smeri != 0)
        
        var efektivniZdaj = zdajMs
        var iztekelRok = false
        
        if (s.smeri != 0) {
            val rok = if (s.jeBilaPonovitev) n.rokPonovitveMs else n.rokPrvePonovitveMs
            val iztekMs = s.zadnjiDogodekTipkeMs + rok
            if (zdajMs > iztekMs) {
                efektivniZdaj = iztekMs
                iztekelRok = true
            }
        }
        
        if (efektivniZdaj <= s.zadnjiCasMs) {
            val novS = if (iztekelRok) s.copy(smeri = 0, zadnjiCasMs = zdajMs) else s.copy(zadnjiCasMs = zdajMs)
            return Izid(novS, 0f, 0f, novS.smeri != 0)
        }
        
        val t1 = s.zadnjiCasMs - s.zacetekDrzanjaMs - n.zamikDrzanjaMs
        val t2 = efektivniZdaj - s.zacetekDrzanjaMs - n.zamikDrzanjaMs
        val dp = pot(t2, n.zacetnaHitrost, n.najvecjaHitrost, n.pospesevanjeMs) - 
                 pot(t1, n.zacetnaHitrost, n.najvecjaHitrost, n.pospesevanjeMs)
                 
        var smerX = 0
        if ((s.smeri and LEVO) != 0) smerX -= 1
        if ((s.smeri and DESNO) != 0) smerX += 1
        
        var smerY = 0
        if ((s.smeri and GOR) != 0) smerY -= 1
        if ((s.smeri and DOL) != 0) smerY += 1
        
        val faktor = if (smerX != 0 && smerY != 0) 0.70710678f else 1.0f
        
        val stariX = s.x
        var novX = stariX + dp * faktor * smerX
        var startDrsenjaXMs = s.startDrsenjaXMs
        
        if (smerX > 0 && novX >= sirina - 1f) {
            if (stariX < sirina - 1f) {
                val d = (sirina - 1f - stariX) / (faktor * smerX)
                val th = inverzPot(pot(t1, n.zacetnaHitrost, n.najvecjaHitrost, n.pospesevanjeMs) + d, n.zacetnaHitrost, n.najvecjaHitrost, n.pospesevanjeMs)
                startDrsenjaXMs = s.zacetekDrzanjaMs + n.zamikDrzanjaMs + (th * 1000f).toLong()
            } else if (startDrsenjaXMs == -1L) {
                startDrsenjaXMs = s.zadnjiCasMs
            }
            novX = sirina - 1f
        } else if (smerX < 0 && novX <= 0f) {
            if (stariX > 0f) {
                val d = (0f - stariX) / (faktor * smerX)
                val th = inverzPot(pot(t1, n.zacetnaHitrost, n.najvecjaHitrost, n.pospesevanjeMs) + d, n.zacetnaHitrost, n.najvecjaHitrost, n.pospesevanjeMs)
                startDrsenjaXMs = s.zacetekDrzanjaMs + n.zamikDrzanjaMs + (th * 1000f).toLong()
            } else if (startDrsenjaXMs == -1L) {
                startDrsenjaXMs = s.zadnjiCasMs
            }
            novX = 0f
        } else {
            startDrsenjaXMs = -1L
        }
        
        val stariY = s.y
        var novY = stariY + dp * faktor * smerY
        var startDrsenjaYMs = s.startDrsenjaYMs
        
        if (smerY > 0 && novY >= visina - 1f) {
            if (stariY < visina - 1f) {
                val d = (visina - 1f - stariY) / (faktor * smerY)
                val th = inverzPot(pot(t1, n.zacetnaHitrost, n.najvecjaHitrost, n.pospesevanjeMs) + d, n.zacetnaHitrost, n.najvecjaHitrost, n.pospesevanjeMs)
                startDrsenjaYMs = s.zacetekDrzanjaMs + n.zamikDrzanjaMs + (th * 1000f).toLong()
            } else if (startDrsenjaYMs == -1L) {
                startDrsenjaYMs = s.zadnjiCasMs
            }
            novY = visina - 1f
        } else if (smerY < 0 && novY <= 0f) {
            if (stariY > 0f) {
                val d = (0f - stariY) / (faktor * smerY)
                val th = inverzPot(pot(t1, n.zacetnaHitrost, n.najvecjaHitrost, n.pospesevanjeMs) + d, n.zacetnaHitrost, n.najvecjaHitrost, n.pospesevanjeMs)
                startDrsenjaYMs = s.zacetekDrzanjaMs + n.zamikDrzanjaMs + (th * 1000f).toLong()
            } else if (startDrsenjaYMs == -1L) {
                startDrsenjaYMs = s.zadnjiCasMs
            }
            novY = 0f
        } else {
            startDrsenjaYMs = -1L
        }
        
        var drsiX = 0f
        if (startDrsenjaXMs != -1L) {
            val t1s = s.zadnjiCasMs - startDrsenjaXMs
            val t2s = efektivniZdaj - startDrsenjaXMs
            val ds = pot(t2s, n.zacetnoDrsenje, n.najvecjeDrsenje, n.pospesevanjeDrsenjaMs) - 
                     pot(t1s, n.zacetnoDrsenje, n.najvecjeDrsenje, n.pospesevanjeDrsenjaMs)
            drsiX = ds * faktor * smerX
        }
        
        var drsiY = 0f
        if (startDrsenjaYMs != -1L) {
            val t1s = s.zadnjiCasMs - startDrsenjaYMs
            val t2s = efektivniZdaj - startDrsenjaYMs
            val ds = pot(t2s, n.zacetnoDrsenje, n.najvecjeDrsenje, n.pospesevanjeDrsenjaMs) - 
                     pot(t1s, n.zacetnoDrsenje, n.najvecjeDrsenje, n.pospesevanjeDrsenjaMs)
            drsiY = ds * faktor * smerY
        }
        
        val koncniSmeri = if (iztekelRok) 0 else s.smeri
        
        return Izid(
            stanje = s.copy(
                x = novX, 
                y = novY, 
                zadnjiCasMs = zdajMs,
                startDrsenjaXMs = startDrsenjaXMs,
                startDrsenjaYMs = startDrsenjaYMs,
                smeri = koncniSmeri
            ),
            drsiX = drsiX,
            drsiY = drsiY,
            tece = koncniSmeri != 0
        )
    }
}
