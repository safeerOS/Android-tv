# Torrent engine: restart without re-reading what is already downloaded

Safeer OS plays and downloads torrents with libtorrent (`os/MagnetMotor.kt`). A film that was watched stays on the
device for a while (48 hours, or until removed when the user chose "Keep"), so the engine has to pick its torrents
up again every time it starts.

## What went wrong

Up to 2.1.171 the engine added every saved torrent again **without resume data**. libtorrent then has no other
choice than to read and hash every kept file from start to end. On a phone with 4 GB of memory and 7 GB of kept
films this happened every time the Video page was opened after the app had been restarted: the device stopped
responding for more than a minute and the system killed the app (measured 4 October 2026). The metadata of each
torrent was also fetched from the network again on every start, so a kept film from a dead swarm disappeared
from the shelf.

## What the engine does now

For every saved torrent two small files live in the app's private storage (`files/magnet/`):

| File | Content | Written |
|---|---|---|
| `<hash>.torrent` | the torrent's metadata | once, when the metadata is first fetched or the user shares a file |
| `<hash>.resume` | libtorrent resume data (which pieces are present, priorities, state) | every 30 s while something changes, on pause and when a download finishes |

Both are written atomically (temporary file, then rename). File names are built only from a validated 40-digit
hex hash. Files that belong to no saved torrent are removed at engine start; unknown files are left alone.

On start each torrent is added **with** its resume data: nothing is read from disk, nothing is fetched from the
network, and a kept film is ready to play in about a second. Resume data is ignored when none of the selected
files exists any more.

A finished torrent that the user does not share stays paused across restarts. Modes the app never chooses itself
(upload-only after a disk error, stop-when-ready) are not carried over into a new start.

## Torrents without resume data: gradual check

The first start after the update (or a lost resume file) still needs one check of the files on disk. It is done
gently (`ObnovaPravila`):

* one torrent at a time, added paused and outside libtorrent's own queue;
* the check runs for 300 ms, then rests for at least 700 ms; the rest grows (up to 5 s) when the checking thread
  itself is delayed — a sign that the device is stalling — or when the system reports low memory;
* progress is saved every 30 s, so a check interrupted by a restart continues instead of starting again;
* a torrent that somebody is playing right now is checked with short rests, so playback can start;
* afterwards the torrent continues as before: an unfinished download runs on, a finished one stays paused.

Measured on the same phone: 7 GB checked in 229 s with the device responsive throughout (command latency at most
0.5 s; before: 20 s timeouts and the app killed).

### Resume data saved during a check

Resume data written *while* a check is running records how many pieces have been checked, but the last few of
them — the ones that were still being hashed — are stored as "not present". Taken literally, the check would
continue after them and the engine would download those pieces again although they are on disk (seen in 8 of 12
interrupted checks, 1–7 pieces each). On restore the partial state is therefore cut at the first gap within its
tail, and the check continues from there. With that, five of five interrupted checks ended complete and nothing
was downloaded again.

## Other safeguards

* A finalizer of a native object that misses the system deadline during a device stall is logged instead of
  crashing the app (`FinalizerWatchdogDaemon`); every other uncaught exception is handled as before.
* Playback is taken out of libtorrent's download queue, so a film starts even when several downloads are
  already running.
* The temporary torrent that the library uses to fetch metadata has the same hash as the real one; its state is
  never stored as the real torrent's state.

## Testing on a device

`adb shell setprop debug.safeer.torrent_brez_stanja 1` makes the next engine start behave like the first start
after the update (resume data removed, downloaded files untouched); `2` also removes the stored metadata. Only
adb can set `debug.*` properties. Set it back to `0` afterwards. Log tag: `SafeerMagnet`.
