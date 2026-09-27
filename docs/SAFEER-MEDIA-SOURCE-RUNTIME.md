# Safeer Media Source Runtime

Safeer Media treats a user-added web app as a **media source**, not as UI that should normally be shown.

Playback order:
1. Prefer a direct, site-authorized HTML5/HLS/DASH media URL and play it with Media3.
2. If the site requires its own web playback context (session, protected player, iframe), keep that context in `SpletniIgralec` and expose only the media surface inside Safeer's `PredvajanjeActivity`.
3. Never open a second browser APK. If playback cannot be represented safely, use the built-in Safeer browser as an explicit fallback.

The source runtime must not bypass DRM, authentication, paywalls, or access controls. User cookies/session remain in the WebView context.

## UX contract
- Search and discovery happen in Safeer Media.
- Source pages are background providers; page chrome, comments, navigation and recommendations must not leak into the player.
- Always retain source identity in metadata/details.
- Movies and series are separate catalog groups. Genre shelves are derived from source metadata/text when confidence is sufficient.
- A source failure must not freeze the Media screen; time out and fall back cleanly.

## Memory contract
Android TV is the constrained target. Keep web source scans bounded, destroy temporary WebViews, and avoid multiple simultaneous Chromium renderers. Playback may own one persistent WebView only when the source itself must remain alive.

## Katalog uporabnikovih virov (v3)

Safeer Media ponudnikov ne prikazuje kot locene aplikacije. Zacetne strani spletnih virov so vhod v skupni katalog.

- `Priljubljeno v tvojih virih` ostane mesan, ponudnisko nevtralen izbor.
- Kadar spletni vir poda dovolj mocan signal, Safeer vsebino razdeli na `Filmi` in `Serije`.
- Zvrsti so podskupine vrste (`Filmi · Akcija`, `Serije · Drama`), zato film in serija iste zvrsti nista pomesana.
- Neznane vsebine se ne ugibajo. Ostanejo v skupnem izboru.
- Ime vira ostane na kartici, da uporabnik vedno ve, od kod vsebina prihaja.
- Katalog spletnih virov ima 20-minutni procesni cache. Ponovno odpiranje razdelka zato ne zaganja novih WebViewev.
- Naenkrat se nalagata najvec dva spletna vira; s tem omejimo sunke RAM-a na televizorjih z malo pomnilnika.
- Najvec 12 spletnih virov se osvezi v enem prehodu; vec virov se lahko podpira pozneje s cakalno vrsto/paginacijo.

Namen te plasti ni obhod pravil ponudnika. DRM, prijava in pravice ostanejo v izvornem spletnem kontekstu; kjer neposredni tok ni na voljo, `SpletniIgralec` uporablja ozadni spletni kontekst in Safeerjev UI.
