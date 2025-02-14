import logging
from console_link.models.snapshot import Snapshot
from console_link.models.command_result import CommandResult

logger = logging.getLogger(__name__)


def create(snapshot: Snapshot, *args, **kwargs) -> CommandResult:
    logger.info(f"Creating snapshot with {args=} and {kwargs=}")
    try:
        return snapshot.create(*args, **kwargs)
    except Exception as e:
        logger.error(f"Failure running create snapshot: {e}")
        return CommandResult(success=False, value=f"Failure running create snapshot: {e}")


def status(snapshot: Snapshot, *args, **kwargs) -> CommandResult:
    logger.info("Getting snapshot status")
    return snapshot.status(*args, **kwargs)


def delete(snapshot: Snapshot, *args, **kwargs) -> CommandResult:
    logger.info(f"Deleting snapshot with {args=} and {kwargs=}")
    try:
        return CommandResult(success=True, value=snapshot.delete(*args, **kwargs))
    except Exception as e:
        logger.error(f"Failure running delete snapshot: {e}")
        return CommandResult(success=False, value=f"Failure running delete snapshot: {e}")


def delete_snapshot_repo(snapshot: Snapshot, *args, **kwargs) -> CommandResult:
    logger.info(f"Deleting snapshot repo with {args=} and {kwargs=}")
    try:
        return CommandResult(success=True, value=snapshot.delete_snapshot_repo(*args, **kwargs))
    except Exception as e:
        logger.error(f"Failure running delete snapshot repo: {e}")
        return CommandResult(success=False, value=f"Failure running delete snapshot repo: {e}")
