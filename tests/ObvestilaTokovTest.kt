package si.safeer.tv.os

fun main() {
    // Vnosi, kakrsne je dodatek kazal namesto toka (krog 73): prosnja, vabilo, "No streams found".
    check(ObvestilaTokov.je("https://ko-fi.com/nekdo", "Donation needed", false))
    check(ObvestilaTokov.je("https://discord.gg/abc", "Join the Discord server", false))
    check(ObvestilaTokov.je("https://www.patreon.com/x", "Support", false))
    check(ObvestilaTokov.je("https://donate.primer.org/", "Hvala", false))
    check(ObvestilaTokov.je("https://primer.org/stran", "❌ No streams found", false))
    check(ObvestilaTokov.je("https://primer.org/info", "No stream found for this title", false))
    check(ObvestilaTokov.je("https://primer.org/x", "Please donate to keep the server alive", false))
    check(ObvestilaTokov.je("https://primer.org/x", "Buy me a coffee", false))
    // Pravi tokovi ostanejo - tudi ce v opisu omenijo Discord ali je v naslovu filma beseda iz pravila.
    check(!ObvestilaTokov.je("https://cdn.primer.org/film.mkv", "Dodatek 1080p [3.1 GB]", false))
    check(!ObvestilaTokov.je("https://cdn.primer.org/tok/abc", "Dodatek 1080p · Join our Discord", true))
    check(!ObvestilaTokov.je("https://cdn.primer.org/Discord.Movie.2024.1080p.mp4?t=1", "Discord Movie 1080p", false))
    check(!ObvestilaTokov.je("https://cdn.primer.org/tok/abc", "The Donation (2019) 720p", true))
    check(!ObvestilaTokov.je("https://cdn.primer.org/tok/abc", "Film 1080p", false))
    check(!ObvestilaTokov.je("https://primer.org/prenos.m3u8", "Kofi Annan dokumentarec", false))
    // Gostitelj: poddomene da, podobna imena ne.
    check(ObvestilaTokov.gostitelj("https://Uporabnik@WWW.Discord.com:443/pot") == "www.discord.com")
    check(ObvestilaTokov.je("https://www.paypal.com/donate?x=1", "", false))
    check(!ObvestilaTokov.je("https://notdiscord.gg.primer.org/film.mp4", "Film", false))
    check(!ObvestilaTokov.je("https://mypaypal.com.primer.org/a", "Film", true))
    println("ObvestilaTokovTest: OK")
}
