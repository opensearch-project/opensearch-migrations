"""Text-UI replacement for 07-live-jaccard-kafka.sh — a live table of replayed tuples
scored for source/target agreement, read from the tuple-output Kafka topic by default, or
from S3 (--source s3, mirroring 06-live-jaccard.sh) as an alternative. Both sinks write the
same tuple JSON schema, so scoring.py is source-agnostic — only which reader class
_bootstrap_and_stream() constructs differs.

Follows the structure of the loadtest TUI (opensearch-project/opensearch-migrations#3263):
a background thread owns the long-lived I/O (there, k6 run polling; here, the Kafka
consumer subprocess or S3 poll loop) and only ever touches a lock-guarded buffer. A timer
on the main thread snapshots that buffer and repaints — the same split that keeps a slow or
stalled reader from ever blocking the UI.

Data model: every replayed request is a PARENT row (its identity — method, URI, status
codes) plus one or more CHILD rows — one per independently-comparable item. A plain search
with just hits has exactly one child; an _msearch has one item-group per NDJSON search
action; and within any single response, hits and every named aggregation are each their own
item, since a size:0 facet query with two aggregations is really two independent
comparisons. Scoring lives on the children only, on purpose: a blended average hides exactly
the failure that matters (one badly-diverged item pulled toward 1.0 by good ones still looks
fine in aggregate), so the parent row carries no score of its own.
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
from .s3_reader import S3ReaderError, S3TupleReader
from .scoring import score_tuple

logger = logging.getLogger(__name__)

TABLE_ANCHOR = "tuples"
COLUMNS = ("J", "METHOD", "URI", "SRC → TGT", "NOTE")
TYPE_COLUMNS = ("TYPE", "N", "SCORED", "AVG", "MIN", "MAX")
SPARK = "▁▂▃▄▅▆▇█"


def _score_color(j: Optional[float]) -> str:
    if j is None:
        return "dim"
    if j >= 0.95:
        return "green"
    if j >= 0.80:
        return "yellow"
    return "red"


def _running_avg(st: Dict) -> Optional[float]:
    """Running average of an accumulated stat bucket, or None if nothing in it scored."""
    return (st["sum"] / st["scored_count"]) if st["scored_count"] else None


def _score_cell(j: Optional[float]) -> str:
    """A score rendered in the fixed 7-wide column the score tables share."""
    return "   -   " if j is None else f"{j:>7.3f}"


def _score_style(j: Optional[float]) -> str:
    return "bold" if j is None else _score_color(j)


def _type_key(label: str) -> str:
    """Strip a per-position prefix like '1/2 (typefilter)' off a subquery label, leaving just
    the comparison TYPE — 'hits', 'agg:filter_product_type', etc. Labels are built in
    scoring.py as '{position prefix} · {type}'; grouping by this tail is what lets the
    by-type view answer "how is agg:supplier doing overall" across every position/request
    it's ever shown up in, rather than one row per position."""
    if " · " in label:
        return label.rsplit(" · ", 1)[1]
    return label


def _spark_char(j: Optional[float]) -> Text:
    if j is None:
        return Text("░", style="dim")
    idx = min(7, int(j * 8))
    return Text(SPARK[idx], style=f"bold {_score_color(j)}")


def _has_scoreable_subqueries(s: Dict) -> bool:
    """subqueries is only ever non-empty for a request that had at least one hits section or
    named aggregation to compare (scoring.py's _response_items) — an index/delete/_count-style
    request with neither produces an empty list, so its own emptiness is the filter."""
    if s.get("j_label") == "preflight":
        return False
    return bool(s.get("subqueries"))


class JaccardApp(App):
    """Live replay-quality monitor. One instance == one Kafka topic being watched."""

    CSS = """
    #summary { height: 4; padding: 0 1; }
    #footnote { height: auto; max-height: 4; padding: 0 1; color: $text-muted; }
    DataTable { height: 1fr; }
    #detail { height: 14; border-top: solid $primary; }
    #detail-src, #detail-tgt { width: 1fr; padding: 0 1; overflow-y: auto; }
    #detail-src { border-right: solid $primary-darken-2; }
    """
    BINDINGS = [
        ("r", "reset", "Reset window"),
        ("t", "toggle_top_view", "Live/By type"),
        ("v", "toggle_view", "Hits/Request"),
        ("m", "toggle_metric", "Jaccard/RBO"),
        ("c", "copy_request", "Copy request"),
        ("q", "quit", "Quit"),
    ]

    def __init__(self, namespace: str, topic: str = "tuple-output", *,
                 window: int = 15, refresh_interval: float = 2.0,
                 auto_create_topic: bool = True,
                 source: str = "kafka",
                 s3_bucket: str = "migrations-default-123456789012-dev-us-east-2",
                 s3_prefix: str = "tuples/",
                 poll_interval: float = 10.0):
        super().__init__()
        self.title = f"[{namespace}] live replay quality"
        self.sub_title = (
            f"topic: {topic}" if source == "kafka" else f"s3://{s3_bucket}/{s3_prefix}"
        )
        # Shared by the "confirming..." and "waiting for..." status messages so neither one
        # says "topic" while actually polling S3, or vice versa.
        self._source_description = (
            f"topic '{topic}'" if source == "kafka" else f"'s3://{s3_bucket}/{s3_prefix}'"
        )
        self._namespace = namespace
        self._topic = topic
        self._window = window
        self._refresh_interval = refresh_interval
        self._auto_create_topic = auto_create_topic
        # "kafka" (default) streams tuple-output near-real-time; "s3" polls the same tuples
        # written to S3 instead (mirrors 06-live-jaccard.sh) — real, rotation-interval-scale
        # lag, but useful when the Kafka topic isn't set up or reachable from this machine.
        self._source = source
        self._s3_bucket = s3_bucket
        self._s3_prefix = s3_prefix
        self._poll_interval = poll_interval
        # Holds every scored tuple seen this "generation" (since last reset), capped so a
        # long-running demo can't grow this unboundedly; the window is a suffix of it.
        self._buf: Deque[Dict] = collections.deque(maxlen=window * 20)
        self._buf_lock = threading.Lock()
        self._status = {"total": 0, "running": True, "connected": False, "error": None}
        # KafkaTupleReader or S3TupleReader depending on self._source — both duck-type the
        # same ensure_ready()/stream()/stop() contract.
        self._reader: Optional[object] = None
        self._seq = itertools.count(1)
        # Row identity, independent of the table rebuild every tick — "<seq>" for a parent
        # row, "<seq>:<subquery index>" for a child. Neither changes once assigned, so this
        # survives the window sliding underneath it and is what lets the detail pane and
        # cursor position both be restored after a rebuild.
        self._selected_key: Optional[str] = None
        # "hits" shows the side-by-side doc-ID diff; "request" shows the raw captured HTTP
        # request(s) for copy/paste — toggled with 'v', independent of which row is selected.
        self._view_mode = "hits"
        # Which score to DISPLAY for "hits" items — both are always computed and stored on
        # every item (scoring.py's _response_items), so toggling this is just a matter of
        # which already-computed field gets read; nothing needs rescoring. RBO has no
        # equivalent for aggregation items (weighted bucket overlap isn't a ranked-list
        # comparison), so those always show their one score regardless of this setting.
        self._score_metric = "jaccard"
        # Unbounded, per-label running stats — deliberately NOT tied to the sliding window or
        # to 'r' (reset only clears what's on screen). A flat overall average has the same
        # blind spot per-request averaging did before the parent/child rework: one badly
        # diverged label could hide inside an otherwise-good overall number. Both metrics are
        # tracked simultaneously (mirroring how each item always carries both scores) so
        # toggling 'm' swaps which lifetime breakdown is shown without losing history.
        self._lifetime_stats = {"jaccard": {}, "rbo": {}}
        # Same shape as _lifetime_stats (count/scored_count/sum/min/max per metric), but keyed
        # by comparison TYPE (_type_key(sq["label"]) — "hits", "agg:filter_product_type", ...)
        # instead of by scoring method. _lifetime_stats answers "how is 'doc IDs' scoring doing
        # overall"; this answers "how is 'agg:filter_product_type' doing overall" — two
        # different axes over the same underlying items, so both are tracked, not derived from
        # each other (a "doc IDs" bucket can span many item types' worth of hits comparisons;
        # an "agg:filter_product_type" bucket can span many scoring-method labels if its shape
        # ever varies request to request).
        self._lifetime_by_type = {"jaccard": {}, "rbo": {}}
        # type_key -> scoring-method label -> stats — the by-type view's drill-down: which
        # scoring methods make up a given type's rolled-up total, and how each is doing.
        self._lifetime_by_type_detail: Dict[str, Dict[str, Dict]] = {"jaccard": {}, "rbo": {}}
        self._lifetime_preflight = 0
        self._lifetime_no_subqueries: Dict[str, int] = {}
        # The seqs shown last time _rebuild_table ran — a full clear()+re-add on every tick
        # visibly flickers and resets scroll position, so it's skipped whenever the visible
        # set of requests hasn't actually changed (the common case between new arrivals).
        self._last_row_signature: Optional[Tuple[int, ...]] = None
        # "live" is the per-request table this class started with; "types" replaces it with
        # one row per comparison TYPE (hits, agg:filter_product_type, ...), aggregated across
        # every position it's ever appeared at — toggled with 't', independent of the sliding
        # window / 'r' reset, same lifetime scope as the footnote it's built from.
        self._top_view = "live"
        self._last_types_signature: Optional[Tuple[Tuple[str, int, float], ...]] = None
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
        self.run_worker(self._bootstrap_and_stream, thread=True, name="tuple_reader")
        self.set_interval(self._refresh_interval, self._repaint)

    def on_unmount(self) -> None:
        self.is_exiting = True
        if self._reader:
            self._reader.stop()

    @property
    def table(self) -> DataTable:
        return self.query_one(f"#{TABLE_ANCHOR}", DataTable)

    # --- Reader bootstrap + streaming (background thread — no UI calls except via
    #     call_from_thread, and only for one-shot setup failures) ---

    def _bootstrap_and_stream(self) -> None:
        if self._source == "s3":
            reader = S3TupleReader(
                self._namespace, bucket=self._s3_bucket, prefix=self._s3_prefix,
                poll_interval=self._poll_interval)
        else:
            reader = KafkaTupleReader(self._namespace, self._topic,
                                      auto_create_topic=self._auto_create_topic)
        try:
            reader.ensure_ready()
        except (KafkaReaderError, S3ReaderError) as e:
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
                self._record_lifetime(s)
        self._status["running"] = False

    def _record_lifetime(self, s: Dict) -> None:
        """Accumulate one newly-scored tuple into every lifetime breakdown this app tracks —
        by scoring method (_lifetime_stats), by comparison type (_lifetime_by_type), and type
        drilled down to scoring method (_lifetime_by_type_detail). Caller must already hold
        _buf_lock — this mutates the same shared state _repaint() reads."""
        if s.get("j_label") == "preflight":
            self._lifetime_preflight += 1
            return
        subqueries = s.get("subqueries", [])
        if not subqueries:
            method = s["method"]
            self._lifetime_no_subqueries[method] = self._lifetime_no_subqueries.get(method, 0) + 1
            return
        for sq in subqueries:
            type_key = _type_key(sq.get("label") or "(unlabeled)")
            for metric in ("jaccard", "rbo"):
                value, label = self._metric_score(sq, metric)
                label = label or "(unlabeled)"
                self._accumulate(self._lifetime_stats[metric], label, value)
                self._accumulate(self._lifetime_by_type[metric], type_key, value)
                self._accumulate(
                    self._lifetime_by_type_detail[metric].setdefault(type_key, {}), label, value)

    @staticmethod
    def _accumulate(store: Dict[str, Dict], key: str, value: Optional[float]) -> None:
        bucket = store.setdefault(
            key, {"count": 0, "scored_count": 0, "sum": 0.0, "min": None, "max": None})
        bucket["count"] += 1
        if value is not None:
            bucket["scored_count"] += 1
            bucket["sum"] += value
            bucket["min"] = value if bucket["min"] is None else min(bucket["min"], value)
            bucket["max"] = value if bucket["max"] is None else max(bucket["max"], value)

    # --- Repaint (main thread, on a timer — never touches the Kafka subprocess) ---

    def _repaint(self) -> None:
        with self._buf_lock:
            buf = list(self._buf)
        self._update_summary(buf)
        if self._top_view == "types":
            self._rebuild_types_table()
        else:
            self._rebuild_table(buf)
        self._update_footnote()

    @staticmethod
    def _metric_score(sq: Dict, metric: str) -> Tuple[Optional[float], Optional[str]]:
        """The (score, label) for one item under a GIVEN metric — RBO only applies to "hits"
        items; aggregations keep their own weighted bucket-overlap score under either metric,
        since RBO has no meaningful equivalent for them. Parameterized (rather than reading
        self._score_metric directly) so lifetime stats can accumulate both metrics at once
        without needing the display mode to match what's being recorded."""
        if metric == "rbo" and sq.get("kind") == "hits":
            return sq.get("rbo_j"), sq.get("rbo_label")
        return sq.get("j"), sq.get("j_label")

    def _effective_score(self, sq: Dict) -> Tuple[Optional[float], Optional[str]]:
        """The (score, label) to DISPLAY for one item, per the current metric mode."""
        return self._metric_score(sq, self._score_metric)

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
            summary.update(f"Confirming {self._source_description} exists and is reachable…")
            return

        all_js = [j for s in buf for sq in s.get("subqueries", [])
                  for j, _ in [self._effective_score(sq)] if j is not None]
        window = all_js[-self._window:]
        blanks = self._window - len(window)
        metric_name = "RBO" if self._score_metric == "rbo" else "Jaccard"
        text = Text(f"{metric_name}  ")
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
            text.append(f"0 tuples seen yet — waiting for live traffic on {self._source_description}")
            if not self._status["running"]:
                text.append("  (consumer disconnected)", style="dim")
        summary.update(text)

    def _rebuild_table(self, buf: List[Dict]) -> None:
        """Repaint the table, keeping the cursor (and so the detail pane) on the same row
        across refreshes — row indexes shift as the window slides, so the row key, not
        index, is what identifies a row from one rebuild to the next.

        A tuple's own data never changes once scored, so the visible set of seqs fully
        determines the table's content — when it's unchanged since the last tick (the common
        case between new arrivals), clear()+re-add is skipped entirely. Doing it every tick
        regardless was visibly flickering and resetting scroll position while the user was
        just reading, not because anything new had actually arrived.
        """
        visible = list(reversed(self._visible_tuples(buf)))
        signature = tuple(s["seq"] for s in visible)
        if signature == self._last_row_signature:
            return
        self._last_row_signature = signature

        table = self.table
        scroll_y = table.scroll_y
        table.clear()
        row_keys: List[str] = []
        # Newest request first — a live monitor's most useful row is the one that just
        # happened, not the oldest one still in the window. Each request's own sub-queries
        # stay in their natural 1st/2nd/3rd order underneath it; only request-to-request
        # order flips.
        for s in visible:
            uri = s["uri"] or "?"
            if len(uri) > 50:
                uri = uri[:47] + "..."
            subqueries = s.get("subqueries", [])
            note = f"{len(subqueries)} rows" if len(subqueries) > 1 else ""
            status_cell = Text(f"{s['src_status']} → {s['tgt_status']}", style="dim")
            parent_key = str(s["seq"])
            table.add_row(Text(""), Text(s["method"], style="bold"), uri, status_cell, note,
                          key=parent_key)
            row_keys.append(parent_key)
            for i, sq in enumerate(subqueries):
                j, j_label = self._effective_score(sq)
                j_cell = (Text("-", style="dim") if j is None
                          else Text(f"{j:.3f}", style=f"bold {_score_color(j)}"))
                hits_cell = self._hits_cell(sq.get("src_hits"), sq.get("tgt_hits"))
                child_key = f"{s['seq']}:{i}"
                table.add_row(j_cell, "", Text(f"  ↳ {sq['label']}", style="dim"),
                              hits_cell, j_label or "", key=child_key)
                row_keys.append(child_key)
        if self._selected_key is not None and self._selected_key in row_keys:
            # scroll=False: the scroll_y restore below is authoritative — letting this also
            # auto-scroll would fight it and undo exactly what's being preserved.
            table.move_cursor(row=row_keys.index(self._selected_key), scroll=False)
        # Deferred one refresh: setting scroll_y synchronously right after add_row() clamps
        # against stale (pre-layout) scroll extents, so a restore issued immediately here can
        # silently come out smaller than what was actually saved.
        self.call_after_refresh(setattr, table, "scroll_y", scroll_y)

    def _rebuild_types_table(self) -> None:
        """Repaint the table in 'by type' mode: one row per comparison TYPE (hits,
        agg:filter_product_type, agg:supplier, ...), aggregated across every position/request
        that type has ever appeared at — a live per-request table answers "how did THIS
        request do"; this answers "how is agg:supplier doing overall, across everything
        replayed so far". Reads _lifetime_by_type directly — already keyed by type, not by
        scoring method, so no re-derivation is needed here.
        """
        with self._buf_lock:
            by_type = {k: dict(v) for k, v in self._lifetime_by_type[self._score_metric].items()}

        ranked = sorted(by_type.items(), key=lambda kv: -kv[1]["count"])
        signature = tuple((k, v["count"], round(v["sum"], 6)) for k, v in ranked)
        if signature == self._last_types_signature:
            return
        self._last_types_signature = signature

        table = self.table
        scroll_y = table.scroll_y
        table.clear()
        row_keys: List[str] = []
        for type_key, st in ranked:
            avg = _running_avg(st)
            avg_cell = (Text("-", style="dim") if avg is None
                        else Text(f"{avg:.3f}", style=f"bold {_score_color(avg)}"))
            min_cell = "-" if st["min"] is None else f"{st['min']:.3f}"
            max_cell = "-" if st["max"] is None else f"{st['max']:.3f}"
            table.add_row(Text(type_key, style="bold"), str(st["count"]), str(st["scored_count"]),
                          avg_cell, min_cell, max_cell, key=type_key)
            row_keys.append(type_key)
        if self._selected_key is not None and self._selected_key in row_keys:
            table.move_cursor(row=row_keys.index(self._selected_key), scroll=False)
        self.call_after_refresh(setattr, table, "scroll_y", scroll_y)

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
        if self._top_view == "types":
            self._update_types_detail(src_pane, tgt_pane)
            return
        if self._selected_key is None:
            src_pane.update(Text(
                "select a row to see detail  ·  v: hits/request  ·  m: jaccard/rbo  ·  c: copy request",
                style="dim"))
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
                src_pane.update(self._format_ndjson(f"SOURCE — {sq['label']}", sq.get("src_sub_ndjson")))
                tgt_pane.update(self._format_ndjson(f"TARGET — {sq['label']}", sq.get("tgt_sub_ndjson")))
            else:
                src_pane.update(self._format_request("SOURCE REQUEST", s.get("src_request")))
                tgt_pane.update(self._format_request("TARGET REQUEST", s.get("tgt_request")))
            return

        if sq is None:
            src_pane.update(self._subquery_overview(s))
            tgt_pane.update("")
            return

        if sq.get("kind") == "agg":
            src_pane.update(self._agg_detail_text(f"SOURCE — {sq['label']}", sq.get("src_agg"), sq.get("tgt_agg")))
            tgt_pane.update(self._agg_detail_text(f"TARGET — {sq['label']}", sq.get("tgt_agg"), sq.get("src_agg")))
            return

        src_list, tgt_list = sq.get("src_hit_list") or [], sq.get("tgt_hit_list") or []
        if not src_list and not tgt_list:
            src_pane.update(Text(
                f"{s['method']} {s['uri']}  [{sq['label']}]\n"
                f"No hit-level detail for this row.\n"
                f"Press 'v' to view its raw request instead.",
                style="dim"))
            tgt_pane.update("")
            return
        src_ids = {h["id"] for h in src_list}
        tgt_ids = {h["id"] for h in tgt_list}
        src_pane.update(self._hit_list_text(f"SOURCE — {sq['label']}", sq.get("src_hits"), src_list, tgt_ids))
        tgt_pane.update(self._hit_list_text(f"TARGET — {sq['label']}", sq.get("tgt_hits"), tgt_list, src_ids))

    def _update_types_detail(self, src_pane: Static, tgt_pane: Static) -> None:
        """The selected type's breakdown by scoring method — e.g. 'hits' items are scored
        either 'doc IDs' or 'hit count ratio' depending on whether either side's response had
        an empty hits array; this is where that split is still visible, since the main
        by-type table itself only shows the type's rolled-up total."""
        if self._selected_key is None:
            src_pane.update(Text(
                "select a type to see its breakdown by scoring method  ·  t: live view  ·  m: jaccard/rbo",
                style="dim"))
            tgt_pane.update("")
            return
        type_key = self._selected_key
        with self._buf_lock:
            by_label = {k: dict(v) for k, v
                        in self._lifetime_by_type_detail[self._score_metric].get(type_key, {}).items()}
        text = Text(f"{type_key} — by scoring method\n", style="bold")
        if not by_label:
            text.append("(no data)", style="dim")
        else:
            for label, st in sorted(by_label.items(), key=lambda kv: -kv[1]["count"]):
                avg = _running_avg(st)
                text.append(_score_cell(avg), style=_score_style(avg))
                text.append(f"  {label}   n={st['count']}")
                if avg is not None:
                    text.append(f"   [{st['min']:.3f}–{st['max']:.3f}]", style="dim")
                text.append("\n")
        src_pane.update(text)
        tgt_pane.update("")

    @staticmethod
    def _agg_detail_text(label: str, agg: Optional[Dict], other_agg: Optional[Dict]) -> Text:
        """One side's view of an aggregation: bucket key/doc_count pairs (mismatched keys in
        red, same idea as the hit-ID diff), or a bare value for a metric aggregation."""
        text = Text(f"{label}\n", style="bold")
        agg = agg if isinstance(agg, dict) else {}
        buckets = agg.get("buckets")
        if buckets is not None:
            other_keys = {b.get("key") for b in (other_agg or {}).get("buckets", []) if isinstance(b, dict)}
            for b in buckets:
                if not isinstance(b, dict):
                    continue
                key, count = b.get("key"), b.get("doc_count")
                style = "" if key in other_keys else "red"
                text.append(f"{str(key):<28}", style=style)
                text.append(f" {count}\n", style="dim")
            if not buckets:
                text.append("(no buckets)", style="dim")
            return text
        if "value" in agg:
            text.append(f"value: {agg['value']}")
            return text
        if "doc_count" in agg:
            text.append(f"doc_count: {agg['doc_count']}")
            return text
        text.append("(no aggregation data)", style="dim")
        return text

    def _subquery_overview(self, s: Dict) -> Text:
        """A parent row's detail: a compact list of its rows (hits and/or aggregations) and
        their scores — the overview a blended average used to replace. Select a row below for
        its full detail."""
        subqueries = s.get("subqueries", [])
        text = Text(f"{s['method']} {s['uri']}\n", style="bold")
        n = len(subqueries)
        text.append(f"{n} row{'s' if n != 1 else ''} "
                    f"— select one below for detail\n\n", style="dim")
        for sq in subqueries:
            j, _ = self._effective_score(sq)
            text.append(_score_cell(j), style=f"bold {_score_color(j)}")
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
            style = "green" if h["id"] in other_ids else "red"
            text.append(f"{i:>2}. {h['id']}", style=style)
            if h.get("score") is not None:
                text.append(f"  ({h['score']:.2f})", style="dim")
            text.append("\n")
        if not hits:
            text.append("(no hits)", style="dim")
        return text

    # Most labels ever seen at once is small and known (doc IDs / hit count ratio / RBO / a
    # handful of agg + unscored variants from scoring.py) — this cap is just a backstop against
    # the footnote panel growing without bound if that ever changes.
    _MAX_LIFETIME_LABELS_SHOWN = 8

    def _update_footnote(self) -> None:
        """Lifetime, per-label running stats — unbounded, independent of the sliding window
        and of 'r' (which only clears what's on screen). A flat overall average would have
        the same blind spot per-request averaging did before the parent/child rework: one
        consistently-diverged label could hide inside an otherwise-good overall number.
        """
        with self._buf_lock:
            stats = {k: dict(v) for k, v in self._lifetime_stats[self._score_metric].items()}
            preflight = self._lifetime_preflight
            no_subq = dict(self._lifetime_no_subqueries)

        text = Text()
        meta_parts = []
        if preflight:
            meta_parts.append(f"{preflight}× OPTIONS preflight (skipped)")
        if no_subq:
            breakdown = ", ".join(f"{c}× {m}" for m, c in sorted(no_subq.items()))
            meta_parts.append(f"no score: {breakdown}")
        if meta_parts:
            text.append("  •  ".join(meta_parts) + "\n", style="dim")

        if not stats:
            text.append("lifetime: (no scored items yet)", style="dim")
        else:
            ranked = sorted(stats.items(), key=lambda kv: -kv[1]["count"])
            shown, hidden = ranked[:self._MAX_LIFETIME_LABELS_SHOWN], ranked[self._MAX_LIFETIME_LABELS_SHOWN:]
            text.append("LIFETIME", style="bold")
            text.append(f" ({self._score_metric})  ", style="dim")
            for label, st in shown:
                avg = _running_avg(st)
                text.append(f"{label}", style=_score_style(avg))
                text.append(f" n={st['count']}", style="dim")
                if avg is not None:
                    text.append(f" avg={avg:.3f} [{st['min']:.3f}–{st['max']:.3f}]", style="dim")
                text.append("   ")
            if hidden:
                text.append(f"+{len(hidden)} more label(s)", style="dim")
        self.query_one("#footnote", Static).update(text)

    # --- Actions ---

    def action_reset(self) -> None:
        with self._buf_lock:
            self._buf.clear()
            self._status["total"] = 0
        self._selected_key = None
        self._update_detail()
        self._repaint()

    def action_toggle_top_view(self) -> None:
        """Swap the whole main table between 'live' (one row per replayed request, the
        original view) and 'types' (one row per comparison TYPE, aggregated across every
        position it's ever appeared at). Column sets differ between the two, so the table's
        columns are rebuilt too, not just its rows; row-key formats also differ ("<seq>" /
        "<seq>:<idx>" vs. a bare type key), so any prior selection is dropped rather than
        carried across — it wouldn't resolve to anything meaningful on the other side anyway.
        """
        self._top_view = "types" if self._top_view == "live" else "live"
        self._selected_key = None
        self._last_row_signature = None
        self._last_types_signature = None
        table = self.table
        table.clear(columns=True)
        table.add_columns(*(TYPE_COLUMNS if self._top_view == "types" else COLUMNS))
        self._update_detail()
        self._repaint()

    def action_toggle_view(self) -> None:
        if self._top_view == "types":
            self.notify("Not available in 'by type' view — press 't' for live view",
                        severity="warning")
            return
        self._view_mode = "request" if self._view_mode == "hits" else "hits"
        self._update_detail()

    def action_toggle_metric(self) -> None:
        """Swap which already-computed score 'hits' items display — Jaccard (set overlap,
        order-blind) or RBO (rank-biased overlap, penalizes reordering). Both scores are
        always computed and stored per item, so this never needs to rescore anything already
        in the buffer; it just changes which stored field the table/summary/overview read."""
        self._score_metric = "rbo" if self._score_metric == "jaccard" else "jaccard"
        self.notify(f"Scoring metric: {self._score_metric.upper()}")
        self._last_row_signature = None  # force a table repaint even though seqs are unchanged
        self._update_detail()
        self._repaint()

    def action_copy_request(self) -> None:
        """Copy the selected row's request(s) to the system clipboard — the whole HTTP
        request for a parent row (or a single-query child), or just that one sub-query's
        NDJSON slice for an msearch child.

        Tries the OS's own clipboard tool (pbcopy/xclip/etc.) first — Textual's built-in
        copy_to_clipboard() only works via an OSC 52 terminal escape sequence, which its own
        docs say plainly does not work on macOS Terminal.app, so it's the fallback here, not
        the first attempt.
        """
        if self._top_view == "types":
            self.notify("Not available in 'by type' view — press 't' for live view",
                        severity="warning")
            return
        found = self._lookup_selected()
        if found is None:
            self.notify("No row selected to copy" if self._selected_key is None
                        else "Row no longer in buffer", severity="warning")
            return
        s, sq = found
        if sq is not None and sq.get("src_sub_ndjson") is not None:
            src_text = str(self._format_ndjson(f"SOURCE — {sq['label']}", sq.get("src_sub_ndjson")))
            tgt_text = str(self._format_ndjson(f"TARGET — {sq['label']}", sq.get("tgt_sub_ndjson")))
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
