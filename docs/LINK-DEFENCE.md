# Safeer Link: defence of the hub

Every device in Safeer Link runs its own hub (see `LINK-MESH.md`). A hub listens in the home network; only devices
of the circle of trust have any business there. This document describes what a hub does about everybody else.

The same rules and the same numbers are implemented twice: `core/link_obramba.py` and `core/link_varovalka.py`
(Linux), `HubObramba.kt` and `HubVarovalka.kt` (Android). Both pairs are pure logic with a clock as a parameter and
have the same tests (`tests/test_link_obramba.py`, `tests/test_link_varovalka.py`, `tests/HubObrambaTest.kt`,
`tests/HubVarovalkaTest.kt`).

## What a hub reveals today

Found in a review of the code on 4 October 2026 (Linux 1.0.93, Android 0.5.48):

| Channel | Who sees it | What |
| --- | --- | --- |
| mDNS advert `_safeercast._tcp` | everyone in the same network, also on a foreign Wi-Fi | device name, certificate fingerprint (`fp`), device id (`id`) — all three permanent |
| Hub port (8990 by default) | everyone in the same network | an open TLS port; the certificate with the permanent key goes to anyone who connects |
| Pairing paths (`/cast/pair/*`) | everyone in the same network | that a hub exists; five guesses of the code per sign-in |
| Relay (Global Link) | the relay operator | the device key, the ids of the circle (`allow`), the public IP address |

A hub never talks to the internet directly (only private addresses are accepted), a WebSocket needs a one-time
ticket, tokens travel only over TLS with a pinned fingerprint, and the relay carries the TLS of Safeer Link end to
end — it sees encrypted bytes only.

Until now nobody **counted** hostile behaviour: a device in the network could probe paths, guess pairing codes
(every start of a pairing shows the user a notification), send refused signatures and open connections without end.
Attempts were limited only inside one sign-in.

## Stage 1 (implemented): count per source, block, tell the user

Every hostile event has a weight. When the weights of one source (IP address) reach **400 within 60 seconds**, the
source is blocked.

| Event | Weight | Alone, this many per minute block the source |
| --- | --- | --- |
| accepted connection of a source that is not trusted | 1 | 400 |
| TLS handshake failed (not TLS, port scanner, silent connection) | 10 | 37 |
| probing: a path the hub does not have, a malformed request, asking to pair while pairing by code is closed | 10 | 37 |
| refused signature, ticket or token; a QR code that does not exist | 20 | 20 |
| broken WebSocket frame | 20 | 20 |
| pairing with a code: one round of SPAKE2 — one guess of the code | 20 | 20 |
| pairing: a wrong code, too many attempts, a start of a QR sign-in | 40 | 10 |
| pairing with a code: a start (each one shows the user a notification with the code) | 100 | 4 |

* **A block is silent.** The connection of a blocked source is closed right after it is accepted: no TLS handshake,
  no certificate, no answer.
* It lasts **10 minutes**, twice as long at every repetition (up to one hour); a day without a block resets the
  count.
* A block refuses **new** connections. A connection the source already has open stays: it proved membership of the
  circle when it was opened (seen on real devices — two phones stayed neighbours while one was blocked).
* **A source that has just proved to be a member of the circle** (valid signature, ticket or token) is trusted for
  10 minutes, and a source with an open connection to the hub (a signed-in device, a neighbouring hub) for as long
  as it stays connected: plain connections are not counted for it (a phone that browses a folder of pictures opens
  hundreds), hostile events count half.
* **The device itself** (127.0.0.1, ::1) is never blocked: its own programs and the channels of Global Link arrive
  from there.
* What is *not* hostile: HTTP 405 (it is part of how devices recognise a hub), 409, 410 and 503 (state, not
  attack), and on Android `GET /cast/health` without a token (401) — that is how every device checks a hub before
  it trusts it.
* A legitimate device that keeps signing in under an id the hub does not know — one refused challenge and one
  plain-HTTP probe every 15 seconds — stays far below the threshold (128 of 400).
* **Attack:** three different sources blocked within 30 minutes, or the same source blocked for the third time.

The user is told: a notification says which address was stopped, why and for how long, and that their own devices
keep working; an attack gets its own notification. Both are also written to the log. If the hub knows a device or a
neighbouring hub at that address, the notification names it and says to connect it again afterwards — then it is most likely the
user's own device with a broken sign-in, not a stranger. There is no list of blocked sources in the interface yet;
a block ends by itself. On a TV the notification is not visible (the log has it).

## The pairing-code guard (implemented)

The six-digit pairing code (900,000 possible values) is the only secret in Safeer Link that can be guessed. Pairing
runs SPAKE2: in every round (`/cast/pair/spake`) the hub answers with its confirmation, so **one round tells the
device whether its code was right — one round is one guess**. A sign-in has five rounds; the number of sign-ins was
unlimited.

Counting per source does not stop a patient attacker. Two sign-ins with five rounds each within a minute block a
source (2 × 100 + 10 × 20 = 400), but nine rounds a minute stay below the threshold, and in a home network an
attacker can take as many addresses as it likes. One device could try several hundred codes an hour, for as long
as it wanted.

The guard therefore counts **all** guesses and **all** starts of a sign-in together, whatever the source:

| Rule | Value |
| --- | --- |
| unsuccessful guesses of the code in one hour, all sources together | 20 |
| starts of a sign-in in one hour (each shows the code on the user's screens) | 30 |
| when a limit is reached, pairing by code is **closed** | 1 hour the first time, 1 day the second, 7 days every time after that |
| limits after a closure, until 30 days have passed since the last one ended | 5 guesses, 10 starts |
| during a closure, with "Connect devices" open on a device of the circle | pairing by code works, at most 10 guesses per closure |

* **A successful pairing is not counted.** It gives back its guess and its start (the code was right), so a
  household that connects one device after another never reaches a limit.
* **While pairing by code is closed**, the start of a sign-in is refused (HTTP 429, `seznanitev_zaprta`) and no code
  appears on any screen; sign-ins that were waiting are dropped. The joining device tells its user why and what to
  do. A device that understands the answer is one with this release or newer; an older one shows its general
  "could not start" message.
* **A device of the circle can always let a new device in**: opening "Connect devices" on it is a deliberate act of
  a trusted device. Pairing by code then works even during a closure — but only for 10 guesses per closure; after
  that only the QR code works until the closure ends (the code shown in "Connect devices" is then refused — the
  interface does not say so yet).
* **The QR code is not affected**: its secret has 128 bits and cannot be guessed.
* **The state survives a restart** of the hub (a file readable only by the user on Linux, the app's private storage
  on Android). Otherwise every restart would give the attacker the full limit back.
* When the clock is set back, entries "from the future" count as made just now, and a closure never lasts longer
  than the longest one.

What this means for an attacker that never gives up: fewer than 250 guesses in a year
(`test_vztrajen_napadalec_v_enem_letu`), a chance below 0.03 % per year and per hub. The user gets a notification
at every closure: that somebody was guessing the code, from which addresses, for how long pairing by code is closed
and how to add a device meanwhile.

Limits of the guard, stated plainly:

* Every hub has its own guard. A circle of five devices gives a patient attacker five times the numbers above.
  Sharing a closure across the circle belongs to stage 3.
* Somebody in the same network can keep pairing by code closed for everyone (that is the price of not letting them
  guess). The owner still pairs with "Connect devices" on a trusted device, or with the QR code.
* The numbers are chosen from the protocol, not measured on households; a family that mistypes a code twenty times
  within an hour closes pairing by code for an hour.

## Planned (not implemented yet)

1. **Hidden advert.** The mDNS advert carries no name, fingerprint or id, only a label that changes every few
   hours and that only the circle can resolve. Advertising only in a trusted network or while the user has pairing
   open.
2. **Dark port.** A random port; a client introduces itself in the TLS handshake (SNI) with a value only the circle
   can compute. Without it the hub ends the handshake before it sends its certificate.
3. **Change of address.** On an attack the hub moves to a new port and a new label and tells the trusted devices
   over the connections that already exist; a closure of pairing by code is shared across the circle.
4. **Relay.** A changing label instead of the id from the key, several interchangeable rendezvous points.
5. **Interface.** A list of blocked sources with "Release", the state of pairing by code, and the notification on
   a TV.

Stages 1–3 need no "flag day": a hub keeps the old fields and the default port for as long as one device of the
circle does not understand the new ones.
