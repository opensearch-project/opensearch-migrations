"""Text-UI replacement for 07-live-jaccard-kafka.sh — a live table of replayed tuples
scored for source/target agreement, read from the tuple-output Kafka topic.

Follows the structure of the loadtest TUI (opensearch-project/opensearch-migrations#3263):
a background thread owns the long-lived I/O (there, k6 run polling; here, the Kafka
consumer subprocess) and only ever touches a lock-guarded buffer. A timer on the main
thread snapshots that buffer and repaints — the same split that keeps a slow or stalled
consumer from ever blocking the UI.

Data model: every replayed request is a PARENT row (its identity — method, URI, status
codes) plus one or more CHILD rows, one per sub-query — a plain search has exactly one
child; an _msearch has one per NDJSON search action. Scoring lives on the children only, on
purpose: a blended average across an msearch's sub-queries hides exactly the failure that
matters (one badly-diverged sub-query pulled toward 1.0 by two good ones still looks fine in
aggregate), so the parent row carries no score of its own.
"""
import collections
import itertools
import json
import logging
import threading
from typing import Deque, Dict, List, Optional, Tuple

from rich.text import Text
from textual.app import App, ComposeResult
from textual.containers import Container, Horizontal
from textual.widgets import DataTable, Footer, Header, Static

from .clipboard import copy_via_system_tool
from .kafka_reader import KafkaReaderError, KafkaTupleReader
from .scoring import score_tuple

logger = logging.getLogger(__name__)

TABLE_ANCHOR = "tuples"
COLUMNS = ("J", "METHOD", "URI", "SRC → TGT", "NOTE")
SPARK = "▁▂▃▄▅▆▇█"


def _score_color(j: Optional[float]) -> str:
    if j is None:
        return "dim"
    return "green" if j >= 0.95 else ("yellow" if j >= 0.80 else "red")


def _spark_char(j: Optional[float]) -> Text:
    if j is None:
        return Text("░", style="dim")
    idx = min(7, int(j * 8))
    return Text(SPARK[idx], style=f"bold {_score_color(j)}")


def _has_scoreable_subqueries(s: Dict) -> bool:
    if s.get("j_label") == "preflight":
        return False
    return any(sq.get("src_hits") is not None or sq.get("tgt_hits") is not None
               for sq in s.get("subqueries", []))


class JaccardApp(App):
    """Live replay-quality monitor. One instance == one Kafka topic being watched."""

    CSS = """
    #summary { height: 4; padding: 0 1; }
    #footnote { height: 1; padding: 0 1; color: $text-muted; }
    DataTable { height: 1fr; }
    #detail { height: 14; border-top: solid $primary; }
    #detail-src, #detail-tgt { width: 1fr; padding: 0 1; overflow-y: auto; }
    #detail-src { border-right: solid $primary-darken-2; }
    """
    BINDINGS = [
        ("r", "reset", "Reset window"),
        ("v", "toggle_view", "Hits/Request"),
        ("c", "copy_request", "Copy request"),
        ("q", "quit", "Quit"),
    ]

    def __init__(self, namespace: str, topic: str = "tuple-output", *,
                 window: int = 15, refresh_interval: float = 2.0,
                 auto_create_topic: bool = True):
        super().__init__()
        self.title = f"[{namespace}] live replay quality"
        self.sub_title = f"topic: {topic}"
        self._namespace = namespace
        self._topic = topic
        self._window = window
        self._refresh_interval = refresh_interval
        self._auto_create_topic = auto_create_topic
        # Holds every scored tuple seen this "generation" (since last reset), capped so a
        # long-running demo can't grow this unboundedly; the window is a suffix of it.
        self._buf: Deque[Dict] = collections.deque(maxlen=window * 20)
        self._buf_lock = threading.Lock()
        self._status = {"total": 0, "running": True, "connected": False, "error": None}
        self._reader: Optional[KafkaTupleReader] = None
        self._seq = itertools.count(1)
        # Row identity, independent of the table rebuild every tick — "<seq>" for a parent
        # row, "<seq>:<subquery index>" for a child. Neither changes once assigned, so this
        # survives the window sliding underneath it and is what lets the detail pane and
        # cursor position both be restored after a rebuild.
        self._selected_key: Optional[str] = None
        # "hits" shows the side-by-side doc-ID diff; "request" shows the raw captured HTTP
        # request(s) for copy/paste — toggled with 'v', independent of which row is selected.
        self._view_mode = "hits"
        self.is_exiting = False

    def compose(self) -> ComposeResult:
        yield Header()
        yield Static("", id="summary")
        yield Container(DataTable(id=TABLE_ANCHOR, cursor_type="row"))
        with Horizontal(id="detail"):
            yield Static("", id="detail-src")
            yield Static("", id="detail-tgt")
        yield Static("", id="footnote")
        yield Footer()

    def on_mount(self) -> None:
        self.table.add_columns(*COLUMNS)
        self._update_detail()
        self.run_worker(self._bootstrap_and_stream, thread=True, name="kafka_reader")
        self.set_interval(self._refresh_interval, self._repaint)

    def on_unmount(self) -> None:
        self.is_exiting = True
        if self._reader:
            self._reader.stop()

    @property
    def table(self) -> DataTable:
        return self.query_one(f"#{TABLE_ANCHOR}", DataTable)

    # --- Kafka bootstrap + streaming (background thread — no UI calls except via
    #     call_from_thread, and only for one-shot setup failures) ---

    def _bootstrap_and_stream(self) -> None:
        reader = KafkaTupleReader(self._namespace, self._topic,
                                   auto_create_topic=self._auto_create_topic)
        try:
            reader.ensure_ready()
        except KafkaReaderError as e:
            if not self.is_exiting:
                self.call_from_thread(self.notify, str(e), severity="error", timeout=None)
            self._status["error"] = str(e)
            self._status["running"] = False
            return
        self._reader = reader
        self._status["connected"] = True
        for record in reader.stream():
            if self.is_exiting:
                break
            s = score_tuple(record)
            s["seq"] = next(self._seq)
            with self._buf_lock:
                self._buf.append(s)
                self._status["total"] += 1
        self._status["running"] = False

    # --- Repaint (main thread, on a timer — never touches the Kafka subprocess) ---

    def _repaint(self) -> None:
        with self._buf_lock:
            buf = list(self._buf)
        self._update_summary(buf)
        self._rebuild_table(buf)
        self._update_footnote(buf)

    def _visible_tuples(self, buf: List[Dict]) -> List[Dict]:
        """The last N qualifying requests — shared by the table and the footnote so both
        agree on what "the window" means. Preflight OPTIONS and requests with no hit data on
        either side (index/delete ops, errors) never get a row at all."""
        return [s for s in buf if _has_scoreable_subqueries(s)][-self._window:]

    def _update_summary(self, buf: List[Dict]) -> None:
        """Two distinct setup phases, then a live sparkline built from individual sub-query
        scores rather than one blended score per request — an msearch with 3 sub-queries
        contributes 3 marks, each colored on its own merit, not one averaged mark that could
        hide a badly-diverged sub-query behind two good ones.
        """
        summary = self.query_one("#summary", Static)

        if self._status["error"]:
            summary.update(Text(f"Setup failed: {self._status['error']}", style="bold red"))
            return
        if not self._status["connected"]:
            summary.update(f"Confirming topic '{self._topic}' exists and is reachable…")
            return

        all_js = [sq["j"] for s in buf for sq in s.get("subqueries", []) if sq["j"] is not None]
        window = all_js[-self._window:]
        blanks = self._window - len(window)
        text = Text("Jaccard  ")
        text.append(Text("░ " * blanks, style="dim"))
        text.append(Text(" ").join(_spark_char(j) for j in window))
        text.append("\n")
        if window:
            avg_j = sum(window) / len(window)
            text.append("avg ")
            text.append(f"{avg_j:.3f}", style=f"bold {_score_color(avg_j)}")
            text.append(f"   min {min(window):.3f}   max {max(window):.3f}"
                         f"   ({len(window)} sub-query scores, {self._status['total']} requests seen)")
        else:
            text.append(f"0 tuples seen yet — waiting for live traffic on '{self._topic}'")
            if not self._status["running"]:
                text.append("  (consumer disconnected)", style="dim")
        summary.update(text)

    def _rebuild_table(self, buf: List[Dict]) -> None:
        """Repaint the table, keeping the cursor (and so the detail pane) on the same row
        across refreshes — row indexes shift as the window slides, so the row key, not
        index, is what identifies a row from one rebuild to the next."""
        table = self.table
        table.clear()
        row_keys: List[str] = []
        # Newest request first — a live monitor's most useful row is the one that just
        # happened, not the oldest one still in the window. Each request's own sub-queries
        # stay in their natural 1st/2nd/3rd order underneath it; only request-to-request
        # order flips.
        for s in reversed(self._visible_tuples(buf)):
            uri = s["uri"] or "?"
            if len(uri) > 50:
                uri = uri[:47] + "..."
            subqueries = s.get("subqueries", [])
            note = f"{len(subqueries)} sub-queries" if len(subqueries) > 1 else ""
            status_cell = Text(f"{s['src_status']} → {s['tgt_status']}", style="dim")
            parent_key = str(s["seq"])
            table.add_row(Text(""), Text(s["method"], style="bold"), uri, status_cell, note,
                          key=parent_key)
            row_keys.append(parent_key)
            for i, sq in enumerate(subqueries):
                j = sq["j"]
                j_cell = (Text("-", style="dim") if j is None
                          else Text(f"{j:.3f}", style=f"bold {_score_color(j)}"))
                hits_cell = self._hits_cell(sq.get("src_hits"), sq.get("tgt_hits"))
                child_key = f"{s['seq']}:{i}"
                table.add_row(j_cell, "", Text(f"  ↳ {sq['label']}", style="dim"),
                              hits_cell, sq.get("j_label") or "", key=child_key)
                row_keys.append(child_key)
        if self._selected_key is not None and self._selected_key in row_keys:
            table.move_cursor(row=row_keys.index(self._selected_key))

    @staticmethod
    def _hits_cell(sh: Optional[int], th: Optional[int]) -> Text:
        if sh is None or th is None:
            return Text("")
        if sh == th:
            style = "green"
        elif max(sh, th) > 0 and min(sh, th) / max(sh, th) >= 0.95:
            style = "yellow"
        else:
            style = "red"
        return Text(f"{sh} → {th}", style=style)

    def on_data_table_row_highlighted(self, event: DataTable.RowHighlighted) -> None:
        key = event.row_key.value if event.row_key is not None else None
        if key == self._selected_key:
            return
        self._selected_key = key
        self._update_detail()

    def _lookup_selected(self) -> Optional[Tuple[Dict, Optional[Dict]]]:
        """The selected (tuple, subquery) pair — subquery is None when a parent row is
        selected. Looks the tuple up by seq in the live buffer rather than caching it at
        selection time, so this still works right after a reset clears everything."""
        if self._selected_key is None:
            return None
        if ":" in self._selected_key:
            seq_str, idx_str = self._selected_key.split(":", 1)
            seq, idx = int(seq_str), int(idx_str)
        else:
            seq, idx = int(self._selected_key), None
        with self._buf_lock:
            s = next((x for x in self._buf if x.get("seq") == seq), None)
        if s is None:
            return None
        if idx is None:
            return s, None
        subqueries = s.get("subqueries", [])
        if idx >= len(subqueries):
            return None
        return s, subqueries[idx]

    def _update_detail(self) -> None:
        src_pane = self.query_one("#detail-src", Static)
        tgt_pane = self.query_one("#detail-tgt", Static)
        if self._selected_key is None:
            src_pane.update(Text(
                "select a row to see detail  ·  v: hits/request  ·  c: copy request", style="dim"))
            tgt_pane.update("")
            return
        found = self._lookup_selected()
        if found is None:
            src_pane.update(Text("(row no longer in buffer)", style="dim"))
            tgt_pane.update("")
            return
        s, sq = found

        if self._view_mode == "request":
            if sq is not None and sq.get("src_sub_ndjson") is not None:
                src_pane.update(self._format_ndjson(f"SOURCE — sub-query {sq['label']}",
                                                     sq.get("src_sub_ndjson")))
                tgt_pane.update(self._format_ndjson(f"TARGET — sub-query {sq['label']}",
                                                     sq.get("tgt_sub_ndjson")))
            else:
                src_pane.update(self._format_request("SOURCE REQUEST", s.get("src_request")))
                tgt_pane.update(self._format_request("TARGET REQUEST", s.get("tgt_request")))
            return

        if sq is None:
            src_pane.update(self._subquery_overview(s))
            tgt_pane.update("")
            return

        src_list, tgt_list = sq.get("src_hit_list") or [], sq.get("tgt_hit_list") or []
        if not src_list and not tgt_list:
            src_pane.update(Text(
                f"{s['method']} {s['uri']}  [{sq['label']}]\n"
                f"No hit-level detail for this sub-query.\n"
                f"Press 'v' to view its raw request instead.",
                style="dim"))
            tgt_pane.update("")
            return
        src_ids = {h["id"] for h in src_list}
        tgt_ids = {h["id"] for h in tgt_list}
        src_pane.update(self._hit_list_text(f"SOURCE — {sq['label']}", sq.get("src_hits"), src_list, tgt_ids))
        tgt_pane.update(self._hit_list_text(f"TARGET — {sq['label']}", sq.get("tgt_hits"), tgt_list, src_ids))

    @staticmethod
    def _subquery_overview(s: Dict) -> Text:
        """A parent row's detail: a compact list of its sub-queries and their scores — the
        overview a blended average used to replace. Select a sub-query row below for its
        full hit-ID diff."""
        subqueries = s.get("subqueries", [])
        text = Text(f"{s['method']} {s['uri']}\n", style="bold")
        n = len(subqueries)
        text.append(f"{n} sub-quer{'y' if n == 1 else 'ies'} "
                     f"— select one below for hit detail\n\n", style="dim")
        for sq in subqueries:
            j = sq["j"]
            j_text = "   -   " if j is None else f"{j:>7.3f}"
            text.append(j_text, style=f"bold {_score_color(j)}")
            text.append(f"  {sq['label']}")
            sh, th = sq.get("src_hits"), sq.get("tgt_hits")
            if sh is not None or th is not None:
                text.append(f"   ({sh} → {th})", style="dim")
            text.append("\n")
        return text

    @staticmethod
    def _format_ndjson(label: str, pair: Optional[List[Dict]]) -> Text:
        text = Text(f"{label}\n", style="bold")
        if not pair:
            text.append("(not captured)", style="dim")
            return text
        text.append("\n".join(json.dumps(obj) for obj in pair))
        return text

    @staticmethod
    def _format_request(label: str, req: Optional[Dict]) -> Text:
        """The raw captured HTTP request, formatted so it's directly copy/paste-able — a
        request line, headers, and a pretty-printed body if there was one."""
        text = Text(f"{label}\n", style="bold")
        if not req:
            text.append("(not captured)", style="dim")
            return text
        method = req.get("Method", "?")
        uri = req.get("Request-URI", "?")
        version = req.get("HTTP-Version", "HTTP/1.1")
        text.append(f"{method} {uri} {version}\n")
        skip = {"Method", "Request-URI", "HTTP-Version", "payload"}
        for key, value in req.items():
            if key in skip:
                continue
            rendered = ", ".join(value) if isinstance(value, list) else str(value)
            text.append(f"{key}: {rendered}\n", style="dim")
        payload = req.get("payload", {}) or {}
        body = None
        # Four documented shapes (see ResultsToLogsConsumer's tuple-format docstring), checked
        # by key presence rather than truthiness — an empty dict/list body is still a real,
        # present body, not "no body".
        if "inlinedJsonBody" in payload:
            body = json.dumps(payload["inlinedJsonBody"], indent=2)
        elif "inlinedJsonSequenceBodies" in payload:
            # NDJSON — one JSON object per line, matching the real wire format of
            # _bulk/_msearch request bodies.
            body = "\n".join(json.dumps(obj) for obj in payload["inlinedJsonSequenceBodies"])
        elif "inlinedTextBody" in payload:
            body = payload["inlinedTextBody"]
        elif "inlinedBinaryBody" in payload or "inlinedBase64Body" in payload:
            body = "(binary body — not shown)"
        elif payload:
            # An unrecognized payload shape — show it raw rather than silently dropping it.
            body = json.dumps(payload, indent=2)
        if body:
            text.append("\n")
            text.append(body)
        return text

    @staticmethod
    def _hit_list_text(label: str, total: Optional[int], hits: List[Dict], other_ids: set) -> Text:
        text = Text(f"{label} — {total if total is not None else len(hits)} hits\n", style="bold")
        for i, h in enumerate(hits, 1):
            style = "" if h["id"] in other_ids else "red"
            text.append(f"{i:>2}. {h['id']}", style=style)
            if h.get("score") is not None:
                text.append(f"  ({h['score']:.2f})", style="dim")
            text.append("\n")
        if not hits:
            text.append("(no hits)", style="dim")
        return text

    def _update_footnote(self, buf: List[Dict]) -> None:
        n_options = sum(1 for s in buf if s.get("j_label") == "preflight")
        no_score = [s for s in buf if not s.get("subqueries") and s.get("j_label") != "preflight"]
        parts = []
        if n_options:
            parts.append(f"{n_options}× OPTIONS preflight (skipped)")
        if no_score:
            methods: Dict[str, int] = {}
            for s in no_score:
                methods[s["method"]] = methods.get(s["method"], 0) + 1
            breakdown = ", ".join(f"{c}× {m}" for m, c in sorted(methods.items()))
            parts.append(f"no score: {breakdown}")
        self.query_one("#footnote", Static).update("  •  ".join(parts))

    # --- Actions ---

    def action_reset(self) -> None:
        with self._buf_lock:
            self._buf.clear()
            self._status["total"] = 0
        self._selected_key = None
        self._update_detail()

    def action_toggle_view(self) -> None:
        self._view_mode = "request" if self._view_mode == "hits" else "hits"
        self._update_detail()

    def action_copy_request(self) -> None:
        """Copy the selected row's request(s) to the system clipboard — the whole HTTP
        request for a parent row (or a single-query child), or just that one sub-query's
        NDJSON slice for an msearch child.

        Tries the OS's own clipboard tool (pbcopy/xclip/etc.) first — Textual's built-in
        copy_to_clipboard() only works via an OSC 52 terminal escape sequence, which its own
        docs say plainly does not work on macOS Terminal.app, so it's the fallback here, not
        the first attempt.
        """
        found = self._lookup_selected()
        if found is None:
            self.notify("No row selected to copy" if self._selected_key is None
                        else "Row no longer in buffer", severity="warning")
            return
        s, sq = found
        if sq is not None and sq.get("src_sub_ndjson") is not None:
            src_text = str(self._format_ndjson(f"SOURCE — sub-query {sq['label']}", sq.get("src_sub_ndjson")))
            tgt_text = str(self._format_ndjson(f"TARGET — sub-query {sq['label']}", sq.get("tgt_sub_ndjson")))
        else:
            src_text = str(self._format_request("SOURCE REQUEST", s.get("src_request")))
            tgt_text = str(self._format_request("TARGET REQUEST", s.get("tgt_request")))
        combined = f"{src_text}\n\n{tgt_text}"
        if copy_via_system_tool(combined):
            self.notify("Copied source + target request to clipboard")
        else:
            self.copy_to_clipboard(combined)
            self.notify(
                "No system clipboard tool found — sent via terminal escape sequence (OSC 52), "
                "which does not work on macOS Terminal.app. Press 'v' to view and select the "
                "text by hand instead.",
                severity="warning", timeout=8)
