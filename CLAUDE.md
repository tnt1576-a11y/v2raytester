# V2Ray Config Tester — Project Context

Tells you **which of your V2Ray configs actually work on your current network**: for
each config it starts the proxy locally and measures real HTTP latency to Google
through it, plus a **Sites** reachability matrix (can this proxy actually reach
YouTube / Instagram / Telegram / OpenAI?) and the exit IP / country.

> This is a standalone project at `C:\Users\jonbo\OneDrive\Desktop\v2raytester`.
> It is **unrelated to the `discordw` project** (the WoW Boost Sniper) — do not
> conflate them or cross-write memory/config between the two.

GitHub: **tnt1576-a11y/v2raytester**. Two deliverables share the same idea:

## 1. Desktop app (Python / tkinter) — repo root

```
pip install customtkinter        # only dep; uses system curl + v2rayN's cores
python v2ray_tester.py
```

Reuses the bundled `xray` / `sing-box` from an existing **v2rayN** install (drop the
files into a v2rayN folder; discovery is version-tolerant). Files:

- **`parsers.py`** — share-link parsers (vmess/vless/trojan/ss/hysteria2/tuic/anytls/
  socks/http/wireguard/naive) → normalized `node` dict; `decode_subscription`,
  `dedupe`, `parse_links`, `stripComments`.
- **`cores.py`** — `core_path`/`core_version` discovery + `build_xray_config` /
  `build_singbox_config`. `CORE_FOR_TYPE` routes protocol → core. naive = unsupported.
- **`tester.py`** — engine: async TCP-ping prefilter (endpoint-dedup + DNS cache),
  `test_node` (spawn core → curl through local SOCKS → 204 latency → reach + geo →
  kill), `run_tests`. `DEFAULT_REACH_TARGETS`, `_reachable` code logic.
- **`v2ray_tester.py`** — the GUI (config box, subscriptions, All/Working tabs with
  the Sites column, latency gradient, sort, right-click actions, autosave working.txt).
- Runtime files (gitignored): `v2raytester_config.json`, `working.txt`. `subs.txt` is
  the editable subscription URL list (committed as the curated default).
- Packaged to `v2raytester.exe` with **PyInstaller** (`--onefile --noconsole
  --collect-all customtkinter`), published on GitHub Releases. The exe is gitignored.

## 2. Android app (Kotlin / Jetpack Compose) — `android/`

Test-only (no VPN), **Xray core only**, full feature parity. Self-contained: the APK
**bundles the Xray arm64 binary** as `libxray.so` (fetched by CI, exec'd at runtime),
so users need no v2rayN. Layout:

- **`core/`** — `ShareLinks.kt`, `XrayConfig.kt`, `TestEngine.kt`, `Models.kt`:
  1:1 Kotlin ports of `parsers.py` / `cores.py` / `tester.py`. **Pure JVM** (no
  `android.*`) so the JVM unit tests run in CI without a device. Tests through the
  proxy with OkHttp over a local SOCKS port (OkHttp does remote DNS for SOCKS).
- **`TesterViewModel.kt`** + **`ui/`** (Compose) — MVVM. Engine callbacks marshal to
  Compose state via a single-consumer `Channel`. Subscriptions are parsed
  **incrementally off the main thread**, deduped, and **capped (`MAX_CONFIGS`)** —
  never dump a giant aggregator into the TextField (that OOM-crashes phones).
- **CI** — `.github/workflows/android.yml` (lives at REPO ROOT; GitHub ignores
  workflows in subfolders). Runs JVM unit tests → fetches pinned `Xray-android-arm64`
  → `libxray.so` → `assembleRelease` → attaches APK to `android-v*` releases. Needs
  `permissions: contents: write` for the release step. `libxray.so` is gitignored.

## Conventions / gotchas

- **Keep the Python and Kotlin cores in sync** — `parsers.py`↔`ShareLinks.kt`,
  `cores.py`↔`XrayConfig.kt`, `tester.py`↔`TestEngine.kt`. A change in one should be
  mirrored. The JVM unit tests mirror the Python's known cases.
- **No local Android/JVM toolchain here** — Kotlin can't be compiled locally; **CI is
  the compiler/test gate.** Watch the Actions run after pushing (use the GitHub API
  with the cached git credential token; `gh` is not installed).
- New rule field: add it everywhere it's read (`_collect_rules`-style) on both sides.
- Don't commit build artifacts: `*.exe`, `android/**/build`, `*.so`, `dist/`.
- Cross-thread: desktop uses `self.after(0, …)` / `run_coroutine_threadsafe`; Android
  uses the event `Channel` + `withContext(Main)`.

## Context note

Anti-censorship tooling for the user (Iran). The proxies come from public GitHub
aggregator subscriptions (curated list in `subs.txt`). Testing connects directly to
those servers (exposes the user's IP to the operators) but does not route real
traffic — it only sends probe requests.
