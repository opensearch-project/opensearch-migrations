"""Text-UI replacement for 07-live-jaccard-kafka.sh — a live table of replayed tuples
scored for source/target agreement, read from the tuple-output Kafka topic.

Follows the structure of the loadtest TUI (opensearch-project/opensearch-migrations#3263):
a background thread owns the long-lived I/O (there, k6 run polling; here, the Kafka
consumer subprocess) and only ever touches a lock-guarded buffer. A timer on the main
thread snapshots that buffer and repaints — the same split that keeps a slow or stalled
consumer from ever blocking the UI.
"""
import collections
import itertools
import json
import logging
import threading
from typing import Deque, Dict, List, Optional

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
        # The highlighted row's identity, independent of the table rebuild every tick — a
        # tuple's seq never changes, so this survives the window sliding underneath it and
        # is what lets the detail pane and cursor position both be restored after a rebuild.
        self._selected_seq: Optional[int] = None
        # "hits" shows the side-by-side doc-ID diff; "request" shows the raw captured HTTP
        # requests for copy/paste — toggled with 'v', independent of which row is selected.
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

    def _update_summary(self, buf: List[Dict]) -> None:
        """Two distinct phases, not one blended "connecting" state:

        1. Setup — verifying the topic exists and creds work (ensure_ready() hasn't returned
           yet). Shown as its own message, since it's a one-shot check, not something the
           sparkline should represent.
        2. Live — setup succeeded and the consumer is attached. The standard sparkline layout
           renders immediately here, with placeholders for a still-empty window, rather than
           waiting for the first tuple to appear before showing the normal screen at all.
        """
        summary = self.query_one("#summary", Static)

        if self._status["error"]:
            summary.update(Text(f"Setup failed: {self._status['error']}", style="bold red"))
            return
        if not self._status["connected"]:
            summary.update(f"Confirming topic '{self._topic}' exists and is reachable…")
            return

        scoreable = [s for s in buf if s["j"] is not None]
        window = scoreable[-self._window:]
        blanks = self._window - len(window)
        text = Text("Jaccard  ")
        text.append(Text("░ " * blanks, style="dim"))
        text.append(Text(" ").join(_spark_char(s["j"]) for s in window))
        text.append("\n")
        if window:
            js = [s["j"] for s in window]
            avg_j = sum(js) / len(js)
            text.append("avg ")
            text.append(f"{avg_j:.3f}", style=f"bold {_score_color(avg_j)}")
            text.append(f"   min {min(js):.3f}   max {max(js):.3f}"
                         f"   ({len(window)} of {self._status['total']} tuples seen)")
        else:
            text.append(f"0 tuples seen yet — waiting for live traffic on '{self._topic}'")
            if not self._status["running"]:
                text.append("  (consumer disconnected)", style="dim")
        summary.update(text)

    def _rebuild_table(self, buf: List[Dict]) -> None:
        """Repaint the table, keeping the cursor (and so the detail pane) on the same tuple
        across refreshes — row indexes shift as the window slides, so seq, not index, is what
        identifies a row from one rebuild to the next."""
        table = self.table
        table.clear()
        show = [s for s in buf if s.get("j_label") != "preflight"][-self._window:]
        added: List[Dict] = []
        for s in show:
            sh, th = s.get("src_hits"), s.get("tgt_hits")
            if sh is None and th is None:
                continue
            j = s["j"]
            j_cell = Text("-", style="dim") if j is None else Text(f"{j:.3f}", style=f"bold {_score_color(j)}")
            hits_cell = self._hits_cell(sh, th)
            uri = s["uri"] or "?"
            if len(uri) > 50:
                uri = uri[:47] + "..."
            table.add_row(j_cell, s["method"], uri, hits_cell, s.get("j_label") or "",
                          key=str(s["seq"]))
            added.append(s)
        if self._selected_seq is not None:
            for i, s in enumerate(added):
                if s["seq"] == self._selected_seq:
                    table.move_cursor(row=i)
                    break

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
        seq = int(event.row_key.value) if event.row_key is not None and event.row_key.value else None
        if seq == self._selected_seq:
            return
        self._selected_seq = seq
        self._update_detail()

    def _lookup_selected(self) -> Optional[Dict]:
        if self._selected_seq is None:
            return None
        with self._buf_lock:
            return next((x for x in self._buf if x.get("seq") == self._selected_seq), None)

    def _update_detail(self) -> None:
        """Render the highlighted tuple into the two side-by-side panes — either the hit-ID
        diff or the raw request, per self._view_mode ('v' toggles between them).

        Looks the tuple up by seq in the live buffer rather than caching it at selection time,
        so the pane still works right after a reset clears everything (falls through to "no
        longer available" instead of showing stale data).
        """
        src_pane = self.query_one("#detail-src", Static)
        tgt_pane = self.query_one("#detail-tgt", Static)
        if self._selected_seq is None:
            src_pane.update(Text(
                "select a row to see detail  ·  v: hits/request  ·  c: copy request", style="dim"))
            tgt_pane.update("")
            return
        s = self._lookup_selected()
        if s is None:
            src_pane.update(Text("(tuple no longer in buffer)", style="dim"))
            tgt_pane.update("")
            return

        if self._view_mode == "request":
            src_pane.update(self._format_request("SOURCE REQUEST", s.get("src_request")))
            tgt_pane.update(self._format_request("TARGET REQUEST", s.get("tgt_request")))
            return

        src_list, tgt_list = s.get("src_hit_list") or [], s.get("tgt_hit_list") or []
        if not src_list and not tgt_list:
            src_pane.update(Text(
                f"{s['method']} {s['uri']}\n"
                f"No hit-level detail for this request (status {s['src_status']} → {s['tgt_status']}).\n"
                f"Press 'v' to view the raw request instead.",
                style="dim"))
            tgt_pane.update("")
            return
        src_ids = {h["id"] for h in src_list}
        tgt_ids = {h["id"] for h in tgt_list}
        src_pane.update(self._hit_list_text("SOURCE", s.get("src_hits"), src_list, tgt_ids))
        tgt_pane.update(self._hit_list_text("TARGET", s.get("tgt_hits"), tgt_list, src_ids))

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
        no_score = [s for s in buf if s["j"] is None and s.get("j_label") != "preflight"]
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
        self._selected_seq = None
        self._update_detail()

    def action_toggle_view(self) -> None:
        self._view_mode = "request" if self._view_mode == "hits" else "hits"
        self._update_detail()

    def action_copy_request(self) -> None:
        """Copy the selected tuple's source + target request to the system clipboard.

        Tries the OS's own clipboard tool (pbcopy/xclip/etc.) first — Textual's built-in
        copy_to_clipboard() only works via an OSC 52 terminal escape sequence, which its own
        docs say plainly does not work on macOS Terminal.app, so it's the fallback here, not
        the first attempt.
        """
        s = self._lookup_selected()
        if s is None:
            self.notify("No row selected to copy" if self._selected_seq is None
                        else "Tuple no longer in buffer", severity="warning")
            return
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
