# Safeer Internet Gateway — protocol 2

A phone with Safeer OS can lend its internet connection to a trusted device in the same Safeer Link — for
example to a computer whose home connection is down. The phone is **not** a tethering hotspot or a NAT
router: for every TCP connection the other device asks for, the phone opens its own socket, binds it to
the Android network the user allowed (mobile data or Wi-Fi) and relays the bytes over Safeer Link.

```
program ── SOCKS5/HTTP ──► Safeer Control (computer, requester)
                                 │  internet.* messages over Safeer Link (TLS, trust circle)
                                 ▼
                           Safeer OS (phone, provider) ── bound socket ──► mobile network ──► internet
```

The phone keeps talking to the computer over Wi-Fi (Safeer Link) while the forwarded traffic leaves through
the mobile network. Android's default route is never changed and no VPN service is used.

Implementations: provider `AndroidApplicationGateway.kt` (Safeer OS Mobile); requester
`core/link_internet.py` + `core/link_internet_posrednik.py` (Safeer Control on Linux). The pure rules have
tests on both sides (`tests/InternetPretokTest.kt`, `tests/InternetPotiTest.kt`, `tests/test_link_internet*.py`);
the Python test suite contains a reference provider that follows this document.

## Who may use it

Three things must all be true:

1. the phone's user switched sharing on (Settings › Internet over Safeer Link) — only then does the phone
   advertise the capability `internet.gateway`;
2. the requesting device is a member of the phone's trust circle (authenticated Link session; the hub
   writes the real sender into every message);
3. the user allowed **that device** on the phone. The capability only says the phone *can* share; permission
   is given per device. The first request asks on the phone: a notification with Allow / Deny, and a dialog
   when Safeer OS is on screen or is opened within ten minutes of the request. (Android does not let a
   background service open a window, and notifications can be switched off for the app — the requester
   therefore tells the user to open Safeer OS on the phone.) Until the question is answered the device gets
   `permission_required`. The decision can be changed in the settings.

Mobile data is a separate opt-in, roaming another one, and a monthly limit can be set. The phone refuses
private, loopback, link-local, CGNAT and reserved destinations (a device must not reach the phone's local
network through it) and the SMTP ports 25, 465 and 587.

## Messages

All messages travel as ordinary Link messages with `target`; the hub fills in `sender`. Fields are flat so a
hub can copy them without understanding them.

| Direction | Type | Fields |
| --- | --- | --- |
| requester → provider | `internet.query` | `v`, `epoch` |
| provider → requester | `internet.status` | `payload` (object, see below); also sent unasked when settings or the permission change |
| requester → provider | `internet.open` | `stream_id` (8–96 chars), `host`, `port`, `path_id`, `v` = 2, `epoch` |
| provider → requester | `internet.opened` | `stream_id`, `path_id` (the network actually used), `kind` |
| provider → requester | `internet.error` | `stream_id`, `reason` |
| both | `internet.data` | `stream_id`, `off` (byte offset of this chunk in the stream), `data` (base64, at most 24 KiB raw) |
| both | `internet.window` | `stream_id`, `bytes` (cumulative number of bytes the receiver has consumed) |
| both | `internet.eof` | `stream_id` — this side will send no more data (half-close) |
| both | `internet.close` | `stream_id`, `reason` — the stream is gone in both directions |

`path_id` in `internet.open` says which network of the phone to use: `mobile` (mobile network only — the
default, sharing mobile data is the point), `wifi` (Wi-Fi or Ethernet with validated internet only), `any`
(validated Wi-Fi, otherwise mobile) or an exact path id from `internet.status`. `kind` is `cellular`, `wifi`,
`ethernet`, `vpn` or `other`.

`internet.status` payload: `protocol` (2), `enabled`, `permission` (`allowed`, `pending`, `denied`,
`not_trusted`). Only for an allowed device also `paths` (`id`, `kind`, `validated`, `metered`, `roaming`,
`allowed`), `cellular` (`allowed`, `roaming_allowed`, `limit_bytes`, `used_month`, `used_today`), `streams`
(`active`, `max`), `window`, `chunk`.

Reasons: `disabled`, `permission_required`, `denied`, `not_trusted`, `old_requester`, `no_path`, `mobile_off`,
`no_mobile`, `roaming`, `limit`, `busy`, `private_destination`, `port_blocked`, `dns_failed`, `connect_failed`,
`timeout`, `idle`, `gap`, `window`, `link`, `gone`, `restart`.

The host name is resolved by the phone on the chosen network, so during a home outage DNS works too.

## Flow control

A Link hub disconnects a device that does not read: its outgoing queue holds at most 64 frames and 512 KiB.
A stream that reads from the internet faster than Link drains would therefore take down the phone's whole
Link connection, not just itself (measured with protocol 1: a 20 MB download stalled after 18 MB and the
phone had to sign in to Link again). Protocol 2 bounds what is in flight:

* a sender may have at most **256 KiB and 20 frames** on the way to one device (all streams together) and
  at most **128 KiB** per stream;
* the receiver acknowledges with `internet.window` when it has consumed **32 KiB** or when its queue is
  empty. There is at most one acknowledgement per chunk, so acknowledgements cannot fill a queue either;
* every chunk carries its offset (`off`). The link between two hubs can be re-established in the middle of a
  transfer and lose a chunk without either end noticing; a chunk with the wrong offset ends the stream
  (`gap`) instead of handing a corrupted byte stream to the program.

A stream with no traffic in either direction for 5 minutes is closed (`idle`). Limits: 64 streams per phone,
48 per requesting device.

## When one side restarts or a message is lost

A stream must not hang, and a stream nobody owns must not keep the device budget:

* **Repeated acknowledgement.** A quiet stream repeats its last `internet.window` after 3 s, then after
  twice as long each time (up to 60 s). A lost acknowledgement therefore stalls a stream for seconds, not
  until the idle timeout, and the repetition doubles as a probe: “do you still know this stream?”
* **`gone`.** A side that receives `internet.data`, `internet.window`, `internet.eof` or `internet.opened`
  for a stream it does not know answers `internet.close` with reason `gone` — once per stream. The other
  side closes the stream. (It does not answer for a stream it has just closed itself.)
* **Epoch.** `internet.open` and `internet.query` carry `epoch`, a number the requester picks at start and
  changes whenever it loses Link. When the provider sees a new epoch from a device, it closes that device's
  streams of the previous epoch (`restart`). Without this, a requester that restarted in the middle of
  downloads would find the device budget still held by its old streams and could not receive anything until
  they idled out.

Measured on real devices: restarting Safeer Control or the phone app in the middle of four parallel
downloads ends the old streams within seconds and new connections work at once.

Protocol 1 (Safeer OS up to 0.5.46) had no status, windows, offsets or per-device permission. A protocol 2
requester does not use a provider that does not answer `internet.query`; a protocol 2 provider refuses an
`internet.open` without `v` ≥ 2 (`old_requester`).

## On the computer

Safeer Control runs a local proxy on `127.0.0.1` (default port 47890) that speaks SOCKS5, SOCKS4a, HTTP
`CONNECT` and plain HTTP proxying. Modes:

| Mode | Behaviour |
| --- | --- |
| off | no proxy |
| failover | home internet works → the proxy connects directly; it is down → through the phone |
| always | everything through the phone |

“Down” is decided by a probe (TCP handshakes to three independent anycast addresses, no data, no DNS); two
identical rounds in a row change the state. A connection that fails directly while the probe also fails goes
through the phone immediately, without waiting for the second round.

Programs do not find the proxy by themselves. Unless the user turns it off, Safeer sets the desktop's system
proxy (GSettings schema `org.gnome.system.proxy`) **only while traffic goes through the phone**. Firefox
follows that setting wherever the schema exists; Chromium-based browsers follow it on GNOME, Cinnamon and the
desktops Chromium treats like them, but not on Xfce or LXQt, where they read only the proxy environment
variables. Afterwards Safeer restores the exact previous values —
also after a crash (the previous values are written to disk first) and without touching anything the user
changed in the meantime. With the mode off nothing is touched. For a terminal:
`eval "$(safeerctl internet env)"`.

Destinations in the home network — private and link-local addresses, `localhost`, `name.local`, names without
a dot — always go directly, also while the internet goes through the phone. The phone cannot reach the
printer or the router (and refuses private destinations); the computer can.

The proxy listens on loopback only and accepts connections only from processes of the same user (uid from
`/proc/net/tcp`), so another user of the computer cannot spend the phone's mobile data.

```
safeerctl internet                     status
safeerctl internet mode failover       use the phone when home internet is down
safeerctl internet mode always --via "Phone" --system-proxy on
safeerctl internet test                a real request through the phone; shows which network it used
safeerctl internet env                 proxy variables for this shell
```

## Throughput

Every chunk travels as a JSON text message through the hub on the phone and the hub on the computer.
Measured through a mid-range phone on 5 GHz Wi-Fi (the phone's own Wi-Fi path): about 5.6 MB/s for one
stream, 7 MB/s for four in parallel, 5 MB/s upload; the limit is one hub thread on the phone. Measured through
the mobile network of a current flagship phone lying with its screen off, on battery (the phone stayed on
Wi-Fi with the computer and brought the mobile network up for the request in under half a second): 11 MB/s
for one stream (50 MB over TLS), 17 MB/s for eight in parallel, 1.3 MB/s upload — the uplink of the mobile
network; every file arrived identical byte for byte and the phone's own counter matched the bytes sent.
Enough for browsing, calls and video.
The next step is a direct TLS connection between the two devices for the bytes, with Link carrying only
the agreement.

## Not in this version

UDP (and with it QUIC/HTTP3 — browsers fall back to TCP), a TUN interface for programs that ignore proxies,
balancing flows across both connections at once, bonding, KDE system proxy, a requester on Windows, TV or
tablet, a direct data connection, resuming a stream after a lost chunk (a stream with a gap ends and the
program retries).
