"""Bounded process-local tracking for consequential workflow operations."""

from __future__ import annotations

import asyncio
from concurrent.futures import Executor, ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timezone
from threading import RLock
from typing import Any, Callable, Dict, Mapping, Optional, Sequence, Tuple
from uuid import uuid4


ACTIVE_OPERATION_STATUSES = {"queued", "running", "waiting"}
TERMINAL_OPERATION_STATUSES = {"succeeded", "failed"}


@dataclass(frozen=True)
class Operation:
    id: str
    kind: str
    label: str
    status: str
    target_ids: Tuple[str, ...]
    created_at: str
    updated_at: str
    message: str
    detail: Optional[str] = None
    result: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class OperationWorkResult:
    waiting: bool
    message: str
    result: Mapping[str, Any] = field(default_factory=dict)
    detail: Optional[str] = None


@dataclass(frozen=True)
class OperationEvent:
    id: int
    operation_id: str
    operation: Operation


class OperationManager:
    """Run actions in worker threads and retain bounded browser-visible state."""

    def __init__(
        self,
        *,
        executor: Optional[Executor] = None,
        clock: Optional[Callable[[], datetime]] = None,
        history_limit: int = 30,
        event_limit: int = 200,
    ):
        self._executor = executor or ThreadPoolExecutor(
            max_workers=4,
            thread_name_prefix="workflow-manage-operation",
        )
        self._owns_executor = executor is None
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._history_limit = max(1, history_limit)
        self._event_limit = max(self._history_limit, event_limit)
        self._lock = RLock()
        self._operations: Dict[str, Operation] = {}
        self._order: list[str] = []
        self._events: list[OperationEvent] = []
        self._next_event_id = 1

    def start(
        self,
        *,
        kind: str,
        label: str,
        target_ids: Sequence[str],
        worker: Callable[[], OperationWorkResult],
    ) -> Operation:
        operation_id = uuid4().hex
        timestamp = self._timestamp()
        operation = Operation(
            id=operation_id,
            kind=kind,
            label=label,
            status="queued",
            target_ids=tuple(target_ids),
            created_at=timestamp,
            updated_at=timestamp,
            message="Queued",
        )
        with self._lock:
            self._operations[operation_id] = operation
            self._order.insert(0, operation_id)
            self._emit(operation)
            self._prune()
        self._executor.submit(
            lambda: self._run(operation_id, worker)
        )
        return self.get(operation_id)

    def get(self, operation_id: str) -> Operation:
        with self._lock:
            return self._operations[operation_id]

    def list(self) -> Tuple[Operation, ...]:
        with self._lock:
            return tuple(
                self._operations[operation_id]
                for operation_id in self._order
                if operation_id in self._operations
            )

    def events_after(self, event_id: int) -> Tuple[OperationEvent, ...]:
        with self._lock:
            return tuple(
                event for event in self._events
                if event.id > event_id
            )

    async def stream_events(
        self,
        event_id: int,
        *,
        heartbeat_seconds: float = 15.0,
    ):
        cursor = max(0, event_id)
        last_heartbeat = asyncio.get_running_loop().time()
        while True:
            events = self.events_after(cursor)
            if events:
                for event in events:
                    cursor = event.id
                    yield event
                last_heartbeat = asyncio.get_running_loop().time()
                continue
            now = asyncio.get_running_loop().time()
            if now - last_heartbeat >= heartbeat_seconds:
                yield None
                last_heartbeat = now
            await asyncio.sleep(0.2)

    def reconcile_submit(
        self,
        *,
        workflow_name: Optional[str],
        snapshot_revision: str,
        workflow_phase: Optional[str],
    ) -> Tuple[str, ...]:
        completed = []
        for operation in self.list():
            if (
                operation.kind not in {"submit", "reset"}
                or operation.status != "waiting"
                or not operation.result.get("workflowName")
            ):
                continue
            expected_name = str(operation.result.get("workflowName") or "")
            baseline = str(operation.result.get("baselineRevision") or "")
            if (
                not workflow_name
                or workflow_name != expected_name
                or snapshot_revision == baseline
            ):
                continue
            if str(workflow_phase or "").lower() in {"failed", "error"}:
                self.fail(
                    operation.id,
                    (
                        f"Submitted workflow was observed in "
                        f"{workflow_phase} phase"
                    ),
                )
            else:
                self.succeed(
                    operation.id,
                    "Submitted workflow was observed in cluster state",
                )
            completed.append(operation.id)
        return tuple(completed)

    def reconcile_approvals(
        self,
        *,
        active_target_ids: Sequence[str],
        snapshot_revision: str,
    ) -> Tuple[str, ...]:
        active = set(active_target_ids)
        completed = []
        for operation in self.list():
            if (
                operation.kind != "approve"
                or operation.status != "waiting"
            ):
                continue
            target_ids = {
                str(target_id)
                for target_id in (
                    operation.result.get("approvalTargetIds")
                    or [operation.result.get("approvalTargetId")]
                )
                if target_id
            }
            baseline = str(operation.result.get("baselineRevision") or "")
            if snapshot_revision == baseline or target_ids & active:
                continue
            self.succeed(
                operation.id,
                "Approval effect was observed in cluster state",
            )
            completed.append(operation.id)
        return tuple(completed)

    def succeed(self, operation_id: str, message: str) -> Operation:
        return self._update(
            operation_id,
            status="succeeded",
            message=message,
        )

    def fail(
        self,
        operation_id: str,
        detail: str,
        *,
        message: str = "Operation failed",
    ) -> Operation:
        return self._update(
            operation_id,
            status="failed",
            message=message,
            detail=detail,
        )

    def shutdown(self) -> None:
        if self._owns_executor:
            self._executor.shutdown(wait=False, cancel_futures=False)

    def _run(
        self,
        operation_id: str,
        worker: Callable[[], OperationWorkResult],
    ) -> None:
        self._update(
            operation_id,
            status="running",
            message="In progress",
        )
        try:
            result = worker()
        except Exception as error:
            self.fail(
                operation_id,
                str(error) or type(error).__name__,
            )
            return
        self._update(
            operation_id,
            status="waiting" if result.waiting else "succeeded",
            message=result.message,
            detail=result.detail,
            result=dict(result.result),
        )

    def _update(
        self,
        operation_id: str,
        *,
        status: str,
        message: str,
        detail: Optional[str] = None,
        result: Optional[Mapping[str, Any]] = None,
    ) -> Operation:
        with self._lock:
            current = self._operations[operation_id]
            operation = Operation(
                id=current.id,
                kind=current.kind,
                label=current.label,
                status=status,
                target_ids=current.target_ids,
                created_at=current.created_at,
                updated_at=self._timestamp(),
                message=message,
                detail=detail,
                result=current.result if result is None else dict(result),
            )
            self._operations[operation_id] = operation
            self._emit(operation)
            self._prune()
            return operation

    def _emit(self, operation: Operation) -> None:
        event = OperationEvent(
            id=self._next_event_id,
            operation_id=operation.id,
            operation=operation,
        )
        self._next_event_id += 1
        self._events.append(event)
        if len(self._events) > self._event_limit:
            self._events = self._events[-self._event_limit:]

    def _prune(self) -> None:
        terminal = [
            operation_id
            for operation_id in self._order
            if self._operations[operation_id].status
            in TERMINAL_OPERATION_STATUSES
        ]
        for operation_id in terminal[self._history_limit:]:
            self._operations.pop(operation_id, None)
            self._order.remove(operation_id)

    def _timestamp(self) -> str:
        value = self._clock()
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc).isoformat().replace(
            "+00:00",
            "Z",
        )
