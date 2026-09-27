# Safeer Media – structured metadata (v5)

Safeer Media treats a user-added web app as a media source, not as the primary UI.

For rendered result pages `SpletniVir` now reads standard `application/ld+json` data when the source publishes it. Supported schema.org types include `Movie`, `TVSeries`, `TVSeason`, `TVEpisode`, and `VideoObject`. The runtime can retain genre, publication year, season/episode numbers, and IMDb/TMDB identifiers exposed by the page itself.

The metadata is advisory: Safeer never invents an ID or media type. When structured metadata is absent it falls back to the existing conservative URL/title classification.

Duplicate video cards prefer stable IDs (IMDb, then TMDB) and otherwise use normalized title + year + type. This reduces accidental merging of unrelated titles with the same name.

No DRM, authentication, paywall, or access-control bypass is performed. A source that requires its own web playback remains on the existing background-web/fallback path.
