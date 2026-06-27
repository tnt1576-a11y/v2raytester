# V2Ray Config Tester

A small Python/tkinter desktop app that tells you **which of your V2Ray configs
actually work on your current network**. For each config it starts the proxy
locally and measures the real HTTP latency (ms) to Google through it — same idea
as v2rayN's own latency test.

> 📱 **Android app:** there's now a self-contained Android version (Kotlin/Compose,
> Xray core bundled — no v2rayN needed). See [`android/`](android/) and grab the APK
> from a release tagged `android-v*`.

## Download (no Python needed)

Grab the standalone Windows build from the
[**latest release**](https://github.com/tnt1576-a11y/v2raytester/releases/latest):

1. Download `v2raytester.exe`.
2. Put it **inside your v2rayN folder** (the one containing `bin\xray\xray.exe`
   and `bin\sing_box\sing-box.exe`) — the app reuses those cores.
3. Double-click to run.

Or run from source (below).

## Install

This app reuses the `xray` and `sing-box` binaries that ship with
[**v2rayN** (2dust)](https://github.com/2dust/v2rayN). Its only Python dependency
is **customtkinter** (for the modern UI); everything else is standard library +
the system `curl`.

1. Install the UI dependency:

   ```
   pip install customtkinter
   ```
2. Copy these files into your existing **v2rayN folder** (the one that contains
   `bin\xray\xray.exe` and `bin\sing_box\sing-box.exe`):
   - `v2ray_tester.py`, `parsers.py`, `cores.py`, `tester.py`
3. Run it:

   ```
   python v2ray_tester.py
   ```

The bottom status bar shows the detected core versions (e.g.
`xray 26.2.6 | sing-box 1.12.21`). Discovery is version-tolerant: it handles the
different `bin\` layouts v2rayN has used across releases and falls back to a
recursive search.

### Build the .exe yourself

```
pip install pyinstaller customtkinter
pyinstaller --onefile --noconsole --name v2raytester --collect-all customtkinter v2ray_tester.py
```

The result is `dist\v2raytester.exe`.

## Usage

1. Add configs: paste share links into the big box (one per line), use **Load File**,
   or click **Load from Subscription**.
2. Click **Test All**. With **Skip unreachable** on (default), it first TCP-pings
   every node (fast, no core spawn), drops the ones that aren't even reachable, and
   only full-tests + shows the rest — turning a multi-hour run on a huge list into a
   short one. Results then stream into the table live:
   - **Ping** — direct TCP connect time to the server (independent of the proxy)
   - **Latency** — real HTTP delay through the proxy, color-graded
     (bright green < 150 ms, lime 150–350, amber > 350)
   - **Sites** — real-destination reachability through the proxy (when *Test
     sites* is on): e.g. `YT✓ IG✓ TG✗ AI✓`. A config can pass the Google test
     yet still be blocked/geo-restricted for the sites you actually want — this
     column shows what each working proxy can really reach. Edit the target list
     with **Edit…** (defaults: YouTube, Instagram, Telegram, OpenAI).
   - **Exit** — the proxy's exit country + IP (when *Exit IP/Geo* is on)
   - **Status** — `online` / `timeout` / `failed` / `unsupported`; hover a failed
     row to see the full core/curl error
3. The results have two tabs: **All Results** and **Working ✓**. The Working tab
   fills in live as configs pass, and every working config is **auto-saved** to
   `working.txt` next to the app (rewritten fastest-first when the run ends).
4. **Right-click any row** (either tab) for: *Retest*, *Copy link*,
   *Open server in browser*, *Delete row*.
5. Click a column header (or **Sort by Latency**) to sort. Working nodes auto-pin
   to the top when a run finishes.
6. **Copy Working** / **Export Working…** also outputs only the configs that
   reached Google, fastest first.

Duplicate configs (same server/port/credential, even under different aliases) are
collapsed automatically on parse — or click **Remove Duplicates** to clean the
loaded list in place. Settings (test URL, timeout, threads, geo + skip-unreachable
toggles) and your config box are saved to `v2raytester_config.json` next to the app.

### Large lists

Parsing handles hundreds of thousands of configs, but a list that big from merged
subscriptions is almost all duplicates — click **Remove Duplicates** first. Keep
**Skip unreachable** on so dead nodes are eliminated by a cheap ping instead of a
full (multi-second) core test.

The app is tuned for big lists:
- the pre-filter **pings each unique `server:port` once** with a fast **async
  probe** (hundreds/thousands of connections in flight at once), and DNS is
  **resolved once per host and cached** — so the ping phase is fast even on
  hundreds of thousands of configs;
- the full test **fails fast** when a core rejects a config (no waiting the whole
  start window), reuses the ping from the pre-filter, and batches UI updates so
  the window stays responsive and **Stop halts within a second or two**;
- the **Working ✓** tab and `working.txt` stay small and fast regardless of input
  size — for a giant list, watch the Working tab rather than All Results.

**Tuning for speed:** the slowest part is the full proxy test, where each
*reachable-but-dead* node costs up to the **Timeout** you set. A working proxy
reaches Google in 1–2s, so a **Timeout of 4–5s** is plenty and caps the dead ones.
Raise **Threads** to **32–64** for many parallel tests (each test spawns a core
process, so going much higher mostly just loads your CPU). Always **Remove
Duplicates** first and keep **Skip unreachable** on.

Note: the All Results table is a plain widget, so auto-sort there is skipped above
~20k rows (the Working tab always sorts).

### Subscriptions

**Load from Subscription** opens a window with a bulk URL list (one per line),
**Fetch All** with a progress bar, and **Abort**. The list is stored in `subs.txt`
next to the app — edit it freely; it's saved back whenever you fetch. All fetched
configs are decoded, merged into the main box, and deduplicated.

## Supported protocols

| Tested via **xray** | Tested via **sing-box** |
|---|---|
| VMess, VLESS (incl. REALITY + xtls-rprx-vision), Trojan, Shadowsocks, SOCKS, HTTP, WireGuard | Hysteria2, TUIC, AnyTLS |

Transports: raw/tcp, ws, grpc, kcp, httpupgrade, xhttp. Security: none / tls / reality.

**NaïveProxy** is parsed and listed but reported `unsupported` — none of the
bundled cores (xray / sing-box) implement a naive client.

## How it works

For each config: parse the share link → generate a tiny core JSON with a local
SOCKS inbound + that proxy as the only outbound → start the core → run
`curl --socks5-hostname 127.0.0.1:<port> http://www.gstatic.com/generate_204`
and time it → kill the core. Many run in parallel on separate ports.

## Notes

- The default test target is `http://www.gstatic.com/generate_204` (success =
  HTTP 204). You can change it in the *Test URL* box.
- **Exit IP/Geo** runs a second request through each working proxy to
  `ip-api.com` to report the exit IP + country. Turn it off (checkbox) for faster,
  fully-local runs.
- This drives proxy cores against your own configs; nothing is uploaded anywhere
  except the optional geo lookup above.
