"""Text-UI replacement for 07-live-jaccard-kafka.sh — a live table of replayed tuples
scored for source/target agreement, read from the tuple-output Kafka topic.

Follows the structure of the loadtest TUI (opensearch-project/opensearch-migrations#3263):
a background thread owns the long-lived I/O (there, k6 run polling; here, the Kafka
consumer subprocess) and only ever touches a lock-guarded buffer. A timer on the main
thread snapshots that buffer and repaints — the same split that keeps a slow or stalled
consumer from ever blocking the UI.
"""
import collections
import logging
import threading
from typing import Deque, Dict, List, Optional

from rich.text import Text
from textual.app import App, ComposeResult
from textual.containers import Container
from textual.widgets import DataTable, Footer, Header, Static

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
    """
    BINDINGS = [
        ("r", "reset", "Reset window"),
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
        self.is_exiting = False

    def compose(self) -> ComposeResult:
        yield Header()
        yield Static("", id="summary")
        yield Container(DataTable(id=TABLE_ANCHOR, cursor_type="row"))
        yield Static("", id="footnote")
        yield Footer()

    def on_mount(self) -> None:
        self.table.add_columns(*COLUMNS)
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
            with self._buf_lock:
                self._buf.append(score_tuple(record))
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
        table = self.table
        table.clear()
        show = [s for s in buf if s.get("j_label") != "preflight"][-self._window:]
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
            table.add_row(j_cell, s["method"], uri, hits_cell, s.get("j_label") or "")

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
