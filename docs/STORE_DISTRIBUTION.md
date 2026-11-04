# Safeer TV — public distribution

Default home tiles, bookmarks and portal lists contain public services only. No pirate catalog is preselected or advertised. Users can enter their own addresses and manage their own bookmarks; this is not a website blocklist. Existing user bookmarks are retained.

Production assets exclude authentication files, local developer files and screenshots. The public build cannot embed credentials for any service. Users sign in themselves to services they subscribe to. Developer command broadcasts and diagnostic logging through SafeerDbg are disabled in release builds. Android backup is disabled for browser data. Web pages are not automatically granted camera, microphone or location access; protected-media playback remains supported.

## Packages

- `./build_tv_apk.sh`: signed APKs for TV (Safeer TV Browser `si.safeer.tv` and Safeer OS), signed with the Safeer TV
  release key (certificate SHA-256 `E7808780C6F492ADC60036BE680667CF74BDE44164B5C63179E1D0F7BDFD7E16`), with `SHA256SUMS`.
  The install scripts (`install_tv_browser.sh`, `install_safeer.sh`) check both the checksum and this signer before
  installing.
- `./build_store_packages.sh` is retired (weekly review 4 Nov 2026): it used non-existent output paths, a fixed
  version 2.1.82 and the mobile browser's key. An Android App Bundle for Play Console is not built until a Play
  submission is decided; it must then be signed with the TV key above (or a Play upload key enrolled for it), never
  with the mobile key. Never commit keys or passwords.

Keep the same signing key for all future releases: Android does not install a package signed with a different key
over an existing installation. Do not uninstall an existing browser without first preserving the user's bookmarks and
other data.

## Submission information

Name: Safeer TV Browser

Short description: Web browsing for Android TV with remote navigation and bookmarks.

Description: Browse websites on Android TV using your remote. Safeer includes tabs, editable bookmarks, a search/address bar, local ad and threat filters, and video playback for supported websites. No films, channels, subscriptions or third-party accounts are included. Access to external services may require your own account or subscription. Website compatibility and filtering coverage vary.

Before submission, complete the store's privacy/data-safety, content rating and app-access forms based on actual behavior, supply screenshots from public neutral pages, and run the store's device testing. Store acceptance is decided by its reviewer; these packages do not represent approval.

Target API 34 meets the currently published Android TV exception: https://support.google.com/googleplay/android-developer/answer/11926878?hl=en
