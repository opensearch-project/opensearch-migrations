import logging
import sys
import pytest


@pytest.fixture(autouse=True)
def reset_logging():
    """Reset logging to a clean state before each test."""
    root_logger = logging.getLogger()
    # Clear all handlers
    root_logger.handlers.clear()
    # Add a fresh stderr handler
    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(logging.Formatter('%(levelname)s - %(name)s - %(message)s'))
    root_logger.addHandler(handler)
    root_logger.setLevel(logging.WARNING)
    yield
