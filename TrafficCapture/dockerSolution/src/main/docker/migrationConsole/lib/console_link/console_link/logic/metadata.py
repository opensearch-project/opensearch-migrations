from typing import Tuple

from console_link.models.metadata import Metadata
from console_link.models.utils import ExitCode
import logging

logger = logging.getLogger(__name__)


def migrate(metadata: Metadata) -> Tuple[ExitCode, str]:
    logger.info("Migrating metadata")
    try:
        result = metadata.migrate()
    except Exception as e:
        logger.error(f"Failed to migrate metadata: {e}")
        return ExitCode.FAILURE, f"Failure when migrating metadata: {e}"
    if result.success:
        return ExitCode.SUCCESS, result.value
    return ExitCode.FAILURE, result.value
