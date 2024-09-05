from typing import Tuple

from console_link.middleware.error_handler import handle_errors
from console_link.models.metadata import Metadata
from console_link.models.utils import ExitCode, generate_log_file_path
import logging

logger = logging.getLogger(__name__)


@handle_errors(service_type="metadata", on_success=lambda v: (ExitCode.SUCCESS, v))
def migrate(metadata: Metadata, detached: bool, extra_args) -> Tuple[ExitCode, str]:
    logger.info("Migrating metadata")
    if detached:
        log_file = generate_log_file_path("metadata_migration")
        logger.info(f"Running in detached mode, writing logs to {log_file}")
    return metadata.migrate(detached_log=log_file if detached else None, extra_args=extra_args)
