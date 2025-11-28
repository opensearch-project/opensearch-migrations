import logging
from pathlib import Path

logger = logging.getLogger(__name__)
VERSION_PREFIX = "Migration Assistant"


def get_version_str() -> str:
    version_file = Path.home() / "VERSION"
    version = "unknown"
    if version_file.exists():
        try:
            version_line = next(
                (line.strip() for line in version_file.read_text().splitlines() if line.strip()), None
            )
            if version_line:
                version = version_line
            else:
                logger.info("VERSION file is empty at ~/VERSION")
        except Exception as e:
            logger.info(f"Failed to read ~/VERSION file: {e}")
    else:
        logger.info("VERSION file not found at ~/VERSION")

    return f"{VERSION_PREFIX} {version}"
