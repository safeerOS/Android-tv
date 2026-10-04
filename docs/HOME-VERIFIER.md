# Safeer Home Verifier — one device asks the add-ons for the whole home

Safeer shows a film or a series in a list only after an add-on has confirmed that it can be played. Every such
confirmation is a request to the add-on, and an add-on counts requests **per home address, not per device**.
While every device asked for itself, each stayed inside its own polite limit — and three devices together
went over it. The add-on then limited the whole home and, for a while, did not even return a stream for the
film somebody actually wanted to watch (measured on 4 October 2026).

So in a home only **one device asks**: the *verifier*. The other devices tell it which titles they need and
get the answer from it.

```
phone ──┐                                   ┌── add-on A
tablet ─┼─ avail.get (Safeer Link) ─► verifier (TV) ─┼── add-on B
…      ─┘        ◄─ records ──────────       └── …
```

What this gives:

- **one request per title for the whole home** — two devices with the same list never ask twice, not even
  when they open it at the same moment;
- **one request budget for the whole home** — the verifier's token bucket (4 at once, then one every 8 s per
  add-on) is the home's budget, however many devices are browsing;
- **whoever is waiting goes first** — titles of a list that is on a screen right now (*window*) are always
  ahead of work *in stock*;
- **lists are ready before you get to them** — titles behind the window and answers that are no longer fresh
  are verified in stock: slowly, and only while nobody is waiting. By the time the user scrolls, taps "Load
  more" or comes back tomorrow, they are known.

Implementation: `os/DomPreverjanjePravila.kt` (pure rules, `tests/DomPreverjanjePravilaTest.kt`),
`os/DomPreverjanje.kt` (both roles), `os/Razpolozljivost.kt` + `os/RazpolozljivostPravila.kt` (the records).

## Who verifies

Every Safeer OS app (TV, tablet, phone) announces the capability `avail` when it registers in Safeer Link.
Among the devices with that capability the verifier is the one that is switched on the longest:

1. TV, then tablet, then phone;
2. with equal rank, the smaller device id.

Every device computes this from the same list of connected devices, so they all arrive at the same answer
without talking about it. A device that comes out on top verifies in its own queue; any other device asks
the one on top. Safeer Predvajalnik can ask but never verifies for others.

A verifier that cannot do the job is skipped for five minutes and the next one is asked: when it does not
answer twice in a row, when it has different add-ons (see *fingerprint*), or when it is on a metered
network. When nobody is left, the device verifies for itself, exactly as before this protocol existed.

## What goes through the verifier

Only films, series and episodes with a public id: keys `movie|tt…`, `series|tt…` and `series|tt…:S:E`.
Titles from a private add-on's own catalogue never leave the device — it verifies those itself.

A record is not "yes/no" but two bounds relative to what a device can do with torrents (see
`RazpolozljivostPravila`), so one answer is valid for every device and every mode. Records travel as
`from;timeFrom;upTo;timeUpTo` strings, the same format devices already use to share them (`lists.get`).

The verifier only answers a device with **the same set of stream add-ons**. The set is compared by a
fingerprint (a hash of hashes of the add-on addresses); addresses, which can contain tokens, are never sent.

Privacy: a device tells the verifier which titles its list contains. Both are the user's own devices in the
same trust circle, and the keys are public catalogue ids. Nothing goes to any server.

## The message

`avail.get` is an ordinary Link command (`control.command` → `control.result`) addressed to the verifier.

Request `params`:

| Field | Meaning |
| --- | --- |
| `odtis` | fingerprint of the asking device's stream add-ons |
| `meja` | what the asking device can do with torrents: `M` = any torrent, a number = bytes, `0` = direct streams only |
| `okno` | keys somebody is waiting for (at most 30) |
| `naprej` | keys to verify in stock (at most 90) |
| `cakaj` | how long the verifier may hold the answer waiting for the first new record, ms (at most 4000) |

Answer `data`:

| Field | Meaning |
| --- | --- |
| `odtis` | the verifier's fingerprint |
| `napaka` | `odtis` (different add-ons), `dodatki` (no stream add-ons), `omrezje` (metered network) — nothing else is returned |
| `zapisi` | `{key: record}` for every requested key the verifier has a **fresh** answer for, judged by the asking device's `meja` |
| `caka` | how many of the `okno` keys are still queued or being verified |
| `neznano` | `okno` keys the verifier has just failed to verify (the add-on did not answer); do not wait for them |
| `premori` | `{add-on fingerprint: ms}` — add-ons that told the home to slow down; every device waits |

It is a long poll: if the verifier has none of the `okno` keys yet, it holds the answer until the first of
them is verified (or `cakaj` runs out). The device repeats the request while its list is waiting. **A key is
wanted for 15 seconds after it was last requested** — a device that leaves the screen simply stops asking,
and the verifier stops spending requests on it; the key moves to stock.

The command is answered on a worker thread, never on the main thread, and is not logged per request.

## The verifier's queue

- A title is in the queue once, however many devices want it. The check runs with the weakest capability
  among the devices waiting: it may stop at the first add-on with a stream that device can play, otherwise
  it waits for all add-ons, and the record then answers the question for every device.
- Stock is always verified completely (all add-ons), so the record serves every device later.
- A failed check (no answer, add-on paused) is not repeated for 60 seconds; the waiting devices are told
  (`neznano`).
- Two workers; at most one of them works on stock, and it gives way the moment a window title arrives —
  also while it is waiting for a token (the unsent request costs nothing, the token is returned).

Stock is verified only when **all** of this holds:

| Condition | Value |
| --- | --- |
| the bucket is nearly full | at least 3 of 4 tokens — tokens are for users first |
| since the last stock request | 20 s |
| since the last user action (a stream request for playback, a window title) | 30 s |
| since an add-on last limited the home | 2 h |
| the hour's budget (below) | less than a third used |
| per day | at most 600 requests |
| the device has power | mains or enough battery, not playing, unmetered network (the same rule as helping other devices) |
| the device is the verifier | a device that asks another one does not verify stock itself |

Stock is bounded (400 titles) and forgotten after 12 hours.

## The home's hourly budget

A short pause between requests is not enough. An add-on also counts how many requests a home sends over a longer
time, and it does not publish that number: on 4 October 2026 a single device asking once every eight seconds was
limited again after a longer browsing session. The verifier therefore keeps a budget per add-on
(`os/TempoDodatka.kt`, `tests/TempoDodatkaTest.kt`):

- every stream request of the last hour is counted, playback included;
- background verification may use up to 150 requests an hour — whatever is left up to the add-on's own limit
  stays free for playback;
- stock may only use the first third of that;
- when the budget is used up, background verification waits until the count of the last hour has dropped to three
  quarters of it. Lists show the titles that are already verified, the other devices in the home wait as well
  (`premori`), and playback keeps working;
- when the add-on limits the home anyway, the budget is halved at once — based on what was really sent in the
  last hour, never below 30 — and comes back by a quarter for every day without a limit;
- when the add-on says how long to wait (`Retry-After`, `RateLimit-Reset`), the pause lasts exactly that long;
  otherwise 15 minutes, doubling up to two hours.

The count and the learned budget survive a restart of the app. The log (`SafeerStremio`) records the HTTP status
and the rate-limit headers of a limiting answer, never the request.

## Not covered yet

- Safeer OS for computers still verifies for itself and does not ask the verifier.
- The verifier does not load catalogues on its own; it verifies what devices have asked for.
