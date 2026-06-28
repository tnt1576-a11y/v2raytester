"""V2Ray Config Tester — modern dark/green GUI (customtkinter).

Drop this folder's files into a v2rayN install folder and run:

    python v2ray_tester.py

Paste share links (or load a subscription / file), hit Test All, and see which
configs actually reach Google through the proxy, with real latency in ms.

Requires: customtkinter  (pip install customtkinter)
"""

import collections
import json
import os
import threading
import time
import urllib.request
import webbrowser
import tkinter as tk
from tkinter import ttk, filedialog, messagebox

import customtkinter as ctk

import cores
import parsers
import tester

CONFIG_FILE = os.path.join(str(cores.app_dir()), "v2raytester_config.json")
SUBS_FILE = os.path.join(str(cores.app_dir()), "subs.txt")
WORK_FILE = os.path.join(str(cores.app_dir()), "working.txt")
DEFAULT_URL = "http://www.gstatic.com/generate_204"

DEFAULT_SUBS = (
    "# One subscription URL per line. Lines starting with # are ignored.\n"
    "# Edit this list freely — it is saved back here when you fetch.\n"
)

# ----------------------------------------------------------------- palette ---
BG = "#0c0f0c"          # window background (near black, green-tinted)
CARD = "#141813"        # cards / panels
CARD2 = "#1b211a"       # inputs / table rows
STROKE = "#242c22"      # subtle borders
FG = "#e7efe7"          # primary text
MUTED = "#7e8c7e"       # secondary text

GREEN = "#2f9e5a"       # primary accent
GREEN_HOV = "#36b568"   # accent hover
GREEN_DEEP = "#16432b"  # deep green (selection / dim fills)
GREEN_GLOW = "#46d07e"  # bright highlight

OK_FG = "#46d07e"
BAD_FG = "#ff5c57"
WARN_FG = "#e0b53d"
TEST_FG = "#5fb0e8"

# latency gradient buckets (ms thresholds -> row foreground)
LAT_FAST = "#46d07e"    # < 150 ms  bright green
LAT_MED = "#9ad13f"     # 150–350   lime
LAT_SLOW = "#e0b53d"    # > 350     amber

STATUS_LABEL = {
    tester.OK: "online",
    tester.TIMEOUT: "timeout",
    tester.FAILED: "failed",
    tester.UNSUPPORTED: "unsupported",
    tester.ERROR: "error",
}

ctk.set_appearance_mode("dark")


class App(ctk.CTk):
    def __init__(self):
        super().__init__()
        self.title("V2Ray Config Tester")
        self.geometry("1140x780")
        self.minsize(940, 640)
        self.configure(fg_color=BG)

        self.nodes = []
        self.results = {}
        self.stop_event = None
        self.testing = False
        self.geo_var = tk.BooleanVar(value=True)
        self.prefilter_var = tk.BooleanVar(value=True)
        self.reach_var = tk.BooleanVar(value=True)
        self._reach_targets = [list(t) for t in tester.DEFAULT_REACH_TARGETS]
        self._test_total = 0
        self._skipped = 0
        self._ping_ms = {}
        self._q = collections.deque()   # worker -> UI messages
        self._pump_id = None
        self._phase = None              # "ping" | "test" | "refine"
        self._ping_stat = (0, 0)
        self._ping_total = 0
        self._refine_total = 0
        self._refine_done = 0
        self._stopping = False
        self._work_fh = None            # live working.txt handle
        self._work_iids = set()         # node indices currently in Working tab
        self._fetch_gen = 0             # single-flight token for subscription fetches
        self.sub_win = None
        self._tip = None
        self._tip_row = None

        # fonts
        self.f_title = ctk.CTkFont("Segoe UI Semibold", 21)
        self.f_sub = ctk.CTkFont("Segoe UI", 12)
        self.f_h = ctk.CTkFont("Segoe UI Semibold", 13)
        self.f_body = ctk.CTkFont("Segoe UI", 13)
        self.f_mono = ctk.CTkFont("Consolas", 12)
        self.f_btn = ctk.CTkFont("Segoe UI Semibold", 13)

        self._build_ui()
        self._style_tree()
        self._load_config()
        self._refresh_core_status()

    # ------------------------------------------------------------- ui builder
    def _card(self, master, **kw):
        opts = dict(fg_color=CARD, corner_radius=14, border_width=1, border_color=STROKE)
        opts.update(kw)
        return ctk.CTkFrame(master, **opts)

    def _ghost_btn(self, master, text, command, width=120, danger=False):
        return ctk.CTkButton(
            master, text=text, command=command, width=width, height=34,
            font=self.f_btn, corner_radius=9,
            fg_color=CARD2, hover_color=(BAD_FG if danger else GREEN_DEEP),
            text_color=FG, border_width=1, border_color=STROKE,
        )

    def _build_ui(self):
        self.grid_columnconfigure(0, weight=1)
        self.grid_rowconfigure(3, weight=1)
        PADX = 18

        # --- header -------------------------------------------------------
        header = ctk.CTkFrame(self, fg_color="transparent")
        header.grid(row=0, column=0, sticky="ew", padx=PADX, pady=(16, 6))
        header.grid_columnconfigure(1, weight=1)
        ctk.CTkLabel(header, text="◢", font=ctk.CTkFont("Segoe UI", 26),
                     text_color=GREEN).grid(row=0, column=0, rowspan=2, padx=(0, 12))
        ctk.CTkLabel(header, text="V2Ray Config Tester", font=self.f_title,
                     text_color=FG).grid(row=0, column=1, sticky="w")
        ctk.CTkLabel(header, text="latency-test your proxies against Google",
                     font=self.f_sub, text_color=MUTED).grid(row=1, column=1, sticky="w")
        self.core_lbl = ctk.CTkLabel(header, text="", font=self.f_sub, text_color=MUTED)
        self.core_lbl.grid(row=0, column=2, rowspan=2, sticky="e", padx=4)

        # --- config input card -------------------------------------------
        incard = self._card(self)
        incard.grid(row=1, column=0, sticky="ew", padx=PADX, pady=6)
        incard.grid_columnconfigure(0, weight=1)
        ctk.CTkLabel(incard, text="CONFIGS", font=self.f_h, text_color=GREEN_GLOW)\
            .grid(row=0, column=0, sticky="w", padx=16, pady=(12, 0))
        self.text = ctk.CTkTextbox(incard, height=150, font=self.f_mono, wrap="none",
                                   fg_color=CARD2, border_width=1, border_color=STROKE,
                                   text_color=FG, corner_radius=10)
        self.text.grid(row=1, column=0, sticky="ew", padx=16, pady=(6, 6))

        inrow = ctk.CTkFrame(incard, fg_color="transparent")
        inrow.grid(row=2, column=0, sticky="ew", padx=16, pady=(0, 14))
        inrow.grid_columnconfigure(0, weight=1)
        ctk.CTkButton(
            inrow, text="⤓  Load from Subscription", command=self.open_subscriptions,
            width=210, height=34, font=self.f_btn, corner_radius=9,
            fg_color=GREEN_DEEP, hover_color=GREEN, text_color=FG,
            border_width=1, border_color=STROKE).grid(row=0, column=0, sticky="w")
        self._ghost_btn(inrow, "Load File", self.load_file, width=96)\
            .grid(row=0, column=2, padx=3)
        self._ghost_btn(inrow, "Parse", self.parse_only, width=78)\
            .grid(row=0, column=3, padx=3)
        self._ghost_btn(inrow, "Remove Duplicates", self.dedupe_box, width=140)\
            .grid(row=0, column=4, padx=3)
        self._ghost_btn(inrow, "Clear", lambda: self.text.delete("1.0", "end"), width=72)\
            .grid(row=0, column=5, padx=3)

        # --- controls card -----------------------------------------------
        ctrl = self._card(self)
        ctrl.grid(row=2, column=0, sticky="ew", padx=PADX, pady=6)
        for c in (4,):
            ctrl.grid_columnconfigure(c, weight=1)

        self.test_btn = ctk.CTkButton(
            ctrl, text="▶  Test All", command=self.start_tests, width=150, height=40,
            font=self.f_btn, corner_radius=10, fg_color=GREEN, hover_color=GREEN_HOV,
            text_color="#06140c")
        self.test_btn.grid(row=0, column=0, padx=(16, 6), pady=14)
        self.stop_btn = self._ghost_btn(ctrl, "■  Stop", self.stop_tests, width=92, danger=True)
        self.stop_btn.configure(state="disabled")
        self.stop_btn.grid(row=0, column=1, padx=6, pady=14)

        opts = ctk.CTkFrame(ctrl, fg_color="transparent")
        opts.grid(row=0, column=2, padx=12)
        ctk.CTkLabel(opts, text="Threads", font=self.f_sub, text_color=MUTED)\
            .grid(row=0, column=0, padx=(0, 6))
        self.conc_var = tk.StringVar(value="16")
        ctk.CTkOptionMenu(opts, values=["8", "16", "32", "64", "128", "256"], variable=self.conc_var,
                          width=72, height=32, corner_radius=8, font=self.f_body,
                          fg_color=CARD2, button_color=GREEN_DEEP, button_hover_color=GREEN,
                          dropdown_fg_color=CARD2, dropdown_hover_color=GREEN_DEEP,
                          text_color=FG).grid(row=0, column=1, padx=(0, 14))
        ctk.CTkLabel(opts, text="Timeout", font=self.f_sub, text_color=MUTED)\
            .grid(row=0, column=2, padx=(0, 6))
        self.timeout_var = tk.StringVar(value="8")
        ctk.CTkOptionMenu(opts, values=["5", "8", "10", "15", "20"], variable=self.timeout_var,
                          width=72, height=32, corner_radius=8, font=self.f_body,
                          fg_color=CARD2, button_color=GREEN_DEEP, button_hover_color=GREEN,
                          dropdown_fg_color=CARD2, dropdown_hover_color=GREEN_DEEP,
                          text_color=FG).grid(row=0, column=3)
        ctk.CTkCheckBox(opts, text="Exit IP/Geo", variable=self.geo_var, font=self.f_body,
                        checkbox_width=20, checkbox_height=20, corner_radius=5,
                        fg_color=GREEN, hover_color=GREEN_HOV, text_color=MUTED,
                        border_color=STROKE).grid(row=0, column=4, padx=(16, 0))
        ctk.CTkCheckBox(opts, text="Skip unreachable", variable=self.prefilter_var,
                        font=self.f_body, checkbox_width=20, checkbox_height=20,
                        corner_radius=5, fg_color=GREEN, hover_color=GREEN_HOV,
                        text_color=MUTED, border_color=STROKE).grid(row=0, column=5, padx=(16, 0))
        ctk.CTkCheckBox(opts, text="Test sites", variable=self.reach_var,
                        font=self.f_body, checkbox_width=20, checkbox_height=20,
                        corner_radius=5, fg_color=GREEN, hover_color=GREEN_HOV,
                        text_color=MUTED, border_color=STROKE).grid(row=0, column=6, padx=(16, 0))
        ctk.CTkButton(opts, text="Edit…", width=52, height=28, corner_radius=8,
                      font=self.f_body, fg_color=CARD2, hover_color=GREEN_DEEP,
                      text_color=MUTED, command=self._edit_sites).grid(row=0, column=7, padx=(6, 0))

        urlf = ctk.CTkFrame(ctrl, fg_color="transparent")
        urlf.grid(row=0, column=4, sticky="ew", padx=(12, 16))
        urlf.grid_columnconfigure(1, weight=1)
        ctk.CTkLabel(urlf, text="Test URL", font=self.f_sub, text_color=MUTED)\
            .grid(row=0, column=0, padx=(0, 6))
        self.url_var = tk.StringVar(value=DEFAULT_URL)
        ctk.CTkEntry(urlf, textvariable=self.url_var, height=32, corner_radius=8,
                     fg_color=CARD2, border_color=STROKE, text_color=FG)\
            .grid(row=0, column=1, sticky="ew")

        # --- results card -------------------------------------------------
        res = self._card(self)
        res.grid(row=3, column=0, sticky="nsew", padx=PADX, pady=(6, 6))
        res.grid_columnconfigure(0, weight=1)
        res.grid_rowconfigure(1, weight=1)

        act = ctk.CTkFrame(res, fg_color="transparent")
        act.grid(row=0, column=0, sticky="ew", padx=14, pady=(12, 6))
        act.grid_columnconfigure(3, weight=1)
        self._ghost_btn(act, "Copy Working", self.copy_working, width=120)\
            .grid(row=0, column=0, padx=3)
        self._ghost_btn(act, "Export Working", self.export_working, width=130)\
            .grid(row=0, column=1, padx=3)
        self._ghost_btn(act, "Sort by Latency", lambda: self.sort_by("latency"), width=130)\
            .grid(row=0, column=2, padx=3)
        self.counts = ctk.CTkLabel(act, text="", font=self.f_body, text_color=MUTED)
        self.counts.grid(row=0, column=3, sticky="e", padx=8)

        self._build_menu()
        tabs = ctk.CTkTabview(res, fg_color=CARD2, corner_radius=10,
                              border_width=1, border_color=STROKE,
                              segmented_button_fg_color=CARD,
                              segmented_button_selected_color=GREEN,
                              segmented_button_selected_hover_color=GREEN_HOV,
                              segmented_button_unselected_color=CARD,
                              segmented_button_unselected_hover_color=GREEN_DEEP,
                              text_color=FG)
        tabs.grid(row=1, column=0, sticky="nsew", padx=12, pady=(0, 6))
        all_tab = tabs.add("All Results")
        work_tab = tabs.add("Working ✓")
        self.tree = self._make_tree(all_tab)
        self.wtree = self._make_tree(work_tab)

        # --- bottom progress ---------------------------------------------
        self.progress = ctk.CTkProgressBar(self, height=8, corner_radius=4,
                                           fg_color=CARD2, progress_color=GREEN)
        self.progress.grid(row=4, column=0, sticky="ew", padx=PADX, pady=(0, 16))
        self.progress.set(0)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    def _make_tree(self, parent):
        """Build a results Treeview (+ scrollbar) filling ``parent``. Both the All
        and Working tabs use this; handlers key off ``event.widget`` so they're
        shared."""
        parent.grid_columnconfigure(0, weight=1)
        parent.grid_rowconfigure(0, weight=1)
        cols = ("idx", "tag", "type", "addr", "ping", "latency", "sites", "exit", "status")
        heads = ("#", "Alias", "Type", "Server : Port", "Ping", "Latency", "Sites", "Exit", "Status")
        widths = (44, 200, 80, 230, 70, 80, 150, 110, 170)
        tree = ttk.Treeview(parent, columns=cols, show="headings",
                            selectmode="extended", style="Green.Treeview")
        for c, h, w in zip(cols, heads, widths):
            tree.heading(c, text=h, command=lambda cc=c, t=tree: self.sort_by(cc, t))
            anchor = "e" if c in ("idx", "ping", "latency") else "w"
            tree.column(c, width=w, anchor=anchor, stretch=(c in ("tag", "addr")))
        tree.grid(row=0, column=0, sticky="nsew", padx=2, pady=2)
        sb = ctk.CTkScrollbar(parent, command=tree.yview,
                              button_color=GREEN_DEEP, button_hover_color=GREEN)
        sb.grid(row=0, column=1, sticky="ns", padx=(0, 2), pady=2)
        tree.configure(yscrollcommand=sb.set)
        for tag, col in (("ok_fast", LAT_FAST), ("ok_med", LAT_MED), ("ok_slow", LAT_SLOW),
                         ("bad", BAD_FG), ("warn", WARN_FG), ("test", TEST_FG)):
            tree.tag_configure(tag, foreground=col)
        tree.bind("<Button-3>", self._show_menu)
        tree.bind("<Motion>", self._on_tree_motion)
        tree.bind("<Leave>", lambda e: self._hide_tip())
        return tree

    def _style_tree(self):
        st = ttk.Style()
        try:
            st.theme_use("clam")
        except tk.TclError:
            pass
        st.configure("Green.Treeview", background=CARD2, fieldbackground=CARD2,
                     foreground=FG, rowheight=30, borderwidth=0, font=("Segoe UI", 11))
        st.configure("Green.Treeview.Heading", background=CARD, foreground=MUTED,
                     borderwidth=0, relief="flat", font=("Segoe UI Semibold", 11))
        st.map("Green.Treeview.Heading", background=[("active", GREEN_DEEP)],
               foreground=[("active", FG)])
        st.map("Green.Treeview", background=[("selected", GREEN_DEEP)],
               foreground=[("selected", FG)])

    # ------------------------------------------------------------- helpers
    def _refresh_core_status(self):
        text, ok = cores.cores_status()
        self.core_lbl.configure(text="● " + text, text_color=(GREEN if ok else BAD_FG))

    @staticmethod
    def _strip_comments(text):
        """Drop blank lines and subscription metadata/comment lines (leading # or
        //, e.g. #profile-title, #subscription-userinfo) so only real config links
        land in the box."""
        out = []
        for ln in (text or "").splitlines():
            s = ln.strip()
            if not s or s.startswith("#") or s.startswith("//"):
                continue
            out.append(s)
        return "\n".join(out)

    def _set_links(self, text, append=False):
        self._set_box(self._strip_comments(text), append=append)

    def _box_text(self):
        return self.text.get("1.0", "end")

    def _set_box(self, content, append=False):
        if append and self._box_text().strip():
            if not self._box_text().endswith("\n"):
                self.text.insert("end", "\n")
            self.text.insert("end", content)
        else:
            self.text.delete("1.0", "end")
            self.text.insert("1.0", content)

    # ------------------------------------------------------------- input ops
    def load_file(self):
        path = filedialog.askopenfilename(
            title="Load configs", filetypes=[("Text/links", "*.txt"), ("All files", "*.*")])
        if not path:
            return
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                data = f.read()
        except Exception as e:
            messagebox.showerror("Load File", str(e))
            return
        self._set_links(parsers.decode_subscription(data), append=True)
        self.parse_only()

    def _parse_box(self):
        """Parse + dedupe the config box. Returns (nodes, removed, errors)."""
        nodes, errors = parsers.parse_links(parsers.decode_subscription(self._box_text()))
        nodes, removed = parsers.dedupe(nodes)
        return nodes, removed, errors

    def dedupe_box(self):
        """Rewrite the config box with duplicate configs removed (keeps first of
        each). Unparseable lines are dropped, so it also tidies the list."""
        nodes, removed, errors = self._parse_box()
        if not nodes:
            messagebox.showinfo("Remove Duplicates", "No valid configs in the box.")
            return
        self._set_box("\n".join(n["raw"] for n in nodes))
        msg = "removed " + str(removed) + " duplicate" + ("" if removed == 1 else "s")
        if errors:
            msg += " · dropped " + str(len(errors)) + " incompatible"
        self.counts.configure(text=msg + " · " + str(len(nodes)) + " configs")

    def parse_only(self):
        nodes, removed, errors = self._parse_box()
        text = str(len(nodes)) + " configs · " + str(removed) + " dupes"
        if errors:
            text += " · " + str(len(errors)) + " incompatible"
        self.counts.configure(text=text)
        return nodes

    # ---------------------------------------------------------------- testing
    def _settings(self):
        threads = int(self.conc_var.get())
        return {
            "url": self.url_var.get().strip() or DEFAULT_URL,
            "timeout": int(self.timeout_var.get()),
            "concurrency": threads,
            "geo": bool(self.geo_var.get()),
            "prefilter": bool(self.prefilter_var.get()),
            "reach_targets": self._reach_targets if self.reach_var.get() else [],
            "ping_concurrency": max(800, threads * 16),
            "start_timeout": 5,
            "ping_timeout": 2,
            "latency_samples": 3,      # Pass B: median of N samples for accurate latency
            "refine_concurrency": 4,   # Pass B: low concurrency = no contention
        }

    def _edit_sites(self):
        """Small editor for the reachability target list (one 'CODE url' per line)."""
        win = ctk.CTkToplevel(self)
        win.title("Reachability targets")
        win.geometry("540x360")
        win.configure(fg_color=BG)
        win.transient(self)
        win.grid_columnconfigure(0, weight=1)
        win.grid_rowconfigure(1, weight=1)
        ctk.CTkLabel(win, text="One per line:  CODE  https://url/to/check   "
                     "(a short code + a lightweight URL)",
                     font=self.f_body, text_color=MUTED, anchor="w")\
            .grid(row=0, column=0, sticky="ew", padx=14, pady=(12, 4))
        box = ctk.CTkTextbox(win, font=("Consolas", 12), fg_color=CARD2,
                             border_color=STROKE, border_width=1, text_color=FG)
        box.grid(row=1, column=0, sticky="nsew", padx=14, pady=4)
        box.insert("1.0", "\n".join(c + " " + u for c, u in self._reach_targets))
        btns = ctk.CTkFrame(win, fg_color="transparent")
        btns.grid(row=2, column=0, sticky="e", padx=14, pady=(4, 12))

        def save():
            targets = []
            for ln in box.get("1.0", "end").splitlines():
                parts = ln.split(None, 1)
                if len(parts) == 2 and "://" in parts[1]:
                    targets.append([parts[0][:6], parts[1].strip()])
            self._reach_targets = targets
            win.destroy()

        ctk.CTkButton(btns, text="Reset", width=80, fg_color=CARD2,
                      hover_color=GREEN_DEEP, text_color=MUTED,
                      command=lambda: (box.delete("1.0", "end"),
                                       box.insert("1.0", "\n".join(
                                           c + " " + u for c, u in tester.DEFAULT_REACH_TARGETS))))\
            .grid(row=0, column=0, padx=4)
        ctk.CTkButton(btns, text="Cancel", width=80, fg_color=CARD2,
                      hover_color=GREEN_DEEP, text_color=MUTED, command=win.destroy)\
            .grid(row=0, column=1, padx=4)
        ctk.CTkButton(btns, text="Save", width=80, fg_color=GREEN,
                      hover_color=GREEN_HOV, command=save).grid(row=0, column=2, padx=4)
        win.after(120, lambda: (win.lift(), win.focus_force()))

    def _insert_row(self, i, n, ping_s=""):
        self.tree.insert("", "end", iid=str(i),
                         values=(i + 1, n.get("tag", ""), n["type"],
                                 n["server"] + ":" + str(n["port"]), ping_s, "", "", "", "· testing"),
                         tags=("test",))

    def start_tests(self):
        if self.testing:
            return
        nodes, _, _ = self._parse_box()
        if not nodes:
            messagebox.showinfo("Test", "No valid configs to test.")
            return
        self.nodes = nodes
        self.results = {}
        self._ping_ms = {}
        self._skipped = 0
        self._test_total = 0
        self._stopping = False
        self._q.clear()
        self._ping_total = len(nodes)
        self._ping_stat = (0, 0)
        self._work_iids = set()
        self.tree.delete(*self.tree.get_children())
        self.wtree.delete(*self.wtree.get_children())
        self._work_open()
        self.progress.set(0)
        self.testing = True
        self.stop_event = threading.Event()
        self.test_btn.configure(state="disabled")
        self.stop_btn.configure(state="normal")
        settings = self._settings()
        self._start_pump()

        if settings["prefilter"]:
            self._phase = "ping"
            self._ping_start = time.time()
            self.counts.configure(text="pinging " + str(len(nodes)) + " nodes…")
            threading.Thread(target=self._run_prefiltered, args=(nodes, settings),
                             daemon=True).start()
        else:
            self._phase = "test"
            self._test_total = len(nodes)
            for i in range(len(nodes)):
                self._q.append(("row", i))
            threading.Thread(target=self._run, args=(nodes, settings, None),
                             daemon=True).start()

    # -------- bounded UI pump (keeps the main thread responsive) ----------
    def _start_pump(self):
        if self._pump_id is None:
            self._pump_id = self.after(60, self._pump)

    def _pump(self):
        if self._stopping:
            self._q.clear()
        else:
            q = self._q
            n = 0
            while q and n < 700:
                msg = q.popleft()
                n += 1
                kind = msg[0]
                if kind == "row":
                    i = msg[1]
                    ms = self._ping_ms.get(i)
                    self._insert_row(i, self.nodes[i], (str(ms) + " ms") if ms is not None else "")
                elif kind == "result":
                    self._apply_result(msg[1], msg[2], msg[3])
                elif kind == "refine":
                    self._phase = "refine"
                    self._refine_total = msg[1]
                    self._refine_done = 0
                elif kind == "pingstat":
                    self._ping_stat = (msg[1], msg[2])
            self._refresh_status()
        if self.testing or self._q:
            self._pump_id = self.after(60, self._pump)
        else:
            self._pump_id = None
            self._finalize()

    def _refresh_status(self):
        if self._stopping:
            return
        if self._phase == "ping":
            done, reach = self._ping_stat
            self.progress.set(done / max(1, self._ping_total))
            secs = int(time.time() - getattr(self, "_ping_start", time.time()))
            self.counts.configure(text="pinging " + str(done) + "/" + str(self._ping_total)
                                  + " · " + str(reach) + " reachable · " + str(secs) + "s")
        elif self._phase == "refine":
            total = max(1, getattr(self, "_refine_total", 0))
            done = getattr(self, "_refine_done", 0)
            self.progress.set(min(1.0, done / total))
            self.counts.configure(text="refining " + str(done) + "/" + str(getattr(self, "_refine_total", 0))
                                  + " · " + str(len(self._work_iids)) + " online")
        else:
            done = len(self.results)
            total = max(1, self._test_total or len(self.nodes))
            self.progress.set(min(1.0, done / total))
            self._update_counts()

    def _finalize(self):
        self.testing = False
        self.test_btn.configure(state="normal")
        self.stop_btn.configure(state="disabled")
        self._work_rewrite_sorted()
        work = len(self._work_iids)
        if self._stopping:
            self.counts.configure(text="stopped · " + str(len(self.results)) + " tested · "
                                  + str(work) + " working saved")
        else:
            self._refresh_status()
        # the Working tab is small — always safe to sort; All tab only past a threshold
        self.sort_by("latency", self.wtree)
        if len(self.tree.get_children()) <= 20000:
            self.sort_by("latency")

    # -------- two-phase: reachability pre-filter, then full test ----------
    def _run_prefiltered(self, nodes, settings):
        total = len(nodes)
        st = {"done": 0, "reach": 0}

        def on_ping(i, ms):
            st["done"] += 1
            if ms is not None:
                st["reach"] += 1
                self._ping_ms[i] = ms
            if st["done"] % 25 == 0 or st["done"] == total:
                self._q.append(("pingstat", st["done"], st["reach"]))

        reachable = tester.ping_filter(nodes, settings, on_ping, self.stop_event)
        self.after(0, self._begin_phase2, reachable, settings)

    def _begin_phase2(self, reachable, settings):
        self._skipped = len(self.nodes) - len(reachable)
        self._test_total = len(reachable)
        self._phase = "test"
        for i in reachable:
            self.nodes[i]["_ping"] = self._ping_ms.get(i)  # reuse ping, skip re-probe
            self._q.append(("row", i))
        if not reachable or self.stop_event.is_set():
            self.testing = False     # pump drains remaining rows, then finalizes
            return
        threading.Thread(target=self._run, args=(self.nodes, settings, reachable),
                         daemon=True).start()

    def _run(self, nodes, settings, indices):
        tester.run_tests(
            nodes, settings,
            on_result=lambda i, r, refined: self._q.append(("result", i, r, refined)),
            stop_event=self.stop_event,
            on_done=lambda: self.after(0, self._phase2_done),
            indices=indices,
            on_refine_start=lambda n: self._q.append(("refine", n)),
        )

    def _phase2_done(self):
        self.testing = False         # pump finalizes once the queue drains

    def _apply_result(self, idx, res, refined=False):
        if refined:
            self._refine_done = getattr(self, "_refine_done", 0) + 1
        self.results[idx] = res
        iid = str(idx)
        status = res["status"]
        lat_ms = res.get("latency")
        if status == tester.OK:
            tag = self._gradient_tag(lat_ms)
        elif status in (tester.TIMEOUT, tester.UNSUPPORTED):
            tag = "warn"
        else:
            tag = "bad"
        lat = (str(lat_ms) + " ms") if lat_ms is not None else "—"
        ping = res.get("tcp_ping")
        ping_s = (str(ping) + " ms") if ping is not None else "—"
        exit_s = res.get("country", "")
        if res.get("exit_ip"):
            exit_s = (exit_s + " · " + res["exit_ip"]).strip(" ·")
        label = STATUS_LABEL.get(status, status)
        if res.get("message") and status != tester.OK:
            label += " · " + res["message"]
        sites_s = self._fmt_reach(res.get("reach"))
        n = res["node"]
        values = (idx + 1, n.get("tag", ""), n["type"],
                  n["server"] + ":" + str(n["port"]), ping_s, lat, sites_s, exit_s, label)
        if self.tree.exists(iid):  # O(1); row may have been deleted
            self.tree.item(iid, values=values, tags=(tag,))

        # live Working tab + auto-save
        if status == tester.OK:
            if idx not in self._work_iids:
                self._work_iids.add(idx)
                self.wtree.insert("", "end", iid=iid, values=values, tags=(tag,))
                self._work_append(n.get("raw", ""))
        elif idx in self._work_iids:  # retest flipped it to non-working
            self._work_iids.discard(idx)
            if self.wtree.exists(iid):
                self.wtree.delete(iid)

    def _on_result(self, idx, res):
        # single-row path (retest): apply + refresh immediately
        self._apply_result(idx, res)
        self._update_counts()

    @staticmethod
    def _fmt_reach(reach):
        """Compact per-site reachability, e.g. 'YT✓ IG✓ TG✗ AI✓'."""
        if not reach:
            return ""
        return " ".join(code + ("✓" if ok else "✗") for code, ok in reach.items())

    def _gradient_tag(self, lat_ms):
        if lat_ms is None:
            return "ok_med"
        if lat_ms < 150:
            return "ok_fast"
        if lat_ms <= 350:
            return "ok_med"
        return "ok_slow"

    def _update_counts(self):
        ok = sum(1 for r in self.results.values() if r["status"] == tester.OK)
        done = len(self.results)
        total = self._test_total or len(self.nodes)
        text = "✓ " + str(ok) + " online · " + str(done) + "/" + str(total) + " tested"
        if self._skipped:
            text += " · " + str(self._skipped) + " skipped"
        self.counts.configure(text=text)

    def stop_tests(self):
        if self.stop_event:
            self.stop_event.set()
        self._stopping = True
        self.stop_btn.configure(state="disabled")
        self.counts.configure(text="stopping…")

    # ----------------------------------------------------------------- output
    def _working_sorted(self):
        items = [r for r in self.results.values() if r["status"] == tester.OK]
        items.sort(key=lambda r: (r["latency"] if r["latency"] is not None else 1 << 30))
        return items

    def copy_working(self):
        items = self._working_sorted()
        if not items:
            messagebox.showinfo("Copy", "No working configs yet.")
            return
        self.clipboard_clear()
        self.clipboard_append("\n".join(r["node"]["raw"] for r in items))
        self.counts.configure(text="copied " + str(len(items)) + " working links")

    def export_working(self):
        items = self._working_sorted()
        if not items:
            messagebox.showinfo("Export", "No working configs yet.")
            return
        path = filedialog.asksaveasfilename(
            title="Export working configs", defaultextension=".txt",
            initialfile="working.txt", filetypes=[("Text", "*.txt")])
        if not path:
            return
        try:
            with open(path, "w", encoding="utf-8") as f:
                f.write("\n".join(r["node"]["raw"] for r in items))
        except Exception as e:
            messagebox.showerror("Export", str(e))
            return
        messagebox.showinfo("Export", "Wrote " + str(len(items)) + " working configs.")

    # ------------------------------------------------------------------ sort
    def sort_by(self, col, tree=None):
        tree = tree or self.tree
        rows = [(tree.set(iid, col), iid) for iid in tree.get_children()]

        def key(pair):
            val = pair[0]
            if col in ("latency", "idx", "ping"):
                digits = "".join(ch for ch in val if ch.isdigit() or ch == ".")
                try:
                    return (0, float(digits))
                except ValueError:
                    return (1, float("inf"))
            return (0, val.lower())

        rows.sort(key=key)
        for pos, (_, iid) in enumerate(rows):
            tree.move(iid, "", pos)

    # ----------------------------------------------------- working-file I/O
    def _work_open(self):
        self._work_close()
        try:
            self._work_fh = open(WORK_FILE, "w", encoding="utf-8")
        except Exception:
            self._work_fh = None

    def _work_append(self, raw):
        if self._work_fh and raw:
            try:
                self._work_fh.write(raw + "\n")
                self._work_fh.flush()
            except Exception:
                pass

    def _work_close(self):
        if self._work_fh:
            try:
                self._work_fh.close()
            except Exception:
                pass
            self._work_fh = None

    def _work_rewrite_sorted(self):
        """Rewrite working.txt fastest-first once a run finishes."""
        self._work_close()
        items = self._working_sorted()
        try:
            with open(WORK_FILE, "w", encoding="utf-8") as f:
                f.write("\n".join(r["node"]["raw"] for r in items))
                if items:
                    f.write("\n")
        except Exception:
            pass

    # ------------------------------------------------------- right-click menu
    def _build_menu(self):
        self.menu = tk.Menu(self, tearoff=0, bg=CARD2, fg=FG,
                            activebackground=GREEN_DEEP, activeforeground=FG,
                            bd=0, relief="flat")
        self.menu.add_command(label="Retest", command=self._menu_retest)
        self.menu.add_command(label="Copy link", command=self._menu_copy)
        self.menu.add_command(label="Open server in browser", command=self._menu_open)
        self.menu.add_separator()
        self.menu.add_command(label="Delete row", command=self._menu_delete)

    def _show_menu(self, event):
        tree = event.widget
        iid = tree.identify_row(event.y)
        if not iid:
            return
        if iid not in tree.selection():
            tree.selection_set(iid)
        self._menu_iid = iid
        self._menu_tree = tree
        try:
            self.menu.tk_popup(event.x_root, event.y_root)
        finally:
            self.menu.grab_release()

    def _menu_node(self):
        iid = getattr(self, "_menu_iid", None)
        if iid is None:
            return None, None
        try:
            return iid, self.nodes[int(iid)]
        except (ValueError, IndexError):
            return iid, None

    def _menu_copy(self):
        _, node = self._menu_node()
        if node:
            self.clipboard_clear()
            self.clipboard_append(node.get("raw", ""))
            self.counts.configure(text="copied 1 link")

    def _menu_open(self):
        _, node = self._menu_node()
        if node and node.get("server"):
            webbrowser.open("http://" + node["server"])

    def _menu_delete(self):
        iid, _ = self._menu_node()
        if iid is None:
            return
        for tree in (self.tree, self.wtree):
            if tree.exists(iid):
                tree.delete(iid)
        self.results.pop(int(iid), None)
        self._work_iids.discard(int(iid))

    def _menu_retest(self):
        iid, node = self._menu_node()
        if node is None or self.testing:
            return
        node.pop("_ping", None)   # force a fresh ping on an explicit retest
        if self.tree.exists(iid):
            self.tree.set(iid, "status", "· testing")
            self.tree.item(iid, tags=("test",))
        idx = int(iid)
        stop = self.stop_event or threading.Event()

        def work():
            res = tester.test_node(node, self._settings(), stop)
            self.after(0, self._on_result, idx, res)

        threading.Thread(target=work, daemon=True).start()

    # ------------------------------------------------------------- tooltip
    def _on_tree_motion(self, event):
        iid = event.widget.identify_row(event.y)
        if iid == self._tip_row:
            return
        self._tip_row = iid
        self._hide_tip()
        if not iid:
            return
        res = self.results.get(int(iid)) if iid.isdigit() else None
        if not res:
            return
        detail = res.get("detail") or res.get("message")
        if not detail or res.get("status") == tester.OK:
            return
        self._show_tip(event.x_root, event.y_root, detail[:600])

    def _show_tip(self, x, y, text):
        self._tip = tk.Toplevel(self)
        self._tip.wm_overrideredirect(True)
        self._tip.configure(bg=STROKE)
        tk.Label(self._tip, text=text, justify="left", bg=CARD2, fg=FG,
                 font=("Consolas", 9), wraplength=520, padx=8, pady=6,
                 bd=0).pack(padx=1, pady=1)
        self._tip.wm_geometry("+%d+%d" % (x + 14, y + 12))

    def _hide_tip(self):
        if self._tip is not None:
            self._tip.destroy()
            self._tip = None

    # ------------------------------------------------- subscription manager
    def open_subscriptions(self):
        if self.sub_win is not None and self.sub_win.winfo_exists():
            self.sub_win.lift()
            return
        win = ctk.CTkToplevel(self)
        win.title("Subscriptions")
        win.geometry("560x460")
        win.configure(fg_color=BG)
        win.transient(self)
        self.sub_win = win

        win.grid_columnconfigure(0, weight=1)
        win.grid_rowconfigure(1, weight=1)
        ctk.CTkLabel(win, text="Subscription URLs  (one per line)", font=self.f_h,
                     text_color=GREEN_GLOW).grid(row=0, column=0, sticky="w", padx=16, pady=(14, 4))
        box = ctk.CTkTextbox(win, font=self.f_mono, wrap="none", fg_color=CARD2,
                             border_width=1, border_color=STROKE, text_color=FG, corner_radius=10)
        box.grid(row=1, column=0, sticky="nsew", padx=16, pady=4)
        box.insert("1.0", self._read_subs())
        self._sub_box = box

        self._sub_status = ctk.CTkLabel(win, text="", font=self.f_sub, text_color=MUTED)
        self._sub_status.grid(row=2, column=0, sticky="w", padx=16)
        self._sub_prog = ctk.CTkProgressBar(win, height=8, corner_radius=4,
                                            fg_color=CARD2, progress_color=GREEN)
        self._sub_prog.grid(row=3, column=0, sticky="ew", padx=16, pady=(2, 6))
        self._sub_prog.set(0)

        btns = ctk.CTkFrame(win, fg_color="transparent")
        btns.grid(row=4, column=0, sticky="ew", padx=16, pady=(0, 14))
        self._sub_fetch_btn = ctk.CTkButton(
            btns, text="⤓  Fetch All", command=self._sub_fetch, width=130, height=36,
            font=self.f_btn, corner_radius=9, fg_color=GREEN, hover_color=GREEN_HOV,
            text_color="#06140c")
        self._sub_fetch_btn.pack(side="left")
        self._sub_abort_btn = self._ghost_btn(btns, "Abort", self._sub_do_abort, width=92, danger=True)
        self._sub_abort_btn.configure(state="disabled")
        self._sub_abort_btn.pack(side="left", padx=8)
        self._ghost_btn(btns, "Save list", self._sub_save, width=92).pack(side="right")

        win.protocol("WM_DELETE_WINDOW", self._sub_close)

    def _read_subs(self):
        try:
            with open(SUBS_FILE, "r", encoding="utf-8") as f:
                return f.read()
        except Exception:
            try:
                with open(SUBS_FILE, "w", encoding="utf-8") as f:
                    f.write(DEFAULT_SUBS)
            except Exception:
                pass
            return DEFAULT_SUBS

    def _sub_urls(self):
        text = self._sub_box.get("1.0", "end")
        urls = []
        for line in text.splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                urls.append(line)
        return urls

    def _sub_save(self):
        try:
            with open(SUBS_FILE, "w", encoding="utf-8") as f:
                f.write(self._sub_box.get("1.0", "end").strip() + "\n")
            self._sub_status.configure(text="saved subs.txt")
        except Exception as e:
            self._sub_status.configure(text="save failed: " + str(e))

    @staticmethod
    def _fetch_one(url):
        """Fetch + decode a single subscription URL. Returns decoded links text
        ('' on failure)."""
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "v2raytester"})
            with urllib.request.urlopen(req, timeout=20) as r:
                raw = r.read().decode("utf-8", "replace")
            return parsers.decode_subscription(raw).strip()
        except Exception:
            return ""

    def _start_fetch(self, urls, progress, done):
        """Single-flight fetch. Bumping the generation token cancels any fetch
        already running, so two Fetch All runs can never overlap (which would
        double-count and double the configs)."""
        self._fetch_gen += 1
        gen = self._fetch_gen
        threading.Thread(target=self._fetch_worker, args=(urls, gen, progress, done),
                         daemon=True).start()
        return gen

    def _fetch_worker(self, urls, gen, progress, done):
        total = len(urls)
        gathered = []
        ok = configs = 0
        for i, url in enumerate(urls, 1):
            if gen != self._fetch_gen:      # superseded or aborted -> bail
                return
            links = self._fetch_one(url)
            if links:
                gathered.append(links)
                ok += 1
                configs += sum(1 for ln in links.splitlines() if "://" in ln)
            self.after(0, progress, gen, i, total, ok, configs)
        if gen == self._fetch_gen:
            self.after(0, done, gen, "\n".join(gathered))

    # -- manual Fetch All (subscription window) --
    def _sub_fetch(self):
        urls = self._sub_urls()
        if not urls:
            self._sub_status.configure(text="no URLs to fetch")
            return
        self._sub_save()
        self._sub_fetch_btn.configure(state="disabled")
        self._sub_abort_btn.configure(state="normal")
        self._sub_prog.set(0)
        self._start_fetch(urls, self._sub_progress, self._sub_done)

    def _sub_do_abort(self):
        self._fetch_gen += 1            # stops the running worker
        self._sub_abort_btn.configure(state="disabled")
        if self.sub_win and self.sub_win.winfo_exists():
            self._sub_fetch_btn.configure(state="normal")
            self._sub_status.configure(text="aborted")

    def _sub_progress(self, gen, done, total, ok, configs):
        if gen != self._fetch_gen or not (self.sub_win and self.sub_win.winfo_exists()):
            return
        self._sub_prog.set(done / max(1, total))
        self._sub_status.configure(
            text=str(done) + "/" + str(total) + " fetched · " + str(ok)
                 + " ok · " + str(configs) + " configs")

    def _sub_done(self, gen, links):
        if gen != self._fetch_gen:
            return
        if self.sub_win and self.sub_win.winfo_exists():
            self._sub_fetch_btn.configure(state="normal")
            self._sub_abort_btn.configure(state="disabled")
        if links.strip():
            self._set_links(links)      # replace, never append (no doubling)
            self.parse_only()

    def _sub_close(self):
        self._fetch_gen += 1            # cancel any running fetch
        try:
            self._sub_save()
        except Exception:
            pass
        if self.sub_win is not None:
            self.sub_win.destroy()
            self.sub_win = None

    # ---------------------------------------------------------------- config
    def _load_config(self):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception:
            return
        self.url_var.set(cfg.get("url", DEFAULT_URL))
        self.timeout_var.set(str(cfg.get("timeout", 8)))
        self.conc_var.set(str(cfg.get("concurrency", 8)))
        self.geo_var.set(bool(cfg.get("geo", True)))
        self.prefilter_var.set(bool(cfg.get("prefilter", True)))
        self.reach_var.set(bool(cfg.get("reach", True)))
        rt = cfg.get("reach_targets")
        if isinstance(rt, list) and rt:
            self._reach_targets = [[str(t[0]), str(t[1])] for t in rt
                                   if isinstance(t, (list, tuple)) and len(t) == 2]
        # NOTE: the config list is intentionally NOT restored — subscriptions go
        # stale within hours, so the box starts empty; use Load from Subscription
        # to pull fresh configs when you want to test.

    def _save_config(self):
        cfg = {
            "url": self.url_var.get().strip(),
            "timeout": int(self.timeout_var.get()),
            "concurrency": int(self.conc_var.get()),
            "geo": bool(self.geo_var.get()),
            "prefilter": bool(self.prefilter_var.get()),
            "reach": bool(self.reach_var.get()),
            "reach_targets": self._reach_targets,
        }
        try:
            with open(CONFIG_FILE, "w", encoding="utf-8") as f:
                json.dump(cfg, f, indent=2)
        except Exception:
            pass

    def _on_close(self):
        if self.stop_event:
            self.stop_event.set()
        self._stopping = True
        self._work_close()
        self._save_config()
        self.destroy()


if __name__ == "__main__":
    App().mainloop()
