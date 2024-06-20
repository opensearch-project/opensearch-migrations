from typing import Tuple

from console_link.models.metadata import Metadata
from console_link.models.utils import ExitCode


def migrate(metadata: Metadata) -> Tuple[ExitCode, str]:
    return ExitCode.SUCCESS, metadata.migrate()
