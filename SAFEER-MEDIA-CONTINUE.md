# Safeer Media – Continue Watching in združevanje virov

Ta veja doda lokalno zgodovino napredka za video. Safeer shrani položaj šele po 15 s in odstrani naslov iz »Nadaljuj gledanje«, ko je ogled >=95 %. Hrani največ 30 vnosov in podatkov ne pošilja v oblak.

V razdelku Video se isti normalizirani naslov iz več virov prikaže kot ena kartica. Če obstaja več virov, klik odpre izbiro vira; če je vir samo eden, se začne takoj. Kartica še vedno pokaže prvi vir in število razpoložljivih virov.

Ob ponovnem predvajanju Safeer poskusi nadaljevati z lokalno shranjenega položaja. To velja za predvajanje, ki ga upravlja GlasbaStoritev/Media3. Za spletni fallback, kjer originalni spletni predvajalnik ostane izvajalec, mora položaj še naprej upravljati SpletniIgralec in stran sama.

Identiteta vsebine je namenoma konservativna (normaliziran naslov brez leta). Naslednja izboljšava naj uporablja strukturirane ID-je/JSON-LD, ko jih vir ponuja, da remake-i ali enako poimenovani naslovi ne bodo napačno združeni.
