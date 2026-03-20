"""
Test that backfill test configuration sets sufficient monitor-retry-limit.

The default monitor-retry-limit of 33 is insufficient for backfill tests
on resource-constrained environments. This test ensures the fix is not
accidentally reverted.
"""
from pathlib import Path


def test_backfill_test_sets_monitor_retry_limit():
    """Backfill tests must set monitor-retry-limit to avoid timeout failures."""
    backfill_path = Path(__file__).parent / "backfill_tests.py"
    content = backfill_path.read_text()
    assert "monitor-retry-limit" in content, (
        "backfill_tests.py must set monitor-retry-limit parameter. "
        "The default of 33 retries (33 min) is insufficient for backfill "
        "migrations on resource-constrained environments."
    )
