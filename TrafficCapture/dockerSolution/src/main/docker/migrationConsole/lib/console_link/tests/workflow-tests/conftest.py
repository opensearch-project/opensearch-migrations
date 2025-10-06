"""Pytest configuration for workflow tests."""

import warnings

# Suppress deprecation warnings from external libraries
warnings.filterwarnings("ignore", category=DeprecationWarning, module="kubernetes.client.rest")
warnings.filterwarnings("ignore", category=DeprecationWarning, module="testcontainers")

# Configure pytest to suppress specific warnings during test collection and execution


def pytest_configure(config):
    """Configure pytest with custom warning filters."""
    config.addinivalue_line(
        "filterwarnings",
        "ignore::DeprecationWarning:kubernetes.client.rest"
    )
    config.addinivalue_line(
        "filterwarnings",
        "ignore::DeprecationWarning:testcontainers"
    )
