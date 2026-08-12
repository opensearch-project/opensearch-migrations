"""Process-local workflow observation and invalidation coordination."""

import asyncio
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, AsyncIterator, Deque, Dict, Optional, Tuple

from .models import ManageProblem, ManageSnapshot


@dataclass(frozen=True)
class Observation:
    snapshot: ManageSnapshot
    stale: bool = False
    refresh_error: Optional[ManageProblem] = None


@dataclass(frozen=True)
class ObservationEvent:
    id: int
    event: str
    data: Dict[str, Any]


@dataclass(frozen=True)
class EventBatch:
    events: Tuple[ObservationEvent, ...]
    history_gap: bool = False


class EventHistory:
    """Bounded in-process event history with monotonic process-local IDs."""

    def __init__(self, max_events: int = 256):
        if max_events < 1:
            raise ValueError("max_events must be positive")
        self._events: Deque[ObservationEvent] = deque(maxlen=max_events)
        self._next_id = 1
        self._changed = asyncio.Event()

    @property
    def current_id(self) -> int:
        return self._next_id - 1

    def publish(self, event: str, data: Dict[str, Any]) -> ObservationEvent:
        item = ObservationEvent(
            id=self._next_id,
            event=event,
            data=dict(data),
        )
        self._next_id += 1
        self._events.append(item)
        self._changed.set()
        return item

    def events_after(self, last_event_id: int) -> EventBatch:
        if not self._events:
            return EventBatch(())
        oldest_id = self._events[0].id
        gap = last_event_id < oldest_id - 1
        events = tuple(
            event for event in self._events
            if event.id > last_event_id
        )
        return EventBatch(events=events, history_gap=gap)

    async def wait_after(
        self,
        last_event_id: int,
        timeout: Optional[float] = None,
    ) -> EventBatch:
        while True:
            batch = self.events_after(last_event_id)
            if batch.events or batch.history_gap:
                return batch
            self._changed.clear()
            batch = self.events_after(last_event_id)
            if batch.events or batch.history_gap:
                return batch
            await asyncio.wait_for(self._changed.wait(), timeout=timeout)


class ObservationCoordinator:
    """Runs one blocking manage-state observation loop for the ASGI process."""

    def __init__(
        self,
        state_service: Any,
        *,
        refresh_interval: float = 3.0,
        retry_max_interval: float = 30.0,
        heartbeat_interval: float = 15.0,
        event_history_size: int = 256,
    ):
        if refresh_interval <= 0:
            raise ValueError("refresh_interval must be positive")
        if retry_max_interval <= 0:
            raise ValueError("retry_max_interval must be positive")
        if heartbeat_interval <= 0:
            raise ValueError("heartbeat_interval must be positive")
        self._state_service = state_service
        self._refresh_interval = refresh_interval
        self._retry_max_interval = retry_max_interval
        self._heartbeat_interval = heartbeat_interval
        self.events = EventHistory(event_history_size)
        self._executor: Optional[ThreadPoolExecutor] = None
        self._poll_task: Optional[asyncio.Task] = None
        self._heartbeat_task: Optional[asyncio.Task] = None
        self._initial_complete = asyncio.Event()
        self._initial_error: Optional[Exception] = None
        self._observation: Optional[Observation] = None

    @property
    def running(self) -> bool:
        return self._poll_task is not None and not self._poll_task.done()

    @property
    def current_observation(self) -> Optional[Observation]:
        return self._observation

    @property
    def latest_revision(self) -> Optional[str]:
        if self._observation is None:
            return None
        return self._observation.snapshot.revision

    async def start(self) -> None:
        if self.running:
            return
        self._executor = ThreadPoolExecutor(
            max_workers=1,
            thread_name_prefix="manage-observation",
        )
        self._poll_task = asyncio.create_task(
            self._poll_loop(),
            name="manage-observation-loop",
        )
        self._heartbeat_task = asyncio.create_task(
            self._heartbeat_loop(),
            name="manage-heartbeat-loop",
        )

    async def stop(self) -> None:
        tasks = [
            task for task in (self._poll_task, self._heartbeat_task)
            if task is not None
        ]
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        self._poll_task = None
        self._heartbeat_task = None
        if self._executor is not None:
            self._executor.shutdown(wait=False, cancel_futures=True)
            self._executor = None

    async def get_observation(
        self,
        timeout: Optional[float] = 15.0,
    ) -> Observation:
        if self._observation is not None:
            return self._observation
        await asyncio.wait_for(self._initial_complete.wait(), timeout=timeout)
        if self._observation is not None:
            return self._observation
        if self._initial_error is not None:
            raise self._initial_error
        raise RuntimeError("Workflow observation did not produce a snapshot")

    async def stream_events(
        self,
        last_event_id: int = 0,
    ) -> AsyncIterator[ObservationEvent]:
        cursor = max(0, last_event_id)
        while self.running:
            batch = await self.events.wait_after(cursor)
            if batch.history_gap:
                cursor = self.events.current_id
                yield ObservationEvent(
                    id=cursor,
                    event="state-invalidated",
                    data={"reason": "history-gap"},
                )
                continue
            for event in batch.events:
                cursor = event.id
                yield event

    async def _poll_loop(self) -> None:
        failures = 0
        while True:
            try:
                await self._refresh()
                failures = 0
                delay = self._refresh_interval
            except asyncio.CancelledError:
                raise
            except Exception as error:
                failures += 1
                self._record_failure(error)
                delay = min(
                    self._refresh_interval * (2 ** (failures - 1)),
                    self._retry_max_interval,
                )
            await asyncio.sleep(delay)

    async def _refresh(self) -> None:
        loop = asyncio.get_running_loop()
        if self._executor is None:
            raise RuntimeError("Observation coordinator is not started")
        snapshot = await loop.run_in_executor(
            self._executor,
            self._state_service.observe,
        )
        if not isinstance(snapshot, ManageSnapshot):
            raise TypeError("ManageStateService.observe() returned an invalid snapshot")

        previous = self._observation
        self._observation = Observation(snapshot=snapshot)
        self._initial_error = None
        self._initial_complete.set()
        changed = (
            previous is not None
            and (
                previous.snapshot.revision != snapshot.revision
                or previous.stale
            )
        )
        if changed:
            self.events.publish(
                "state-invalidated",
                {"revision": snapshot.revision},
            )

    def _record_failure(self, error: Exception) -> None:
        self._initial_error = error
        self._initial_complete.set()
        problem = ManageProblem(
            source="observation",
            message=str(error),
            retryable=True,
        )
        previous = self._observation
        if previous is not None:
            self._observation = Observation(
                snapshot=previous.snapshot,
                stale=True,
                refresh_error=problem,
            )
            if not previous.stale or previous.refresh_error != problem:
                self.events.publish(
                    "state-invalidated",
                    {
                        "revision": previous.snapshot.revision,
                        "stale": True,
                    },
                )

    async def _heartbeat_loop(self) -> None:
        while True:
            await asyncio.sleep(self._heartbeat_interval)
            self.events.publish(
                "heartbeat",
                {
                    "sentAt": datetime.now(timezone.utc)
                    .isoformat()
                    .replace("+00:00", "Z"),
                },
            )
