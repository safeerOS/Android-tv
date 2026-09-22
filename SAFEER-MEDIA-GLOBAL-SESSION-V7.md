# Safeer Media v7 — Global Media Session + Adaptive Home

- Background audio remains a single Safeer playback session.
- The Safeer OS media card exposes Play/Pause, Next (only when available) and Stop.
- The Android media notification exposes the same controls, so audio can be stopped or advanced without reopening Media.
- Stop ends the playback session and releases the hidden web player/WebView rather than merely pausing it.
- Video continues to use Continue Watching instead of pretending to be background music.
- Empty dynamic rows are removed.
- Genre rows from user web sources require at least 3 items; sparse items remain discoverable in broader Movies/Series/For you rows.
- Empty PeerTube/source rows are not rendered.

The source website remains a provider/runtime. Safeer does not bypass DRM, authentication, or access controls.
