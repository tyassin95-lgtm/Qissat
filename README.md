# Qissat

A dedicated, minimal Android app for watching the Turkish shows on **https://www.qisat.tv** —
one app for **Android phones, Android TV / Google TV and Amazon Fire TV / Fire Stick**.

It is a locked-down browser: it can only show qisat.tv, it blocks the ads / pop-ups / pop-unders /
redirects the site and its players inject, it plays video either in the site's own player or in a
native Media3 (ExoPlayer) player, and it is fully usable with a TV remote.

* Package: `tv.qissat.app` · min Android 5.0 (API 21) · target Android 15 (API 35) · Kotlin, no Compose, ~1.3 MB release APK
* Phones: normal touch browsing. TV / Fire TV: remote cursor on by default, TV launcher icon + banner.

## Install

Pre-built, ready-to-install APKs are in [`dist/`](dist/):

| File | Use |
|---|---|
| `Qissat-1.0.1-release.apk` | **Recommended.** Optimised (R8), signed. |
| `Qissat-1.0.1-debug.apk` | Debuggable build, installs *next to* the release one (`tv.qissat.app.debug`). |

**Android phone / tablet** – copy the APK to the phone (or download it from this repo), open it and allow
"Install unknown apps" for your file manager/browser when asked. Or: `adb install Qissat-1.0.1-release.apk`.

**Android TV / Google TV** – easiest: put the APK on a USB stick or cloud drive and open it with a file manager
app (e.g. *Send Files to TV*, *File Commander*), or `adb connect <tv-ip>:5555 && adb install Qissat-1.0.1-release.apk`
(enable *Developer options → USB/Network debugging* first). Allow "Install unknown apps" for the installer app.
Launch **Qissat** from the apps row.

**Amazon Fire TV / Fire Stick** – *Settings → My Fire TV → Developer options → Install unknown apps* → enable for
**Downloader**. Install the *Downloader* app, open it and enter the direct URL of the APK
(e.g. the raw GitHub link to `dist/Qissat-1.0.1-release.apk`), install, then open **Qissat** from *Your Apps & Channels*.
Or with ADB: *Developer options → ADB debugging on*, then `adb connect <fire-ip>:5555 && adb install Qissat-1.0.1-release.apk`.

## Using it

**Phone** – browse exactly like a mobile browser. Back goes back; on the home page press Back twice to exit.
Tapping play on an episode opens the native player (landscape, full-screen); Back returns to the episode page.

**TV remote**

| Button | Action |
|---|---|
| Arrows | Move the on-screen cursor (accelerates while held; push against the top/bottom edge to scroll) |
| OK / Select | Click. Hold OK and move the arrows to drag (e.g. scrub a slider) |
| Back | Close fullscreen → go back in the site → (on home) press twice to exit |
| Menu (≡) or long-press Back | Small menu: Home · Reload · Cursor on/off · Native player on/off · Mute video · Exit |
| Play/Pause, FF, Rewind, Next/Prev | Control the web player (±10 s / ±60 s); in the native player they control playback directly |
| Channel/Page up/down | Scroll a screenful |

Turn the cursor off in the menu to use plain focus navigation (arrow keys jump between links).

**Native player** (opens when you press play; it is the stream the site's player was about to play) –
OK toggles play/pause, Left/Right seek ±10 s (hold to keep seeking), Up/Down show the controls. The controls bar has
speed, quality (HLS), audio, subtitles, mute and picture size (fit / zoom / stretch). Where you stopped is remembered per episode.
If a stream cannot be played natively, the app silently hands over to the site's own web player
(menu → *Native player: Off* forces the web player always).

## How it works

```
app/src/main/java/tv/qissat/app
├── MainActivity            single WebView host: back, fullscreen video, menu, errors, key routing
├── adblock/                UrlPolicy · Rules · HostUtil  – pure Kotlin, unit-tested
├── web/                    WebView client/chrome client, popup sink, shield injector, media sniffer
├── input/                  CursorView · CursorController · DeviceType
├── player/                 PlayerActivity (Media3), PlayRequest, PlaybackStore
└── ui/MenuDialog
app/src/main/assets
├── shield.js               injected into every frame (ES5)
└── adblock/*.txt           blocked_hosts · blocked_paths · frame_providers  (edit to tune the filter)
```

**Site only.** Top-level navigation is allowed only for `qisat.tv` and subdomains (http is upgraded to https). Everything
else — social links, `intent:`/`market:`/`mailto:` links, `data:`/`file:` — is refused with a short message and never handed to
another app. Certificate errors are never bypassed.

**Playback hosts without browsing them.** qisat.tv episodes load `w.anaplayer.online`, which frames one of several video hosts
(`cdnplus`, `mp4plus`, `anafast`, `vidoba`, `vidspeed`, …) that rotate TLDs constantly (`cdnplus.sbs`, `cdnplus.space`, …).
Frames are therefore allowed by provider *brand* (`frame_providers.txt`, any TLD). Player-wrapper pages are recognised by name
(`anaplayer`, `shooof`) or by the plugin's `/albaplayer/` URL shape, so a wrapper added later (newer episodes go
qisat → anaplayer → shooof.site → provider) works without an app update, and whatever a wrapper frames is accepted unless
it is on the ad lists. Frames the site itself places must look like players; unknown frames nested inside a provider page
(where ad iframes live) are refused. Scripts, media, XHR and
images are allowed by default unless they hit the ad host list or an ad path signature, so the CDNs/segment hosts a player
needs never have to be enumerated and can rotate freely.

**Ad / pop-up defence in depth**

1. *Network layer* – 280+ ad, pop-under, push and tracker domains (`blocked_hosts.txt`) and path signatures (`/popunder`, `/vast.xml`, …) get an empty response.
2. *Window layer* – `window.open` / `target=_blank` windows are routed into a throw-away sink WebView; only same-site targets from a real user gesture are re-opened in the main view. Too many at once are refused.
3. *Page layer (`shield.js`, every frame, at document start)* – `window.open` replaced with an inert stub, off-site / new-window link clicks and detached-anchor clicks inside third-party frames are cancelled, invisible full-screen click-jacking overlays are removed (visible player UI is never touched), ad containers hidden.
4. *Anti-adblock compatibility* – the players check bait globals (`cRAds`, `adbon`, …) and nag or throttle playback when an ad script "is missing". `shield.js` defines them so playback is not degraded by blocking.
5. `mediaPlaybackRequiresUserGesture` stops hidden autoplaying ad videos; JS `alert/confirm/prompt` from third parties and "leave page?" traps are suppressed; all permission requests are denied.

**Media control and native hand-off.** `shield.js` also relays play/pause/seek/mute from the remote's media keys down through nested
frames to the `<video>`, and reports (via a nonce-protected request to a local pseudo-host that never reaches the network)
when a real video starts playing. The app matches that with the HLS/DASH/MP4 URL the player requested (`MediaSniffer`), pauses the
web player, and opens `PlayerActivity` with the right `Referer`/`Origin`/cookies/User-Agent. Media3 handles HLS, DASH and
progressive streams, adaptive quality, and recovers from network errors (retries with back-off, then offers Retry / Web player).

**Low-end devices.** Native views (no Compose/AppCompat), one WebView, R8 + resource shrinking, baseline profile, smaller
Media3 buffers on low-RAM devices, no analytics, ad scripts never downloaded.

**Resilience.** Offline / timeout / SSL / 5xx error screen with Retry (auto-retries when the network returns), page-load watchdog that
does not cover usable pages, WebView renderer crash recovery, state restored after process death, rotation/fullscreen handled without recreating the page.

## Build

Requirements: JDK 17+, Android SDK 35 (`sdkmanager "platforms;android-35" "build-tools;35.0.0"`).

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew testDebugUnitTest lintRelease assembleDebug assembleRelease
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk   (signed with keystore/qissat-release.jks)
```

`keystore/` holds a self-generated key so the release APK installs and updates consistently. It is committed for convenience
of a personal sideload build; **generate your own key before publishing anywhere**.

## Tests

* `./gradlew testDebugUnitTest` – 86 JVM tests: URL/ad policy against real site traffic, media sniffer, beacon parsing, cursor
  (Robolectric), both activities (launch, menu, back, fullscreen, errors, retry, timeout), popup sink, end-to-end
  "stream sniffed → play beacon → native player launched", unplayable stream → web-player fallback.
* `cd tools/shield-test && npm i && npm test` – runs `shield.js` in real headless Chromium against a hostile page
  (pop-ups, click-jacking overlays, nested frames, command relay, beacons).

## Honest limits

* Built and verified in a headless environment without an Android emulator or device: the app was exercised through the unit,
  Robolectric and browser-engine tests above, lint, and a signed release build — **not on physical phones / TVs**.
  Please do a quick smoke test on your devices (open an episode, press play, fullscreen, Back, remote cursor).
* Ad lists age. New ad domains slip through until added to `blocked_hosts.txt`; the pop-up, overlay and navigation defences work regardless of the source.
* Very old Fire OS (Fire OS 5 / API 22 sticks) ship an outdated system WebView: the site's own player may not work there at all, and the
  per-frame shield falls back to main-frame injection. Native player and navigation locking still work.
* The app depends on the structure of qisat.tv and its video hosts; if they change radically, the allow-lists in `assets/adblock/` are the place to adapt.
