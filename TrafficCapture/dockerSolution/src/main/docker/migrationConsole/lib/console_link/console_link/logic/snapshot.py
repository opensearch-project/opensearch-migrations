import logging
from typing import Tuple
from console_link.models.snapshot import Snapshot, SnapshotStatus

logger = logging.getLogger(__name__)


def create(snapshot: Snapshot, *args, **kwargs) -> Tuple[SnapshotStatus, str]:
    logger.info(f"Creating snapshot with {args=} and {kwargs=}")
    try:
        result = snapshot.create(*args, **kwargs)
    except Exception as e:
        logger.error(f"Failure running create snapshot: {e}")
        return SnapshotStatus.FAILED, f"Failure running create snapshot: {e}"

    if not result.success:
        return SnapshotStatus.FAILED, "Snapshot creation failed." + "\n" + result.value

    return status(snapshot, *args, **kwargs)


def status(snapshot: Snapshot, *args, **kwargs) -> Tuple[SnapshotStatus, str]:
    logger.info("Getting snapshot status")
    return snapshot.status(*args, **kwargs)
