from typing import Tuple

from console_link.middleware.error_handler import handle_errors
from console_link.models.metadata import Metadata
from console_link.models.utils import ExitCode
import logging

logger = logging.getLogger(__name__)


@handle_errors(service_type="metadata", on_success=lambda v: (ExitCode.SUCCESS, v))
def migrate(metadata: Metadata, extra_args) -> Tuple[ExitCode, str]:
    logger.info("Migrating metadata")
    return metadata.migrate(extra_args=extra_args)


@handle_errors(service_type="metadata", on_success=lambda v: (ExitCode.SUCCESS, v))
def evaluate(metadata: Metadata, extra_args) -> Tuple[ExitCode, str]:
    logger.info("Migrating metadata")
    return metadata.evaluate(extra_args=extra_args)
