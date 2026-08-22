"""Bounded, cancellable Kubernetes logs for workflow manage."""

from __future__ import annotations

import ast
import base64
import codecs
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
import secrets
import threading
import time
from typing import Any, Callable, Deque, Dict, Iterable, Mapping, Optional, Tuple

from kubernetes import client
from kubernetes.client.rest import ApiException

from ..commands.crd_utils import CRD_GROUP, CRD_VERSION


RESOURCE_OUTPUT_LABELS = {"strimzi.io/cluster"}
LABELS_NOT_PROPAGATED_TO_PODS = {
    "migrations.opensearch.org/run-number",
    "migrations.opensearch.org/workflow-name",
}
LOG_TARGET_PREFIX = "logs:"
WORKFLOW_STEP_TARGET_PREFIX = "logs:workflow-step:"
DEFAULT_TAIL_LINES = 1000
MAX_TAIL_LINES = 5000
DEFAULT_MAX_LINES = 10_000
DEFAULT_MAX_BYTES = 5 * 1024 * 1024


class LogError(RuntimeError):
    """Base class for managed log failures."""


class LogUnavailable(LogError):
    """A capability, target, or stream is no longer available."""


class LogTargetStale(LogError):
    """A server-issued target expired or no longer identifies its pod."""


@dataclass(frozen=True)
class LogSelection:
    """Internal source selection. It is never accepted directly from a browser."""

    kind: str
    label: str
    selector: Optional[str]
    pod_name: Optional[str]
    pod_uid: Optional[str]
    container: Optional[str]
    restart_count: Optional[int]
    previous: bool
    node_id: Optional[str] = None

    @property
    def supports_follow(self) -> bool:
        return not self.previous


@dataclass(frozen=True)
class LogTarget:
    id: str
    label: str
    kind: str
    pod_name: Optional[str]
    pod_uid: Optional[str]
    container: Optional[str]
    restart_count: Optional[int]
    previous: bool
    supports_follow: bool


@dataclass(frozen=True)
class LogTargetInventory:
    node_id: str
    capability_target_id: str
    targets: Tuple[LogTarget, ...]
    message: Optional[str] = None


@dataclass(frozen=True)
class LogRecord:
    timestamp: Optional[str]
    pod_name: str
    pod_uid: str
    container: str
    restart_count: int
    previous: bool
    message: str
    kind: str = "log"


@dataclass(frozen=True)
class LogEvent:
    sequence: int
    received_at: str
    timestamp: Optional[str]
    pod_name: str
    pod_uid: str
    container: str
    restart_count: int
    previous: bool
    message: str
    kind: str = "log"


@dataclass(frozen=True)
class LogPage:
    events: Tuple[LogEvent, ...]
    before_cursor: Optional[str]
    after_cursor: Optional[str]
    at_available_start: bool
    at_buffer_end: bool
    history_truncated: bool
    state: str


@dataclass(frozen=True)
class LogStream:
    id: str
    target: LogTarget
    state: str
    page: LogPage


@dataclass(frozen=True)
class LogStreamStatus:
    id: str
    state: str
    message: Optional[str] = None


def resource_log_selector(
    resource: Mapping[str, Any],
    prefix: str = "migrations.opensearch.org/",
) -> str:
    """Return only labels known to propagate from migration CRs to pods."""
    labels = ((resource.get("metadata") or {}).get("labels") or {})
    parts = [
        f"{key}={value}"
        for key, value in sorted(labels.items())
        if (
            (key.startswith(prefix) or key in RESOURCE_OUTPUT_LABELS)
            and key not in LABELS_NOT_PROPAGATED_TO_PODS
            and value
        )
    ]
    if not parts:
        raise LogUnavailable("Migration resource has no pod labels.")
    return ",".join(parts)


class KubernetesLogSource:
    """Resolve and read exact Kubernetes pod log sources."""

    def __init__(
        self,
        namespace: str,
        workflow_name: str,
        *,
        core_api: Optional[Any] = None,
        custom_api: Optional[Any] = None,
        discovery_interval: float = 2.0,
    ):
        self.namespace = namespace
        self.workflow_name = workflow_name
        self.core_api = core_api or client.CoreV1Api()
        self.custom_api = custom_api or client.CustomObjectsApi()
        self.discovery_interval = discovery_interval

    def resolve(self, capability_target_id: str) -> Tuple[LogSelection, ...]:
        if capability_target_id.startswith(WORKFLOW_STEP_TARGET_PREFIX):
            node_id = capability_target_id[len(WORKFLOW_STEP_TARGET_PREFIX):]
            if not node_id:
                raise LogUnavailable("The workflow step log target is invalid.")
            selector = (
                f"workflows.argoproj.io/workflow={self.workflow_name}"
            )
            pods = tuple(
                pod for pod in self._list_pods(selector)
                if _pod_annotation(
                    pod,
                    "workflows.argoproj.io/node-id",
                ) == node_id
            )
            if not pods:
                raise LogUnavailable(
                    "No pod is available for this workflow step."
                )
            return tuple(
                selection
                for pod in pods
                for selection in self._container_selections(
                    pod,
                    selector=selector,
                    node_id=node_id,
                )
            )

        parts = capability_target_id.split(":", 2)
        if len(parts) != 3 or parts[0] != "logs" or not all(parts[1:]):
            raise LogUnavailable("The resource log target is invalid.")
        plural, name = parts[1:]
        try:
            resource = self.custom_api.get_namespaced_custom_object(
                group=CRD_GROUP,
                version=CRD_VERSION,
                namespace=self.namespace,
                plural=plural,
                name=name,
            )
        except ApiException as error:
            if error.status == 404:
                raise LogUnavailable(
                    f"The resource {plural}/{name} no longer exists."
                ) from error
            raise LogUnavailable(str(error)) from error
        selector = resource_log_selector(resource)
        pods = self._list_pods(selector)
        if not pods:
            raise LogUnavailable(
                f"No pods match the log labels for {plural}/{name}."
            )
        aggregate = LogSelection(
            kind="aggregate",
            label="All matching containers",
            selector=selector,
            pod_name=None,
            pod_uid=None,
            container=None,
            restart_count=None,
            previous=False,
        )
        exact = tuple(
            selection
            for pod in pods
            for selection in self._container_selections(
                pod,
                selector=selector,
            )
        )
        return (aggregate,) + exact

    def history(
        self,
        selection: LogSelection,
        tail_lines: int,
    ) -> Tuple[LogRecord, ...]:
        selections = (
            self._current_container_selections(selection)
            if selection.kind == "aggregate"
            else (selection,)
        )
        if not selections:
            return ()
        per_container = max(1, tail_lines // len(selections))
        records = []
        for exact in selections:
            try:
                content = self.core_api.read_namespaced_pod_log(
                    name=exact.pod_name,
                    namespace=self.namespace,
                    container=exact.container,
                    previous=exact.previous,
                    timestamps=True,
                    tail_lines=per_container,
                )
            except ApiException as error:
                if error.status in (400, 404):
                    continue
                raise
            records.extend(_parse_log_content(content, exact))
        records.sort(key=lambda item: (
            item.timestamp or "",
            item.pod_name,
            item.container,
        ))
        return tuple(records[-tail_lines:])

    def follow(
        self,
        selection: LogSelection,
        emit: Callable[[LogRecord], None],
        stop: threading.Event,
        register_response: Callable[[Any], None],
    ) -> None:
        if selection.previous:
            return
        if selection.kind != "aggregate":
            self._follow_container(
                selection,
                emit,
                stop,
                register_response,
            )
            return

        workers: Dict[Tuple[str, str, int], threading.Thread] = {}
        while not stop.is_set():
            for exact in self._current_container_selections(selection):
                self._start_follow_worker(
                    workers,
                    exact,
                    emit,
                    stop,
                    register_response,
                )
            stop.wait(self.discovery_interval)
        for worker in workers.values():
            worker.join(timeout=1)

    def _start_follow_worker(
        self,
        workers: Dict[Tuple[str, str, int], threading.Thread],
        selection: LogSelection,
        emit: Callable[[LogRecord], None],
        stop: threading.Event,
        register_response: Callable[[Any], None],
    ) -> None:
        key = (
            selection.pod_uid or "",
            selection.container or "",
            selection.restart_count or 0,
        )
        if key in workers:
            return
        worker = threading.Thread(
            target=self._follow_container,
            args=(selection, emit, stop, register_response),
            name=(
                f"log-follow-{selection.pod_name}-"
                f"{selection.container}-{selection.restart_count}"
            ),
            daemon=True,
        )
        workers[key] = worker
        worker.start()

    def _follow_container(
        self,
        selection: LogSelection,
        emit: Callable[[LogRecord], None],
        stop: threading.Event,
        register_response: Callable[[Any], None],
    ) -> None:
        response = None
        try:
            response = self.core_api.read_namespaced_pod_log(
                name=selection.pod_name,
                namespace=self.namespace,
                container=selection.container,
                previous=False,
                timestamps=True,
                tail_lines=0,
                follow=True,
                _preload_content=False,
            )
            register_response(response)
            for line in _response_lines(response):
                if stop.is_set():
                    break
                timestamp, message = _split_timestamp(line)
                emit(LogRecord(
                    timestamp=timestamp,
                    pod_name=selection.pod_name or "",
                    pod_uid=selection.pod_uid or "",
                    container=selection.container or "",
                    restart_count=selection.restart_count or 0,
                    previous=False,
                    message=message,
                ))
        except Exception as error:
            if not stop.is_set():
                emit(LogRecord(
                    timestamp=None,
                    pod_name=selection.pod_name or "",
                    pod_uid=selection.pod_uid or "",
                    container=selection.container or "",
                    restart_count=selection.restart_count or 0,
                    previous=False,
                    message=str(error) or type(error).__name__,
                    kind="error",
                ))
        finally:
            if response is not None:
                _close_response(response)

    def _current_container_selections(
        self,
        selection: LogSelection,
    ) -> Tuple[LogSelection, ...]:
        if not selection.selector:
            return ()
        pods = self._list_pods(selection.selector)
        if selection.node_id:
            pods = tuple(
                pod for pod in pods
                if _pod_annotation(
                    pod,
                    "workflows.argoproj.io/node-id",
                ) == selection.node_id
            )
        return tuple(
            exact
            for pod in pods
            for exact in self._container_selections(
                pod,
                selector=selection.selector,
                node_id=selection.node_id,
                include_previous=False,
            )
        )

    def _list_pods(self, selector: str) -> Tuple[Any, ...]:
        result = self.core_api.list_namespaced_pod(
            namespace=self.namespace,
            label_selector=selector,
        )
        return tuple(_field(result, "items", ()) or ())

    def _container_selections(
        self,
        pod: Any,
        *,
        selector: str,
        node_id: Optional[str] = None,
        include_previous: bool = True,
    ) -> Tuple[LogSelection, ...]:
        pod_name = str(_nested(pod, "metadata", "name") or "")
        pod_uid = str(_nested(pod, "metadata", "uid") or "")
        statuses = {
            str(_field(status, "name") or ""): int(
                _field(status, "restart_count", 0) or 0
            )
            for status in (
                _nested(pod, "status", "container_statuses") or ()
            )
        }
        result = []
        for container_value in (
            _nested(pod, "spec", "containers") or ()
        ):
            container_name = str(_field(container_value, "name") or "")
            if not container_name:
                continue
            restart_count = statuses.get(container_name, 0)
            label = f"{pod_name} / {container_name}"
            result.append(LogSelection(
                kind="container",
                label=label,
                selector=selector,
                pod_name=pod_name,
                pod_uid=pod_uid,
                container=container_name,
                restart_count=restart_count,
                previous=False,
                node_id=node_id,
            ))
            if include_previous and restart_count > 0:
                result.append(LogSelection(
                    kind="container",
                    label=f"{label} (previous)",
                    selector=selector,
                    pod_name=pod_name,
                    pod_uid=pod_uid,
                    container=container_name,
                    restart_count=restart_count - 1,
                    previous=True,
                    node_id=node_id,
                ))
        return tuple(result)


@dataclass
class _IssuedTarget:
    selection: LogSelection
    target: LogTarget
    expires_at: float


@dataclass
class _Session:
    id: str
    target: LogTarget
    selection: LogSelection
    state: str = "starting"
    events: Deque[LogEvent] = field(default_factory=deque)
    byte_count: int = 0
    next_sequence: int = 1
    history_truncated: bool = False
    message: Optional[str] = None
    stop_event: threading.Event = field(default_factory=threading.Event)
    condition: threading.Condition = field(default_factory=threading.Condition)
    responses: list = field(default_factory=list)
    worker: Optional[threading.Thread] = None


class LogStreamService:
    """Own server-issued targets and bounded in-memory log sessions."""

    def __init__(
        self,
        source: Any,
        *,
        max_lines: int = DEFAULT_MAX_LINES,
        max_bytes: int = DEFAULT_MAX_BYTES,
        target_ttl: float = 600,
    ):
        self.source = source
        self.max_lines = max_lines
        self.max_bytes = max_bytes
        self.target_ttl = target_ttl
        self._lock = threading.Lock()
        self._targets: Dict[str, _IssuedTarget] = {}
        self._sessions: Dict[str, _Session] = {}

    def list_targets(
        self,
        node_id: str,
        capability_target_id: str,
    ) -> LogTargetInventory:
        selections = self.source.resolve(capability_target_id)
        issued = []
        now = time.monotonic()
        with self._lock:
            self._prune_targets(now)
            for selection in selections:
                target_id = f"log-target-{secrets.token_urlsafe(18)}"
                target = LogTarget(
                    id=target_id,
                    label=selection.label,
                    kind=selection.kind,
                    pod_name=selection.pod_name,
                    pod_uid=selection.pod_uid,
                    container=selection.container,
                    restart_count=selection.restart_count,
                    previous=selection.previous,
                    supports_follow=selection.supports_follow,
                )
                self._targets[target_id] = _IssuedTarget(
                    selection=selection,
                    target=target,
                    expires_at=now + self.target_ttl,
                )
                issued.append(target)
        return LogTargetInventory(
            node_id=node_id,
            capability_target_id=capability_target_id,
            targets=tuple(issued),
        )

    def start(
        self,
        target_id: str,
        *,
        tail_lines: int = DEFAULT_TAIL_LINES,
        follow: bool = True,
        page_size: int = 200,
    ) -> LogStream:
        if not 1 <= tail_lines <= MAX_TAIL_LINES:
            raise LogUnavailable(
                f"tailLines must be between 1 and {MAX_TAIL_LINES}."
            )
        with self._lock:
            issued = self._targets.get(target_id)
            if issued is None or issued.expires_at < time.monotonic():
                raise LogTargetStale(
                    "The log target expired. Refresh the target list."
                )
        if follow and not issued.target.supports_follow:
            raise LogUnavailable("Previous container logs cannot be followed.")

        session_id = f"log-stream-{secrets.token_urlsafe(18)}"
        session = _Session(
            id=session_id,
            target=issued.target,
            selection=issued.selection,
        )
        with self._lock:
            self._sessions[session_id] = session
        try:
            history = self.source.history(issued.selection, tail_lines)
            session.history_truncated = len(history) >= tail_lines
            for record in history:
                self._append(session, record)
        except Exception:
            with self._lock:
                self._sessions.pop(session_id, None)
            raise

        session.state = "following" if follow else "ended"
        result = LogStream(
            id=session.id,
            target=session.target,
            state=session.state,
            page=self.page(session.id, limit=page_size),
        )
        if follow:
            worker = threading.Thread(
                target=self._run_follow,
                args=(session,),
                name=f"log-stream-{session.id}",
                daemon=True,
            )
            session.worker = worker
            worker.start()
        return result

    def page(
        self,
        stream_id: str,
        *,
        before: Optional[str] = None,
        after: Optional[str] = None,
        limit: int = 200,
    ) -> LogPage:
        if before and after:
            raise LogUnavailable("Use either before or after, not both.")
        session = self._session(stream_id)
        bounded_limit = max(1, min(limit, 1000))
        with session.condition:
            events = tuple(session.events)
            if before:
                sequence = _decode_cursor(before)
                matching = tuple(
                    event for event in events
                    if event.sequence < sequence
                )
                selected = matching[-bounded_limit:]
            elif after:
                sequence = _decode_cursor(after)
                matching = tuple(
                    event for event in events
                    if event.sequence > sequence
                )
                selected = matching[:bounded_limit]
            else:
                selected = events[-bounded_limit:]
            first_available = (
                events[0].sequence if events else session.next_sequence
            )
            last_available = (
                events[-1].sequence if events else session.next_sequence - 1
            )
            first_selected = (
                selected[0].sequence if selected else first_available
            )
            last_selected = (
                selected[-1].sequence if selected else last_available
            )
            return LogPage(
                events=selected,
                before_cursor=(
                    _encode_cursor(first_selected) if selected else None
                ),
                after_cursor=(
                    _encode_cursor(last_selected) if selected else None
                ),
                at_available_start=first_selected <= first_available,
                at_buffer_end=last_selected >= last_available,
                history_truncated=session.history_truncated,
                state=session.state,
            )

    def wait_for_events(
        self,
        stream_id: str,
        *,
        after_sequence: int,
        timeout: float = 15,
    ) -> Tuple[LogEvent, ...]:
        session = self._session(stream_id)
        with session.condition:
            events = tuple(
                event for event in session.events
                if event.sequence > after_sequence
            )
            if not events and session.state in ("starting", "following"):
                session.condition.wait(timeout)
                events = tuple(
                    event for event in session.events
                    if event.sequence > after_sequence
                )
            return events

    def status(self, stream_id: str) -> LogStreamStatus:
        session = self._session(stream_id)
        with session.condition:
            return LogStreamStatus(
                id=session.id,
                state=session.state,
                message=session.message,
            )

    def stop(self, stream_id: str) -> LogStreamStatus:
        session = self._session(stream_id)
        with session.condition:
            session.state = "stopped"
            session.stop_event.set()
            responses = tuple(session.responses)
            session.condition.notify_all()
        for response in responses:
            _close_response(response)
        if session.worker and session.worker is not threading.current_thread():
            session.worker.join(timeout=2)
        return self.status(stream_id)

    def shutdown(self) -> None:
        with self._lock:
            stream_ids = tuple(self._sessions)
        for stream_id in stream_ids:
            try:
                self.stop(stream_id)
            except LogUnavailable:
                pass

    def _run_follow(self, session: _Session) -> None:
        try:
            self.source.follow(
                session.selection,
                lambda record: self._append(session, record),
                session.stop_event,
                lambda response: self._register_response(session, response),
            )
            with session.condition:
                if session.state != "stopped":
                    session.state = "ended"
                session.condition.notify_all()
        except Exception as error:
            with session.condition:
                if session.state != "stopped":
                    session.state = "error"
                    session.message = str(error) or type(error).__name__
                session.condition.notify_all()

    def _append(self, session: _Session, record: LogRecord) -> None:
        size = len(record.message.encode("utf-8", errors="replace"))
        with session.condition:
            event = LogEvent(
                sequence=session.next_sequence,
                received_at=datetime.now(timezone.utc).isoformat(),
                timestamp=record.timestamp,
                pod_name=record.pod_name,
                pod_uid=record.pod_uid,
                container=record.container,
                restart_count=record.restart_count,
                previous=record.previous,
                message=record.message,
                kind=record.kind,
            )
            session.next_sequence += 1
            while session.events and (
                len(session.events) >= self.max_lines
                or session.byte_count + size > self.max_bytes
            ):
                removed = session.events.popleft()
                session.byte_count -= len(
                    removed.message.encode("utf-8", errors="replace")
                )
                session.history_truncated = True
            session.events.append(event)
            session.byte_count += size
            session.condition.notify_all()

    def _register_response(self, session: _Session, response: Any) -> None:
        with session.condition:
            session.responses.append(response)
            if session.stop_event.is_set():
                _close_response(response)

    def _session(self, stream_id: str) -> _Session:
        with self._lock:
            session = self._sessions.get(stream_id)
        if session is None:
            raise LogUnavailable("The log stream is unavailable.")
        return session

    def _prune_targets(self, now: float) -> None:
        expired = [
            target_id
            for target_id, issued in self._targets.items()
            if issued.expires_at < now
        ]
        for target_id in expired:
            self._targets.pop(target_id, None)


def _field(value: Any, name: str, default: Any = None) -> Any:
    if isinstance(value, Mapping):
        if name in value:
            return value[name]
        parts = name.split("_")
        camel = parts[0] + "".join(part.title() for part in parts[1:])
        return value.get(camel, default)
    return getattr(value, name, default)


def _nested(value: Any, *names: str) -> Any:
    current = value
    for name in names:
        current = _field(current, name)
        if current is None:
            return None
    return current


def _pod_annotation(pod: Any, name: str) -> Optional[str]:
    annotations = _nested(pod, "metadata", "annotations") or {}
    value = annotations.get(name) if isinstance(annotations, Mapping) else None
    return str(value) if value is not None else None


def _parse_log_content(
    content: Any,
    selection: LogSelection,
) -> Tuple[LogRecord, ...]:
    content = _normalize_log_content(content)
    result = []
    for line in str(content or "").splitlines():
        timestamp, message = _split_timestamp(line)
        result.append(LogRecord(
            timestamp=timestamp,
            pod_name=selection.pod_name or "",
            pod_uid=selection.pod_uid or "",
            container=selection.container or "",
            restart_count=selection.restart_count or 0,
            previous=selection.previous,
            message=message,
        ))
    return tuple(result)


def _normalize_log_content(content: Any) -> str:
    if isinstance(content, (bytes, bytearray, memoryview)):
        return bytes(content).decode("utf-8", errors="replace")
    text = str(content or "")
    # Some Kubernetes Python client combinations deserialize the text/plain
    # response as the repr of a bytes object. Parse that literal safely.
    if (
        len(text) >= 3
        and text[0] == "b"
        and text[1] in ("'", '"')
        and text[-1] == text[1]
    ):
        try:
            value = ast.literal_eval(text)
            if isinstance(value, bytes):
                return value.decode("utf-8", errors="replace")
        except (SyntaxError, ValueError):
            pass
    return text


def _split_timestamp(line: str) -> Tuple[Optional[str], str]:
    timestamp, separator, message = line.partition(" ")
    if separator and "T" in timestamp and (
        timestamp.endswith("Z") or "+" in timestamp
    ):
        return timestamp, message
    return None, line


def _response_lines(response: Any) -> Iterable[str]:
    chunks = (
        response.stream(amt=4096, decode_content=True)
        if hasattr(response, "stream")
        else iter(response)
    )
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    pending = ""
    for chunk in chunks:
        if isinstance(chunk, str):
            text = chunk
        else:
            text = decoder.decode(chunk)
        pending += text
        while "\n" in pending:
            line, pending = pending.split("\n", 1)
            yield line.rstrip("\r")
    pending += decoder.decode(b"", final=True)
    if pending:
        yield pending.rstrip("\r")


def _close_response(response: Any) -> None:
    try:
        response.close()
    except Exception:
        pass
    release = getattr(response, "release_conn", None)
    if callable(release):
        try:
            release()
        except Exception:
            pass


def _encode_cursor(sequence: int) -> str:
    raw = f"log:{sequence}".encode("ascii")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def _decode_cursor(cursor: str) -> int:
    try:
        padding = "=" * (-len(cursor) % 4)
        decoded = base64.urlsafe_b64decode(cursor + padding).decode("ascii")
        prefix, value = decoded.split(":", 1)
        if prefix != "log":
            raise ValueError
        return max(0, int(value))
    except ValueError as error:
        raise LogUnavailable("The log cursor is invalid.") from error
