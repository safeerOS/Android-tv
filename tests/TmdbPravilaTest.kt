import si.safeer.tv.os.TmdbPravila as T

fun main() {
    val b = T.BASE
    check(T.jeNas("$b/manifest.json") && T.jeManifest("$b/manifest.json"))
    check(!T.jeNas("https://tmdb.safeer.invalid.evil.com/manifest.json"))
    check(!T.jeNas("https://v3-cinemeta.strem.io/manifest.json"))
    val z = T.zahteva("$b/catalog/movie/tmdb.popularno.json")!!
    check(z.tip == "movie" && z.katalog == "tmdb.popularno" && z.skip == 0)
    val z2 = T.zahteva("$b/catalog/series/tmdb.novo/skip=40.json")!!
    check(z2.tip == "series" && z2.skip == 40)
    check(T.zahteva("$b/catalog/movie/tmdb.novo/skip=abc.json") == null)
    check(T.zahteva("$b/catalog/movie/x/y/z.json") == null)
    check(T.zahteva("$b/meta/movie/tt1234567.json") == null)
    check(T.zahteva("https://example.com/catalog/movie/a.json") == null)
    check(T.pot("movie", "tmdb.popularno") == "movie/popular")
    check(T.pot("series", "tmdb.novo") == "tv/on_the_air")
    check(T.pot("movie", "../../x") == null)
    check(T.stran(0) == 1 && T.stran(20) == 2 && T.stran(39) == 2 && T.stran(-5) == 1 && T.stran(99999) == T.NAJVEC_STRANI)
    check(T.stran(17, listOf(17, 33)) == 2 && T.stran(33, listOf(17, 33)) == 3 && T.stran(40, listOf(17)) == 3 && T.stran(0, listOf(17)) == 1)
    check(T.slika("/abc.jpg", "w342") == "https://image.tmdb.org/t/p/w342/abc.jpg")
    check(T.slika("/../x", "w342") == "" && T.slika(null, "w342") == "" && T.slika("http://evil", "w342") == "")
    check(T.veljavenImdb("tt0111161") && !T.veljavenImdb("tt12") && !T.veljavenImdb("") && !T.veljavenImdb(null) && !T.veljavenImdb("tt0111161x"))
    check(T.leto("1994-09-23") == "1994" && T.leto("x") == "" && T.leto(null) == "" && T.leto("2001") == "2001")
    check(T.jezik("sl") == "sl-SI" && T.jezik("") == "en-US" && T.jezik("de") == "de" && T.jezik("zz;drop") == "en-US")
    println("TmdbPravilaTest: VSE V REDU")
}
