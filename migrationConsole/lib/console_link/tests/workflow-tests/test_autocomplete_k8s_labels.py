import json
import tempfile
from pathlib import Path
from unittest.mock import Mock, patch

from click.shell_completion import CompletionItem

from console_link.workflow.commands.autocomplete_k8s_labels import complete_label_value


def test_complete_label_value_returns_matching_values():
    ctx = Mock()
    ctx.params = {'workflow_name': 'test-workflow'}

    with patch(
        'console_link.workflow.commands.autocomplete_k8s_labels._get_cached_label_data',
        return_value=({'task': {'captureProxy', 'trafficReplayer'}}, [])
    ):
        results = complete_label_value('task')(ctx, None, 'tra')

    assert all(isinstance(result, CompletionItem) for result in results)
    assert [result.value for result in results] == ['trafficReplayer']


def test_complete_label_value_filters_overconstrained_combos():
    ctx = Mock()
    ctx.params = {
        'source': 'source1',
        'target': None,
        'snapshot': None,
        'task': None,
        'from_snapshot_migration': None,
        'labels': (),
    }
    label_map = {
        'source': {'source1', 'source2'},
        'task': {'captureProxy', 'trafficReplayer'},
    }
    valid_combos = [
        frozenset({('source', 'source1'), ('task', 'captureProxy')}),
        frozenset({('source', 'source2'), ('task', 'trafficReplayer')}),
    ]

    with patch(
        'console_link.workflow.commands.autocomplete_k8s_labels._get_cached_label_data',
        return_value=(label_map, valid_combos)
    ):
        results = complete_label_value('task')(ctx, None, '')

    assert [result.value for result in results] == ['captureProxy']


def test_complete_label_value_considers_raw_label_filters():
    ctx = Mock()
    ctx.params = {
        'source': None,
        'target': None,
        'snapshot': None,
        'task': None,
        'from_snapshot_migration': None,
        'labels': ('source=source1',),
    }
    label_map = {
        'source': {'source1'},
        'task': {'captureProxy', 'trafficReplayer'},
    }
    valid_combos = [
        frozenset({('source', 'source1'), ('task', 'trafficReplayer')}),
    ]

    with patch(
        'console_link.workflow.commands.autocomplete_k8s_labels._get_cached_label_data',
        return_value=(label_map, valid_combos)
    ):
        results = complete_label_value('task')(ctx, None, '')

    assert [result.value for result in results] == ['trafficReplayer']


def test_complete_label_value_with_cache():
    ctx = Mock()
    ctx.params = {'workflow_name': 'cached-workflow'}
    cache_dir = Path(tempfile.gettempdir()) / "workflow_completions"
    cache_dir.mkdir(exist_ok=True)
    cache_file = cache_dir / "labels_cached-workflow.json"
    cache_file.write_text(json.dumps({
        'labels': {'task': ['captureProxy']},
        'combos': [[('task', 'captureProxy')]]
    }))

    try:
        with patch('console_link.workflow.commands.autocomplete_k8s_labels._fetch_workflow_labels') as mock_fetch:
            results = complete_label_value('task')(ctx, None, '')
            mock_fetch.assert_not_called()

        assert [result.value for result in results] == ['captureProxy']
    finally:
        cache_file.unlink(missing_ok=True)


def test_complete_label_value_empty_data():
    ctx = Mock()
    ctx.params = {'workflow_name': 'test-workflow'}

    with patch(
        'console_link.workflow.commands.autocomplete_k8s_labels._get_cached_label_data',
        return_value=({}, [])
    ):
        results = complete_label_value('task')(ctx, None, '')

    assert results == []
