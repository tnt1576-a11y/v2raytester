# V2Ray Tester — Android

A test-only Android app that finds **which of your V2Ray configs actually work on
your network** — real HTTP latency to Google through each proxy, plus a **Sites**
column showing whether each working proxy can reach YouTube / Instagram / Telegram
/ OpenAI. It does **not** route your traffic (no VPN permission); it just tests and
lets you export the good configs.

It's a Kotlin/Jetpack-Compose rewrite of the desktop `v2raytester`, reusing the same
parsing, Xray config generation, and test flow.

## Install (no Python, no v2rayN needed)

The app **bundles the Xray core**, so the APK is self-contained.

- Grab `v2raytester.apk` from the
  [latest release](https://github.com/tnt1576-a11y/v2raytester/releases) (tags
  `android-v*`) or from a green run's **Artifacts** under the repo's Actions tab.
- Enable "install unknown apps" for your browser/file manager, then open the APK.
- Requires a 64-bit (arm64) device — i.e. essentially every modern phone.

## Use

1. Paste configs, **Load file**, or **Subscription → Fetch All** (a curated list
   ships in the app; edit it freely).
2. **Test All**. With *Skip dead* on it TCP-pings first and only full-tests the
   reachable ones. Results stream into **All** / **Working ✓** tabs.
3. Tap a row for *Retest / Copy / Share / Delete*; **Share working** exports the
   passing configs (fastest first) to any app.

Supported: VMess, VLESS (incl. REALITY), Trojan, Shadowsocks, SOCKS, HTTP,
WireGuard. Hysteria2/TUIC/AnyTLS are listed as `unsupported` (sing-box only).

## How it works

For each config: generate a tiny Xray JSON (local SOCKS inbound + that proxy),
run the bundled `libxray.so` on a free port, then make an HTTP request through that
SOCKS port with OkHttp and time it. Reachability + exit-IP/geo reuse the same proxy
while the core is alive. Many run in parallel; lower the thread count if your phone
struggles.

## Build it yourself

CI (`.github/workflows/android.yml`) builds the APK: it runs the JVM unit tests,
downloads the pinned Xray arm64 binary into `app/src/main/jniLibs/arm64-v8a/libxray.so`,
then `assembleRelease`.

Locally (Android Studio Koala+ or CLI with the Android SDK):

```
# place the xray arm64 binary first (CI does this automatically):
#   app/src/main/jniLibs/arm64-v8a/libxray.so   <- the 'xray' binary, renamed
./gradlew :app:assembleRelease         # or open the android/ folder in Android Studio
```

Notes:
- The core binary is **not committed** (fetched at build time); see the workflow.
- The release build is signed with the local debug key — fine for sideloading,
  not for the Play Store (which disallows the bundled executable anyway).
- arm64-v8a only; add `Xray-android-amd64` to `jniLibs/x86_64` if you need an
  emulator build.
