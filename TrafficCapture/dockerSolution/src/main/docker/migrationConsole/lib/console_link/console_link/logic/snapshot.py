import logging
from typing import Tuple
from console_link.models.snapshot import Snapshot, SnapshotStatus

logger = logging.getLogger(__name__)

def create(snapshot: Snapshot, *args, **kwargs) -> Tuple[SnapshotStatus, str]:
    logger.info(f"Creating snapshot with {args=} and {kwargs=}")
    try:
        result = snapshot.create(*args, **kwargs)
    except Exception as e:
        logger.error(f"Failed to create snapshot: {e}")
        return SnapshotStatus.FAILED, f"Failure when creating snapshot: {type(e).__name__} {e}"
    
    if result.success:
        return SnapshotStatus.COMPLETED, "Snapshot created successfully." + "\n" + result.value
    return SnapshotStatus.FAILED, "Snapshot creation failed." + "\n" + result.value

def status(snapshot: Snapshot, *args, **kwargs) -> Tuple[SnapshotStatus, str]:
    logger.info("Getting snapshot status")
    try:
        result = snapshot.status(*args, **kwargs)
    except Exception as e:
        logger.error(f"Failed to get status of snapshot: {e}")
        return SnapshotStatus.FAILED, f"Failure when getting status of snapshot: {type(e).__name__} {e}"
    if result.success:
        return SnapshotStatus.COMPLETED, result.value
    return SnapshotStatus.FAILED, "Snapshot status retrieval failed." + "\n" + result.value
