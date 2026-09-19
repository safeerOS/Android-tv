# Safeer TV Browser — Privacy

Updated: 17 September 2026

Safeer is a general-purpose browser. It does not include a user account, media subscription, private credentials or a developer's browsing data.

Bookmarks, history, browser settings, website cookies and website storage are stored on the device. Websites receive requests, your IP address and any information you submit to them. Your selected search service receives your searches. Browser data is excluded from Android backup in this release.

The browser downloads filter lists from external security-list providers (abuse.ch, Phishing Army, HaGeZi, SI-CERT, EasyList); those providers receive the normal network information required for that download. Local filtering cannot identify every threat or advertisement. Safeer does not promise anonymity.

Safeer Shield (off until you turn it on) filters DNS for the whole television. To see DNS queries it registers a local VpnService, and Android shows the key icon while it runs. This is not a VPN in the usual sense: no tunnel to any server is opened and no traffic is routed anywhere. The only route inside it is to a virtual DNS address on the device itself (10.111.222.2 / fd66:5afe:e2::2); the domain list is a file on the television and the decision to block is made there. Blocked names are answered locally with NXDOMAIN and go no further. Allowed queries are forwarded, unchanged, to the DNS resolvers your network provides — the same ones the television would use anyway; only if the network offers none do they go to 1.1.1.1 (Cloudflare) or 9.9.9.9 (Quad9), which then see those queries as they would from any device. Nothing about your browsing is sent to us: Safeer operates no DNS service and no logging service. The Shield counts how many queries it blocked, on the device, for the notification and the Safeer OS card. Its filtering has limits: an app that uses its own DNS-over-HTTPS or DNS-over-TLS, or connects straight to an IP address, does not pass through it, and a blocked domain can still be reached by other means. Turning the Shield off removes the VpnService; another VPN app takes it over, and the Shield then reports that it was interrupted.

SponsorBlock (on by default, can be switched off in the menu) skips sponsor segments in YouTube videos using the community database at sponsor.ajay.app. The browser never sends the video ID: it requests the first four characters of the ID's SHA-256 hash, receives the segments of every video sharing that prefix and picks the right one on the device. These requests are anonymous and carry no cookies.

Downloads go to the device's Downloads area through Android DownloadManager. On Android 9, saving public downloads may require storage permission. Microphone permission is used for user-initiated voice features where supported; web pages are not automatically granted microphone, camera or location access. Protected-media identifiers may be used by DRM-enabled playback requested by the user. External providers control their own accounts, cookies and privacy policies.

No analytics SDK is included. Release builds disable the developer command receiver and SafeerDbg diagnostic logging. The project does not operate a browser-history collection service.

Privacy questions and issue reports: https://github.com/safeerOS/Android-tv/issues
