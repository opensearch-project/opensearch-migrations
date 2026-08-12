import asyncio
from datetime import datetime, timezone
from threading import Lock

import pytest

from console_link.workflow.application.models import ManageSnapshot
from console_link.workflow.application.observations import (
    EventHistory,
    ObservationCoordinator,
)


def _snapshot(revision):
    return ManageSnapshot(
        format_version=1,
        revision=revision,
        observed_at=datetime.now(timezone.utc).isoformat(),
        namespace="ma",
        workflow_name="migration",
        workflow=None,
        root_ids=(),
        nodes={},
    )


class _StateService:
    def __init__(self, snapshot):
        self._result = snapshot
        self.calls = 0
        self._lock = Lock()

    def set_result(self, result):
        with self._lock:
            self._result = result

    def observe(self):
        with self._lock:
            result = self._result
        self.calls += 1
        if isinstance(result, Exception):
            raise result
        return result


async def _wait_until(predicate, timeout=1):
    async with asyncio.timeout(timeout):
        while not predicate():
            await asyncio.sleep(0.01)


@pytest.mark.asyncio
async def test_coordinator_polls_once_for_all_consumers_and_invalidates_on_change():
    service = _StateService(_snapshot("revision-1"))
    coordinator = ObservationCoordinator(
        service,
        refresh_interval=0.02,
        heartbeat_interval=10,
    )

    await coordinator.start()
    try:
        first = await coordinator.get_observation(timeout=1)
        second = await coordinator.get_observation(timeout=1)
        assert first.snapshot is second.snapshot

        service.set_result(_snapshot("revision-2"))
        await _wait_until(
            lambda: coordinator.latest_revision == "revision-2",
        )
        events = coordinator.events.events_after(0)

        assert [event.event for event in events.events] == [
            "state-invalidated",
        ]
        assert service.calls < 20
    finally:
        await coordinator.stop()

    assert coordinator.running is False


@pytest.mark.asyncio
async def test_coordinator_keeps_last_success_and_marks_it_stale_after_failure():
    service = _StateService(_snapshot("revision-1"))
    coordinator = ObservationCoordinator(
        service,
        refresh_interval=0.02,
        retry_max_interval=0.04,
        heartbeat_interval=10,
    )

    await coordinator.start()
    try:
        await coordinator.get_observation(timeout=1)
        service.set_result(RuntimeError("cluster unavailable"))
        await _wait_until(
            lambda: coordinator.current_observation.stale,
        )

        observation = await coordinator.get_observation(timeout=1)
        assert observation.snapshot.revision == "revision-1"
        assert observation.stale is True
        assert observation.refresh_error is not None
        assert observation.refresh_error.source == "observation"
        assert observation.refresh_error.retryable is True
    finally:
        await coordinator.stop()


@pytest.mark.asyncio
async def test_coordinator_reports_initial_failure_without_hanging():
    service = _StateService(RuntimeError("cluster unavailable"))
    coordinator = ObservationCoordinator(
        service,
        refresh_interval=1,
        retry_max_interval=1,
        heartbeat_interval=10,
    )

    await coordinator.start()
    try:
        with pytest.raises(RuntimeError, match="cluster unavailable"):
            await coordinator.get_observation(timeout=1)
    finally:
        await coordinator.stop()


def test_event_history_is_monotonic_bounded_and_detects_a_gap():
    history = EventHistory(max_events=2)

    first = history.publish("heartbeat", {})
    second = history.publish("state-invalidated", {"revision": "two"})
    third = history.publish("heartbeat", {})
    replay = history.events_after(first.id)
    gap = history.events_after(0)

    assert [first.id, second.id, third.id] == [1, 2, 3]
    assert [event.id for event in replay.events] == [2, 3]
    assert replay.history_gap is False
    assert gap.history_gap is True


@pytest.mark.asyncio
async def test_event_stream_converts_history_gap_to_state_invalidation():
    coordinator = ObservationCoordinator(
        _StateService(_snapshot("revision-1")),
        refresh_interval=10,
        heartbeat_interval=10,
        event_history_size=2,
    )

    await coordinator.start()
    try:
        await coordinator.get_observation(timeout=1)
        coordinator.events.publish("heartbeat", {"sequence": 1})
        coordinator.events.publish("heartbeat", {"sequence": 2})
        coordinator.events.publish("heartbeat", {"sequence": 3})

        stream = coordinator.stream_events(last_event_id=0)
        event = await anext(stream)

        assert event.id == 3
        assert event.event == "state-invalidated"
        assert event.data == {"reason": "history-gap"}
        await stream.aclose()
    finally:
        await coordinator.stop()
