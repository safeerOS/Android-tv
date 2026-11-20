import si.safeer.tv.os.YouTubeNarocnine as Y

fun main() {
    check(Y.jeIdKanala("UCabcdefghijklmnopqrstuv"))
    check(!Y.jeIdKanala("UCabc"))
    check(!Y.jeIdKanala("XXabcdefghijklmnopqrstuv"))
    check(!Y.jeIdKanala("UCabcdefghijklmnopqrstu&"))
    check(Y.naslovFeeda("UCabcdefghijklmnopqrstuv")!!.endsWith("channel_id=UCabcdefghijklmnopqrstuv"))
    check(Y.naslovFeeda("UCabc\"&x=1") == null)
    val xml = "<feed><title>Kanal</title><entry><yt:videoId>aaaaaaaaaaa</yt:videoId><title>A &amp; B</title><author><name>Ana</name></author></entry>" +
        "<entry><yt:videoId>bad</yt:videoId><title>Slab id</title></entry>" +
        "<entry><yt:videoId>bbbbbbbbbbb</yt:videoId><title></title></entry>" +
        "<entry><yt:videoId>ccccccccccc</yt:videoId><title>C</title><author><name>Ana</name></author></entry>" +
        "<entry><yt:videoId>ddddddddddd</yt:videoId><title>D</title></entry><entry><yt:videoId>eeeeeeeeeee</yt:videoId><title>E</title></entry>" +
        "<entry><yt:videoId>fffffffffff</yt:videoId><title>F</title></entry></feed>"
    val v = Y.videiIzFeeda(xml)
    check(v.size == 3) { "st=${v.size}" }
    check(v[0].id == "aaaaaaaaaaa" && v[0].naslov == "A & B" && v[0].kanal == "Ana")
    check(v[1].id == "ccccccccccc")
    check(v[2].id == "ddddddddddd")
    check(Y.videiIzFeeda("", 3).isEmpty())
    println("YouTubeNarocnineTest: VSE V REDU")
}
