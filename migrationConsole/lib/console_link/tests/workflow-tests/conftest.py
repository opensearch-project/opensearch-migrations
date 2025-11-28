"""Pytest configuration for workflow tests."""

import os
import warnings
from pathlib import Path
import pytest


@pytest.fixture(autouse=True)
def set_config_processor_dir(monkeypatch):
    """Set CONFIG_PROCESSOR_DIR to test resources/scripts for all tests."""
    # Get the path to test resources/scripts directory which contains mock scripts
    test_dir = Path(__file__).parent
    scripts_dir = test_dir / "resources" / "scripts"

    # Set CONFIG_PROCESSOR_DIR environment variable
    monkeypatch.setenv("CONFIG_PROCESSOR_DIR", str(scripts_dir))

    # Set required ETCD environment variables for tests
    monkeypatch.setenv("ETCD_SERVICE_HOST", "localhost")
    monkeypatch.setenv("ETCD_SERVICE_PORT_CLIENT", "2379")
