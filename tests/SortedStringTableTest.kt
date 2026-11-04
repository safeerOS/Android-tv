package com.safeer.threatfeed

/**
 * CodeRabbit 4. 11. 2026: pokvarjena dolzina besede blizu Int.MAX_VALUE je mejo w + n prelila, ByteArray(n) ~2 GB je dal
 * OutOfMemoryError (Error, ki ga open() ne ujame) - ob vsakem zagonu brskalnika, dokler datoteka ostane. open() mora vrniti null.
 */
fun main() {
    val d = java.io.File(System.getProperty("java.io.tmpdir"), "safeer-sst-" + System.nanoTime()).apply { mkdirs() }
    try {
        val kljuc = SortedStringTable.keyOf(listOf("preizkus"))
        val f = java.io.File(d, "t.sst")
        SortedStringTable.write(f, kljuc, listOf("zzzzzzzzzzzz"), emptyList())
        check(SortedStringTable.open(f, kljuc) != null) { "cela tabela se ne odpre" }
        val b = f.readBytes()
        val beseda = "zzzzzzzzzzzz".toByteArray()
        val i = (0..b.size - beseda.size).first { k -> beseda.indices.all { b[k + it] == beseda[it] } }
        val n = 0x7FFFFFF0
        b[i - 4] = (n ushr 24).toByte(); b[i - 3] = (n ushr 16).toByte(); b[i - 2] = (n ushr 8).toByte(); b[i - 1] = n.toByte()
        f.writeBytes(b)
        val t = try { SortedStringTable.open(f, kljuc) } catch (e: OutOfMemoryError) { error("PADLO: OutOfMemoryError pri pokvarjeni dolzini besede") }
        check(t == null) { "PADLO: pokvarjena tabela se je odprla" }
        println("SortedStringTableTest: OK")
    } finally { d.deleteRecursively() }
}
