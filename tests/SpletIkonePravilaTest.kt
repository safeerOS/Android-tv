package si.safeer.tv

fun main() {
    val html = """
        <html><head>
          <link href='/mala.png' rel='shortcut icon' sizes='32x32'>
          <link REL="apple-touch-icon" HREF="/apple.png">
          <link type="image/png" sizes="128x128 256x256" rel="icon" href="/velika.png">
          <link href="/vektor.svg?v=2" type="image/svg+xml" rel="icon">
          <link rel="stylesheet" href="/ni-ikona.css">
        </head></html>
    """.trimIndent()
    val k = SpletIkonePravila.kandidati(html)
    check(k.map { it.href } == listOf("/velika.png", "/vektor.svg?v=2", "/apple.png", "/mala.png")) { k }
    check(k.map { it.velikost } == listOf(256, 256, 180, 32)) { k }
    check(SpletIkonePravila.kandidati("<LINK rel=icon href=/favicon.ico>").single().href == "/favicon.ico")
    println("SpletIkonePravilaTest: OK")
}
