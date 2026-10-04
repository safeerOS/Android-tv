package si.safeer.tv.os

fun main() {
    val p = ObnovaPravila
    // Preverjanje tece najvec slabo tretjino casa; kadar torrent kdo predvaja, vecino casa.
    check(p.TECE_MS * 2 <= p.PREMOR_MS)
    check(p.PREMOR_V_RABI_MS in 50 until p.TECE_MS)
    // Naprava ne zamuja: premor ostane najkrajsi.
    check(p.premor(p.PREMOR_MS, 0, false) == p.PREMOR_MS)
    check(p.premor(p.PREMOR_MS, p.ZAMUDA_MS, false) == p.PREMOR_MS)
    // Naprava zamuja: premor se podvoji, a ne cez zgornjo mejo.
    check(p.premor(p.PREMOR_MS, p.ZAMUDA_MS + 1, false) == 2 * p.PREMOR_MS)
    check(p.premor(4_000, 900, false) == p.PREMOR_NAJVEC_MS)
    check(p.premor(p.PREMOR_NAJVEC_MS, 5_000, false) == p.PREMOR_NAJVEC_MS)
    // Ko se naprava umiri, se premor pocasi krajsa nazaj do najkrajsega.
    var premor = p.PREMOR_NAJVEC_MS
    var korakov = 0
    while (premor > p.PREMOR_MS) { val nov = p.premor(premor, 0, false); check(nov < premor); premor = nov; korakov++ }
    check(premor == p.PREMOR_MS && korakov in 10..100)
    // Stiska s pomnilnikom: takoj najdaljsi premor, ne glede na zamudo.
    check(p.premor(p.PREMOR_MS, 0, true) == p.PREMOR_NAJVEC_MS)
    // Neveljavna prejsnja vrednost ne uide iz meja.
    check(p.premor(0, 0, false) == p.PREMOR_MS && p.premor(-5, 999, false) == p.PREMOR_MS)

    // Stanje, shranjeno med preverjanjem: rep z luknjo se odreze pri prvi luknji, da se ti kosi preverijo znova.
    check(p.repPreverjanja(4 * 1024 * 1024) == 16 && p.repPreverjanja(1024 * 1024) == 64 && p.repPreverjanja(16 * 1024) == 1024)
    check(p.repPreverjanja(64 * 1024 * 1024) == 16 && p.repPreverjanja(0) == 1024)
    val cel = BooleanArray(464) { true }
    check(p.veljavnihKosov(464, 16, { cel[it] }) == 464)
    val zLuknjo = BooleanArray(464) { it < 462 }                 // resnicen primer: 464 kosov, zadnja dva zapisana kot »nimamo«
    check(p.veljavnihKosov(464, 16, { zLuknjo[it] }) == 462)
    val vmes = BooleanArray(464) { it != 457 && it != 460 }      // luknji sredi repa: rezemo pri prvi
    check(p.veljavnihKosov(464, 16, { vmes[it] }) == 457)
    val zgodaj = BooleanArray(464) { it != 100 }                 // luknja dalec pred repom je resnicno manjkajoc kos: ostane
    check(p.veljavnihKosov(464, 16, { zgodaj[it] }) == 464)
    check(p.veljavnihKosov(5, 16, { it != 0 }) == 0 && p.veljavnihKosov(0, 16, { true }) == 0 && p.veljavnihKosov(3, 16, { true }) == 3)

    // Po preverjanju: koncan torrent brez »Deli naprej« ostane ustavljen.
    check(!p.tecePoPreverjanju(koncan = true, deliNaprej = false))
    check(p.tecePoPreverjanju(koncan = true, deliNaprej = true))
    check(p.tecePoPreverjanju(koncan = false, deliNaprej = false))

    // Imena datotek stanja nastanejo samo iz veljavnega hasha.
    val a = "0123456789abcdef0123456789abcdef01234567"
    val b = "ffffffffffffffffffffffffffffffffffffffff"
    check(p.veljavenHash(a) && !p.veljavenHash(a.uppercase()) && !p.veljavenHash(a.dropLast(1)) && !p.veljavenHash("../" + a.drop(3)))
    check(!p.veljavenHash("") && !p.veljavenHash(a + "0"))
    // Ostanki: prekinjeni zapisi vedno, stanje neshranjenih torrentov; shranjeni in neznane datoteke ostanejo.
    val imena = listOf("$a.torrent", "$a.resume", "$b.torrent", "$b.resume", "$a.resume.tmp", "$b.torrent.tmp",
        "zapiski.txt", "$a.torrent.bak", "kratko.resume", "$a.tmp", ".nomedia")
    check(p.ostanki(imena, setOf(a)) == listOf("$b.torrent", "$b.resume", "$a.resume.tmp", "$b.torrent.tmp"))
    check(p.ostanki(imena, setOf(a, b)) == listOf("$a.resume.tmp", "$b.torrent.tmp"))
    check(p.ostanki(emptyList(), setOf(a)).isEmpty())
    println("ObnovaPravilaTest: OK")
}
