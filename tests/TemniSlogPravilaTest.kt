package si.safeer.tv

fun main() {
    println("TemniSlogPravilaTest: zacetek preizkusov...")

    // 1. S1 Specificnost: ohranitev izvirne specificnosti z uporabo :where(:not([data-safeer-medij], [data-safeer-medij] *))
    val osnovniCss = """
        body { background: #000; }
        #root { color: #fff; }
        div.card { border: 1px solid #333; }
        [class*="container"] { padding: 10px; }
    """.trimIndent()
    val pretvorjenOsnovni = TemniSlogPravila.pretvoriTemniCss(osnovniCss)
    check(pretvorjenOsnovni.contains("body:where(:not([data-safeer-medij], [data-safeer-medij] *)) {")) {
        "body mora ohraniti izvirno specificnost brez :where(body): $pretvorjenOsnovni"
    }
    check(pretvorjenOsnovni.contains("#root:where(:not([data-safeer-medij], [data-safeer-medij] *)) {")) {
        "#root mora ohraniti specificnost z ID-jem: $pretvorjenOsnovni"
    }
    check(pretvorjenOsnovni.contains("div.card:where(:not([data-safeer-medij], [data-safeer-medij] *)) {")) {
        "div.card mora ohraniti specificnost: $pretvorjenOsnovni"
    }
    check(pretvorjenOsnovni.contains("""[class*="container"]:where(:not([data-safeer-medij], [data-safeer-medij] *)) {""")) {
        "[class*=container] mora dobiti filter: $pretvorjenOsnovni"
    }
    // Brez stare oblike :where(...) zunaj, ki je vsem vsilila (0,1,0)
    check(!pretvorjenOsnovni.contains(":where(body)")) { "ne sme vsebovati :where(body)" }
    check(!pretvorjenOsnovni.contains(":where(#root)")) { "ne sme vsebovati :where(#root)" }

    // 2. V1 Psevdoelementi (::...): filter mora biti postavljen PRED psevdoelement
    val psevdoCss = """
        input::placeholder { color: #888; }
        div.opis::before { content: ""; }
        a.povezava::after { content: ">"; }
        ::-webkit-scrollbar { width: 8px; }
        ::selection { background: #444; }
    """.trimIndent()
    val pretvorjenPsevdo = TemniSlogPravila.pretvoriTemniCss(psevdoCss)
    check(pretvorjenPsevdo.contains("input:where(:not([data-safeer-medij], [data-safeer-medij] *))::placeholder {")) {
        "Filter mora biti pred ::placeholder: $pretvorjenPsevdo"
    }
    check(pretvorjenPsevdo.contains("div.opis:where(:not([data-safeer-medij], [data-safeer-medij] *))::before {")) {
        "Filter mora biti pred ::before: $pretvorjenPsevdo"
    }
    check(pretvorjenPsevdo.contains("a.povezava:where(:not([data-safeer-medij], [data-safeer-medij] *))::after {")) {
        "Filter mora biti pred ::after: $pretvorjenPsevdo"
    }
    // Globalni psevdoelementi brez predhodnega elementa morajo ostati nedotaknjeni (brskalnik zavrze :where()::...)
    check(pretvorjenPsevdo.contains("::-webkit-scrollbar {")) {
        "::-webkit-scrollbar mora ostati nedotaknjen: $pretvorjenPsevdo"
    }
    check(pretvorjenPsevdo.contains("::selection {")) {
        "::selection mora ostati nedotaknjen: $pretvorjenPsevdo"
    }
    check(!pretvorjenPsevdo.contains(":where()")) {
        "Ne sme vsebovati praznega :where(): $pretvorjenPsevdo"
    }

    // 3. V1 @keyframes in @font-face: notranjosti ne pokvari (pusti nedotaknjeno)
    val keyframesCss = """
        @keyframes utripanje {
            from { opacity: 0; }
            50% { opacity: 0.5; }
            to { opacity: 1; }
        }
        @-webkit-keyframes premik {
            0% { transform: translateY(0); }
            100% { transform: translateY(10px); }
        }
        @-moz-keyframes premik-moz {
            from { opacity: 0; }
            to { opacity: 1; }
        }
        @font-face {
            font-family: 'SafeerFont';
            src: url('safeer.woff2') format('woff2');
        }
    """.trimIndent()
    val pretvorjenKeyframes = TemniSlogPravila.pretvoriTemniCss(keyframesCss)
    check(pretvorjenKeyframes.contains("from { opacity: 0; }")) {
        "from v @keyframes ne sme dobiti filtra: $pretvorjenKeyframes"
    }
    check(pretvorjenKeyframes.contains("to { opacity: 1; }")) {
        "to v @keyframes ne sme dobiti filtra: $pretvorjenKeyframes"
    }
    check(pretvorjenKeyframes.contains("50% { opacity: 0.5; }")) {
        "50% v @keyframes ne sme dobiti filtra: $pretvorjenKeyframes"
    }
    check(pretvorjenKeyframes.contains("0% { transform: translateY(0); }")) {
        "0% v @-webkit-keyframes ne sme dobiti filtra: $pretvorjenKeyframes"
    }
    check(pretvorjenKeyframes.contains("@-moz-keyframes premik-moz {")) {
        "@-moz-keyframes mora biti ohranjen: $pretvorjenKeyframes"
    }
    check(pretvorjenKeyframes.contains("font-family: 'SafeerFont';")) {
        "@font-face mora ostati nedotaknjen: $pretvorjenKeyframes"
    }

    // 4. V1 @media, @supports, @container: pretvori pravila znotraj
    val mediaCss = """
        @media screen and (min-width: 600px) {
            .stranska-vrstica { width: 300px; }
            input::placeholder { color: #aaa; }
            @supports (display: grid) {
                .mreza { display: grid; }
            }
        }
        @container (min-width: 400px) {
            .kartica { padding: 8px; }
        }
    """.trimIndent()
    val pretvorjenMedia = TemniSlogPravila.pretvoriTemniCss(mediaCss)
    check(pretvorjenMedia.contains("@media screen and (min-width: 600px)")) {
        "@media glava mora biti ohranjena: $pretvorjenMedia"
    }
    check(pretvorjenMedia.contains(".stranska-vrstica:where(:not([data-safeer-medij], [data-safeer-medij] *)) {")) {
        "Pravilo znotraj @media mora dobiti filter: $pretvorjenMedia"
    }
    check(pretvorjenMedia.contains("input:where(:not([data-safeer-medij], [data-safeer-medij] *))::placeholder {")) {
        "Psevdoelement znotraj @media mora dobiti filter pred psevdoelementom: $pretvorjenMedia"
    }
    check(pretvorjenMedia.contains(".mreza:where(:not([data-safeer-medij], [data-safeer-medij] *)) {")) {
        "Pravilo znotraj vdelanega @supports mora dobiti filter: $pretvorjenMedia"
    }
    check(pretvorjenMedia.contains("@container (min-width: 400px)")) {
        "@container glava mora biti ohranjena: $pretvorjenMedia"
    }
    check(pretvorjenMedia.contains(".kartica:where(:not([data-safeer-medij], [data-safeer-medij] *)) {")) {
        "Pravilo znotraj @container mora dobiti filter: $pretvorjenMedia"
    }

    // 5. Vejice v :is(), :not() in atributih ter sestavljeni selektorji s psevdoelementi
    val vejiceCss = """
        :is(div, span.alert), .btn { color: #fff; }
        :not(.on, .active), #glavni { display: none; }
        a[data-oznake="film,serija"], button { background: #222; }
        input::placeholder, textarea::placeholder, .oznaka { opacity: 0.8; }
        button:focus::after { outline: none; }
    """.trimIndent()
    val pretvorjenVejice = TemniSlogPravila.pretvoriTemniCss(vejiceCss)
    check(pretvorjenVejice.contains(":is(div, span.alert):where(:not([data-safeer-medij], [data-safeer-medij] *)), .btn:where(:not([data-safeer-medij], [data-safeer-medij] *)) {")) {
        "Vejica v :is() ne sme razbiti selektorja: $pretvorjenVejice"
    }
    check(pretvorjenVejice.contains(":not(.on, .active):where(:not([data-safeer-medij], [data-safeer-medij] *)), #glavni:where(:not([data-safeer-medij], [data-safeer-medij] *)) {")) {
        "Vejica v :not() ne sme razbiti selektorja: $pretvorjenVejice"
    }
    check(pretvorjenVejice.contains("""a[data-oznake="film,serija"]:where(:not([data-safeer-medij], [data-safeer-medij] *)), button:where(:not([data-safeer-medij], [data-safeer-medij] *)) {""")) {
        "Vejica v nizu atributa ne sme razbiti selektorja: $pretvorjenVejice"
    }
    check(pretvorjenVejice.contains("input:where(:not([data-safeer-medij], [data-safeer-medij] *))::placeholder, textarea:where(:not([data-safeer-medij], [data-safeer-medij] *))::placeholder, .oznaka:where(:not([data-safeer-medij], [data-safeer-medij] *)) {")) {
        "Seznam s psevdoelementi se mora pravilno pretvoriti: $pretvorjenVejice"
    }
    check(pretvorjenVejice.contains("button:focus:where(:not([data-safeer-medij], [data-safeer-medij] *))::after {")) {
        "Psevdo-razred pred psevdoelementom mora ohraniti filter pred psevdoelementom: $pretvorjenVejice"
    }

    // 6. Test dejanskega DARK_MODE_AMOLED_CSS
    val vzorecDarkMode = """
        /* 🌌 Safeer Universal Smart OLED Dark Theme */
        :root {
            color-scheme: dark !important;
        }
        html, body, #root, #app, [id*="root"], [id*="app"],
        main, article, section, [class*="content"], [class*="container"], [class*="wrapper"] {
            background-color: #0b0e14 !important;
            color: #e2e8f0 !important;
        }
        header, nav, [class*="header"] {
            background-color: #0f131c !important;
        }
    """.trimIndent()
    val dejanskiPretvorjen = TemniSlogPravila.pretvoriTemniCss(vzorecDarkMode)
    check(!dejanskiPretvorjen.contains("/*")) { "Komentarji morajo biti odstranjeni" }
    check(dejanskiPretvorjen.contains(":root:where(:not([data-safeer-medij], [data-safeer-medij] *)) {")) {
        ":root mora dobiti filter: $dejanskiPretvorjen"
    }
    check(dejanskiPretvorjen.contains("html:where(:not([data-safeer-medij], [data-safeer-medij] *))")) {
        "html mora dobiti filter: $dejanskiPretvorjen"
    }
    check(dejanskiPretvorjen.contains("body:where(:not([data-safeer-medij], [data-safeer-medij] *))")) {
        "body mora dobiti filter: $dejanskiPretvorjen"
    }
    check(dejanskiPretvorjen.contains("""[class*="container"]:where(:not([data-safeer-medij], [data-safeer-medij] *))""")) {
        "[class*=container] mora dobiti filter: $dejanskiPretvorjen"
    }

    // 7. Oznaceni element sam je izvzet (prekrivni sloj ob videu ne sme dobiti temnega ozadja), ne le potomci
    check(TemniSlogPravila.MEDIJ_FILTER == ":where(:not([data-safeer-medij], [data-safeer-medij] *))") {
        "Filter mora izvzeti oznaceni element in njegove potomce: ${'$'}{TemniSlogPravila.MEDIJ_FILTER}"
    }

    // 8. JS koren preverba prisotnosti zahtevanih elementov v MEDIJ_KOREN_JS
    val js = TemniSlogPravila.MEDIJ_KOREN_JS
    check(js.contains("document.fullscreenElement")) { "JS mora preveriti document.fullscreenElement" }
    check(js.contains("fullscreenchange")) { "JS mora ob spremembi celozaslonskega nacina ponovno oznaciti" }
    check(js.contains("setTimeout") && js.contains("250")) { "MutationObserver mora biti omejen (en pregled na 250 ms)" }
    check(js.contains("el.contains(v)")) { "Prednik videa ne sme biti oznacen kot prekrivka" }
    check(js.contains("ox === 'auto' || ox === 'scroll' || oy === 'auto' || oy === 'scroll'")) {
        "JS mora ustaviti plezanje pri overflow auto|scroll"
    }
    check(js.contains("console.warn")) { "JS catch mora vsebovati console.warn (brez skrivanja napak)" }
    check(js.contains("MutationObserver")) { "JS mora imeti MutationObserver" }
    check(js.contains("emptied")) { "JS mora poslusati dogodek emptied za odstranitev oznake" }
    check(js.contains("0.8 * povrsina") || js.contains("0.8 * r.width * r.height")) {
        "JS mora oznaciti prekrivke z vec kot 80 % pokritostjo videa"
    }

    println("TemniSlogPravilaTest: VSI PREIZKUSI USPELI (OK)!")
}
