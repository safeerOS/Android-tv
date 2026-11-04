/*
 * Sorted string table on disk, opened with FileChannel.map (pure JVM, no Android imports).
 *
 * Why: the threat engines kept hundreds of thousands of rules in the Java heap (HashMap with String and Integer objects,
 * ~120 bytes per rule; the TV main process had 41 MB of Java heap, measured 4. 11. 2026) and rebuilt them from the saved
 * lists on every start (6.2 s on the 2 GB Philips TV before the first request was protected). The table is written once,
 * after the source was verified (SHA-256 of a list, Ed25519 of a signed bundle), and opened in milliseconds. Lookups are
 * binary searches over the mapped file, so the rules live in clean file pages the kernel can drop.
 *
 * Keys are stored as text, not as hashes: a hash collision would block an innocent site (see DomainSuffixTrie).
 * Comparison is char by char (UTF-16 code units), the same order as String.compareTo.
 *
 * Layout (big endian):
 *   0  magic "SFSST1\0\0"
 *   8  key (32 bytes): what the table was built from (for example SHA-256 of the source lists); open() compares it
 *  40  CRC32 of everything after the fixed header (checked by bodyIntact(), off the main thread)
 *  44  flags (bit 0: keys stored as UTF-16, otherwise one byte per char because every char is <= 0xFF)
 *  48  table count, word count, words section size, reserved
 *  64  per table: entry count, pool size in chars
 *      words: per word int length (-1 = null) + UTF-8 bytes, padded to 4
 *      per table: (count + 1) start offsets, count values (>= 0), key pool (padded to 4)
 * The file length must equal the length computed from the header, so a cut-off write is never used.
 *
 * The same file is used unchanged by Safeer Browser for Android and Safeer Browser for Android TV.
 */
package com.safeer.threatfeed

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32

class SortedStringTable private constructor(
    private val buf: ByteBuffer,
    /** Short strings stored once (categories, list names, feed sources); values usually index into them. */
    val words: List<String?>,
    private val counts: IntArray,
    private val poolUnits: IntArray,
    private val offsetsAt: IntArray,
    private val valuesAt: IntArray,
    private val poolAt: IntArray,
    private val wide: Boolean,
    val file: File,
    private val expectedLength: Long,
    private val crc: Int,
) {
    /** Description of one table to write: [count] keys in strictly ascending order with their values (>= 0). */
    class Table(val count: Int, val key: (Int) -> CharSequence, val value: (Int) -> Int)

    val tableCount: Int get() = counts.size

    /** Size of the file (all of it is mapped; only touched pages become resident). */
    val bytes: Long get() = expectedLength

    fun size(table: Int): Int = counts[table]

    /**
     * Value of the entry equal to the part of key from index from in table, or -1. A damaged file never throws: every offset is checked
     * against the pool and an inconsistent entry ends the search as "not found" (bodyIntact() then replaces the file).
     */
    fun find(table: Int, key: String, from: Int = 0): Int {
        var lo = 0
        var hi = counts[table] - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(table, mid, key, from)
            when {
                c == DAMAGED -> return -1
                c == 0 -> return buf.getInt(valuesAt[table] + mid * 4)
                c < 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return -1
    }

    /** Key of entry [index] (for tests and diagnostics; lookups never build strings). */
    fun keyAt(table: Int, index: Int): String {
        val s = buf.getInt(offsetsAt[table] + index * 4)
        val e = buf.getInt(offsetsAt[table] + index * 4 + 4)
        val sb = StringBuilder(e - s)
        for (k in s until e) sb.append(charAt(table, k))
        return sb.toString()
    }

    fun valueAt(table: Int, index: Int): Int = buf.getInt(valuesAt[table] + index * 4)

    private fun charAt(table: Int, unit: Int): Char =
        if (wide) buf.getChar(poolAt[table] + unit * 2) else (buf.get(poolAt[table] + unit).toInt() and 0xff).toChar()

    private fun compare(table: Int, index: Int, key: String, from: Int): Int {
        val s = buf.getInt(offsetsAt[table] + index * 4)
        val e = buf.getInt(offsetsAt[table] + index * 4 + 4)
        if (s < 0 || e < s || e > poolUnits[table]) return DAMAGED
        val d = e - s
        val keyLength = key.length - from
        val n = if (d < keyLength) d else keyLength
        for (k in 0 until n) {
            val x = charAt(table, s + k)
            val y = key[from + k]
            if (x != y) return if (x < y) -1 else 1
        }
        return d - keyLength
    }

    /**
     * Reads the file once (through the page cache, not through the mapping) and checks length and CRC32 of the body.
     * Meant for a background thread after open(): a damaged file is then rebuilt from the verified source.
     */
    fun bodyIntact(): Boolean {
        try {
            if (file.length() != expectedLength) return false
            FileInputStream(file).use { input ->
                val sum = CRC32()
                val buffer = ByteArray(64 * 1024)
                var skip = HEADER_FIXED.toLong()
                while (skip > 0) {
                    val r = input.skip(skip)
                    if (r <= 0) return false
                    skip -= r
                }
                while (true) {
                    val r = input.read(buffer)
                    if (r < 0) break
                    sum.update(buffer, 0, r)
                }
                return sum.value.toInt() == crc
            }
        } catch (e: Exception) {
            return false
        }
    }

    companion object {
        private val MAGIC = "SFSST1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0)
        const val KEY_BYTES = 32
        /** Magic, key, CRC32, flags, four counts. */
        const val HEADER_FIXED = 8 + KEY_BYTES + 4 + 4 + 16
        const val MAX_TABLES = 16
        const val MAX_WORDS = 1 shl 16
        private const val DAMAGED = Int.MIN_VALUE

        private fun pad4(n: Long) = (n + 3) and 3L.inv()

        /** SHA-256 over the given parts, each prefixed by its length (no ambiguity between "ab","c" and "a","bc"). */
        fun keyOf(parts: List<String>): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            for (p in parts) {
                val b = p.toByteArray(Charsets.UTF_8)
                md.update(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()))
                md.update(b)
            }
            return md.digest()
        }

        /**
         * Opens [file] when it is a complete table built from [expectedKey]; null otherwise (missing, other key, other
         * format, wrong length). Reads only the header and the words, so it takes milliseconds for any table size.
         */
        fun open(file: File, expectedKey: ByteArray): SortedStringTable? {
            try {
                if (expectedKey.size != KEY_BYTES || !file.isFile) return null
                RandomAccessFile(file, "r").use { raf ->
                    val length = raf.length()
                    if (length < HEADER_FIXED || length > Int.MAX_VALUE) return null
                    val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, length).order(ByteOrder.BIG_ENDIAN)
                    val magic = ByteArray(MAGIC.size); map.get(magic)
                    if (!magic.contentEquals(MAGIC)) return null
                    val key = ByteArray(KEY_BYTES); map.get(key)
                    if (!MessageDigest.isEqual(key, expectedKey)) return null
                    val crc = map.getInt()
                    val flags = map.getInt()
                    val tables = map.getInt()
                    val wordCount = map.getInt()
                    val wordsBytes = map.getInt()
                    map.getInt()
                    if ((flags and 1.inv()) != 0 || tables !in 0..MAX_TABLES || wordCount !in 0..MAX_WORDS || wordsBytes < 0) return null
                    val counts = IntArray(tables)
                    val units = IntArray(tables)
                    for (t in 0 until tables) {
                        counts[t] = map.getInt(); units[t] = map.getInt()
                        if (counts[t] < 0 || units[t] < 0) return null
                    }
                    val wide = (flags and 1) != 0
                    var pos = HEADER_FIXED.toLong() + 8L * tables
                    val wordsAt = pos
                    pos = pad4(pos + wordsBytes)
                    val offsetsAt = IntArray(tables); val valuesAt = IntArray(tables); val poolAt = IntArray(tables)
                    for (t in 0 until tables) {
                        offsetsAt[t] = pos.toInt(); pos += 4L * (counts[t] + 1L)
                        valuesAt[t] = pos.toInt(); pos += 4L * counts[t]
                        poolAt[t] = pos.toInt(); pos = pad4(pos + units[t].toLong() * (if (wide) 2 else 1))
                        if (pos > length) return null
                    }
                    if (pos != length) return null
                    val words = ArrayList<String?>(wordCount)
                    var w = wordsAt.toInt()
                    val wordsEnd = wordsAt + wordsBytes
                    for (i in 0 until wordCount) {
                        if (w + 4 > wordsEnd) return null
                        val n = map.getInt(w); w += 4
                        if (n == -1) { words.add(null); continue }
                        if (n < 0 || w + n > wordsEnd) return null
                        val bytes = ByteArray(n)
                        for (k in 0 until n) bytes[k] = map.get(w + k)
                        words.add(String(bytes, Charsets.UTF_8)); w += n
                    }
                    return SortedStringTable(map, words, counts, units, offsetsAt, valuesAt, poolAt, wide, file, length, crc)
                }
            } catch (e: Exception) {
                return null
            }
        }

        /**
         * Writes [tables] and [words] to [target] atomically (own temp file, fsync, rename): a reader sees the old table
         * or the new one, never a partial one. Keys must be strictly ascending (checked: a wrong order would break the
         * binary search) and values >= 0. Only code that has just verified the source may call this.
         */
        fun write(target: File, key: ByteArray, words: List<String?>, tables: List<Table>) {
            require(key.size == KEY_BYTES) { "key must be 32 bytes" }
            require(tables.size <= MAX_TABLES && words.size <= MAX_WORDS) { "too many tables or words" }
            // Pass 1: order, values, pool sizes and whether any char needs two bytes.
            var wide = false
            val units = IntArray(tables.size)
            for ((t, table) in tables.withIndex()) {
                var previous: CharSequence? = null
                var total = 0L
                for (i in 0 until table.count) {
                    val k = table.key(i)
                    if (previous != null) require(compareChars(previous, k) < 0) { "keys of table $t are not strictly ascending at $i" }
                    require(table.value(i) >= 0) { "negative value" }
                    for (c in 0 until k.length) if (k[c].code > 0xff) wide = true
                    total += k.length
                    previous = k
                }
                require(total <= Int.MAX_VALUE / 4) { "table too large" }
                units[t] = total.toInt()
            }
            val wordBytes = words.map { it?.toByteArray(Charsets.UTF_8) }
            val wordsSize = wordBytes.sumOf { 4 + (it?.size ?: 0) }

            val dir = target.absoluteFile.parentFile
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.name}")
            val temp = File.createTempFile(target.name + ".", ".tmp", dir)
            try {
                FileOutputStream(temp).use { fos ->
                    val crc = CRC32()
                    val head = DataOutputStream(BufferedOutputStream(fos, 256))
                    head.write(MAGIC); head.write(key)
                    head.writeInt(0) // CRC32, written below
                    head.writeInt(if (wide) 1 else 0)
                    head.writeInt(tables.size); head.writeInt(words.size); head.writeInt(wordsSize); head.writeInt(0)
                    head.flush()
                    val body = DataOutputStream(BufferedOutputStream(CrcStream(fos, crc), 64 * 1024))
                    for ((t, table) in tables.withIndex()) { body.writeInt(table.count); body.writeInt(units[t]) }
                    var written = HEADER_FIXED + 8L * tables.size
                    for (b in wordBytes) {
                        if (b == null) body.writeInt(-1) else { body.writeInt(b.size); body.write(b) }
                    }
                    written += wordsSize
                    written = padTo4(body, written)
                    for (table in tables) {
                        var offset = 0
                        body.writeInt(0)
                        for (i in 0 until table.count) { offset += table.key(i).length; body.writeInt(offset) }
                        for (i in 0 until table.count) body.writeInt(table.value(i))
                        written += 4L * (2L * table.count + 1)
                        for (i in 0 until table.count) {
                            val k = table.key(i)
                            for (c in 0 until k.length) if (wide) body.writeChar(k[c].code) else body.write(k[c].code)
                            written += k.length.toLong() * (if (wide) 2 else 1)
                        }
                        written = padTo4(body, written)
                    }
                    body.flush()
                    val crcBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt())
                    crcBytes.flip()
                    fos.channel.write(crcBytes, (8 + KEY_BYTES).toLong())
                    fos.fd.sync()
                }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                if (temp.exists()) temp.delete()
            }
        }

        private fun padTo4(out: DataOutputStream, written: Long): Long {
            var w = written
            while ((w and 3L) != 0L) { out.write(0); w++ }
            return w
        }

        /** Same order as String.compareTo (UTF-16 code units), for any CharSequence. */
        fun compareChars(a: CharSequence, b: CharSequence): Int {
            val n = minOf(a.length, b.length)
            for (i in 0 until n) {
                val x = a[i]; val y = b[i]
                if (x != y) return x - y
            }
            return a.length - b.length
        }
    }

    /** Output stream that feeds every byte to a CRC32 (the fixed header is written outside it). */
    private class CrcStream(private val out: OutputStream, private val crc: CRC32) : OutputStream() {
        override fun write(b: Int) { crc.update(b); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { crc.update(b, off, len); out.write(b, off, len) }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
