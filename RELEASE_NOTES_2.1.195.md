# Safeer 2.1.195 · Safeer OS 0.5.71 · Predvajalnik 0.2.57

Dva popravka iz pregleda kode.

- **Čiščenje sledilnih parametrov v naslovih:** imena parametrov, zapisana s percent-kodo (npr. `utm%5Fsource`), so prej ostala v naslovu; zdaj se prepoznajo in odstranijo. Dodani so še `igsh`, `_ga`, `_gl`, Matomo (`pk_campaign` …, `mtm_*`), `spm`, `scm`, `ncid`, `cmpid`, na YouTubu pa še `si`. Prijave (OAuth), video, iskanje in plačila ostanejo nedotaknjeni.
- **Izjema za strežnike licenc (DRM):** izjema od filtrov velja samo za prave domene ponudnikov (Widevine, DRMtoday, castLabs, ExpressPlay, Axinom, EZDRM, BuyDRM, Irdeto, Verimatrix), ne več za vsak naslov, ki ima v imenu »widevine« ali podobno. Videi z zaščito delujejo enako.
