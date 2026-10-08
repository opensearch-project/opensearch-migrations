from console_link.workflow.resource_tree import _format_backfill_status
from console_link.workflow.tree_utils import _format_snapshot_migration_backfill_status

WITH_COUNTS = {
    'phase': 'Running',
    'updatedAt': '2026-10-07T20:00:00Z',
    'summary': {'percentageCompleted': 50.0, 'shardsTotal': 6, 'shardsMigrated': 3,
                'shardsInProgress': 1, 'shardsWaiting': 2,
                'docsSucceeded': 1_234_567, 'docsFailed': 6_300},
}

# Runs that predate the counts (or before the first completed work item): the monitor writes null.
WITHOUT_COUNTS = {
    'phase': 'Running',
    'summary': {'percentageCompleted': 5.0, 'shardsTotal': 6, 'shardsMigrated': 0,
                'docsSucceeded': None, 'docsFailed': None},
}


def test_step_view_shows_exact_doc_counts():
    rendered = _format_snapshot_migration_backfill_status(WITH_COUNTS)
    assert "docs succeeded 1,234,567" in rendered
    assert "docs failed 6,300" in rendered


def test_step_view_omits_doc_counts_when_absent():
    rendered = _format_snapshot_migration_backfill_status(WITHOUT_COUNTS)
    assert "docs" not in rendered
    assert rendered.startswith("RFS: Running (5%, shards 0/6")


def test_step_view_shows_zero_failures():
    status = {**WITH_COUNTS, 'summary': {**WITH_COUNTS['summary'], 'docsFailed': 0}}
    assert "docs failed 0" in _format_snapshot_migration_backfill_status(status)


def test_resource_view_shows_doc_counts_in_headline_and_details():
    headline, details = _format_backfill_status(WITH_COUNTS)
    assert "docs 1,234,567 succeeded / 6,300 failed" in headline
    assert "docs succeeded: 1,234,567" in details
    assert "docs failed: 6,300" in details


def test_resource_view_omits_doc_counts_when_absent():
    headline, details = _format_backfill_status(WITHOUT_COUNTS)
    assert "docs" not in headline
    assert not any(d.startswith("docs") for d in details)
