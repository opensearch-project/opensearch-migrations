import logging
from typing import Tuple

from console_link.models.metadata import Metadata
from console_link.models.utils import ExitCode, generate_log_file_path

logger = logging.getLogger(__name__)


def migrate(metadata: Metadata, detached: bool) -> Tuple[ExitCode, str]:
    logger.info("Migrating metadata")
    if detached:
        log_file = generate_log_file_path("metadata_migration")
        logger.info(f"Running in detached mode, writing logs to {log_file}")
    try:
        result = metadata.migrate(detached_log=log_file if detached else None)
    except Exception as e:
        logger.error(f"Failed to migrate metadata: {e}")
        return ExitCode.FAILURE, f"Failure when migrating metadata: {e}"
    if result.success:
        return ExitCode.SUCCESS, result.value
    return ExitCode.FAILURE, result.value
