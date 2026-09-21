# Safeer Browser for Android TV 2.1.121 · Safeer OS 0.4.4 · Safeer OS Tablet 0.3.4

**Fix: the TV stays in Safeer Link, so its apps show up on the tablet again.**

- In 2.1.120 the TV signed in to its own hub again every few seconds. Other devices only saw it now and then, so the tablet's *Apps* screen was often missing the TV's apps, and commands sent to the TV (open an app, apps list) could get lost.
- Cause: the TV's receiver opened a new connection without closing the old one. The hub replaced the old one with the new one, but the old one was never properly closed on the TV; 30 seconds later it reported a missing reply, started another connection, and that one replaced the good one – a loop that never ended.
- Now there is exactly one connection at a time: a new one closes the previous one, late events of old connections are ignored, a close from the hub is answered, and a second start of the service does not open a second connection.
- Checked on the TV: no drops for minutes; the tablet shows *Safeer TV* with its apps under *Apps*.

Slovensko: **Popravek – televizor ostane v Safeer Linku, zato se njegove aplikacije spet pokažejo na tablici.** V 2.1.120 se je televizor na lastno središče vsakih nekaj sekund prijavljal znova, zato ga druge naprave niso videle zanesljivo (tablica: v *Aplikacijah* so manjkale aplikacije televizorja, ukazi televizorju so se lahko izgubili). Vzrok: sprejemnik je odprl novo povezavo, ne da bi zaprl staro; stara je čez 30 s javila napako in sprožila nov krog. Zdaj je povezava vedno ena sama.
