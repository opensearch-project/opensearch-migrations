from unittest.mock import Mock, patch
from click.shell_completion import CompletionItem
from console_link.workflow.commands.autocomplete_k8s_labels import get_label_completions
import json
import tempfile
from pathlib import Path


def test_get_label_completions_empty_incomplete():
    """Test completion with no input - should suggest all valid key=value pairs."""
    ctx = Mock()
    ctx.params = {'workflow_name': 'test-workflow', 'namespace': 'ma'}
    
    label_map = {'env': {'prod', 'dev'}, 'region': {'us-east', 'us-west'}}
    valid_combos = [
        frozenset([('env', 'prod'), ('region', 'us-east')]),
        frozenset([('env', 'dev'), ('region', 'us-west')])
    ]
    
    with patch('console_link.workflow.commands.autocomplete_k8s_labels._get_cached_label_data',
               return_value=(label_map, valid_combos)):
        results = get_label_completions(ctx, None, '')
        
    assert len(results) > 0
    assert all(isinstance(r, CompletionItem) for r in results)
    values = [r.value for r in results]
    assert 'env=dev' in values
    assert 'env=prod' in values


def test_get_label_completions_partial_key():
    """Test completion when typing a partial key."""
    ctx = Mock()
    ctx.params = {'workflow_name': 'test-workflow'}
    
    label_map = {'env': {'prod'}, 'region': {'us-east'}}
    valid_combos = [frozenset([('env', 'prod')])]
    
    with patch('console_link.workflow.commands.autocomplete_k8s_labels._get_cached_label_data',
               return_value=(label_map, valid_combos)):
        results = get_label_completions(ctx, None, 'en')
        
    values = [r.value for r in results]
    assert 'env=prod' in values
    assert not any('region' in v for v in values)


def test_get_label_completions_with_value():
    """Test completion when key= is typed."""
    ctx = Mock()
    ctx.params = {'workflow_name': 'test-workflow'}
    
    label_map = {'env': {'prod', 'dev', 'staging'}}
    valid_combos = [frozenset([('env', 'prod')]), frozenset([('env', 'dev')])]
    
    with patch('console_link.workflow.commands.autocomplete_k8s_labels._get_cached_label_data',
               return_value=(label_map, valid_combos)):
        results = get_label_completions(ctx, None, 'env=')
        
    values = [r.value for r in results]
    assert 'env=prod' in values
    assert 'env=dev' in values
    assert not any('staging' in v for v in values)  # staging not in valid combos


def test_get_label_completions_partial_value():
    """Test completion when typing partial value after key=."""
    ctx = Mock()
    ctx.params = {'workflow_name': 'test-workflow'}
    
    label_map = {'env': {'prod', 'preview'}}
    valid_combos = [frozenset([('env', 'prod')]), frozenset([('env', 'preview')])]
    
    with patch('console_link.workflow.commands.autocomplete_k8s_labels._get_cached_label_data',
               return_value=(label_map, valid_combos)):
        results = get_label_completions(ctx, None, 'env=pr')
        
    values = [r.value for r in results]
    assert 'env=prod' in values
    assert 'env=preview' in values


def test_get_label_completions_multiple_labels():
    """Test completion with existing labels - should suggest next valid combinations."""
    ctx = Mock()
    ctx.params = {'workflow_name': 'test-workflow'}
    
    label_map = {'env': {'prod'}, 'region': {'us-east', 'us-west'}}
    valid_combos = [
        frozenset([('env', 'prod'), ('region', 'us-east')]),
        frozenset([('env', 'prod'), ('region', 'us-west')])
    ]
    
    with patch('console_link.workflow.commands.autocomplete_k8s_labels._get_cached_label_data',
               return_value=(label_map, valid_combos)):
        results = get_label_completions(ctx, None, 'env=prod,')
        
    values = [r.value for r in results]
    assert any('region=us-east' in v for v in values)
    assert any('region=us-west' in v for v in values)


def test_get_label_completions_no_duplicate_keys():
    """Test that already-used keys are not suggested again."""
    ctx = Mock()
    ctx.params = {'workflow_name': 'test-workflow'}
    
    label_map = {'env': {'prod'}, 'region': {'us-east'}}
    valid_combos = [frozenset([('env', 'prod'), ('region', 'us-east')])]
    
    with patch('console_link.workflow.commands.autocomplete_k8s_labels._get_cached_label_data',
               return_value=(label_map, valid_combos)):
        results = get_label_completions(ctx, None, 'env=prod,region=us-east,')
        
    values = [r.value for r in results]
    # Should not suggest env or region again
    assert not any('env=' in v.split(',')[-1] for v in values if v)
    assert not any('region=' in v.split(',')[-1] for v in values if v)


def test_get_label_completions_limits_results():
    """Test that results are limited to 20 items."""
    ctx = Mock()
    ctx.params = {'workflow_name': 'test-workflow'}
    
    # Create many labels
    label_map = {f'key{i}': {f'val{i}'} for i in range(50)}
    valid_combos = [frozenset([(f'key{i}', f'val{i}')]) for i in range(50)]
    
    with patch('console_link.workflow.commands.autocomplete_k8s_labels._get_cached_label_data',
               return_value=(label_map, valid_combos)):
        results = get_label_completions(ctx, None, '')
        
    assert len(results) <= 20


def test_get_label_completions_with_cache():
    """Test that caching works correctly."""
    ctx = Mock()
    ctx.params = {'workflow_name': 'cached-workflow'}
    
    # Create a cache file
    cache_dir = Path(tempfile.gettempdir()) / "workflow_completions"
    cache_dir.mkdir(exist_ok=True)
    cache_file = cache_dir / "labels_cached-workflow.json"
    
    cache_data = {
        'labels': {'env': ['prod']},
        'combos': [[('env', 'prod')]]
    }
    cache_file.write_text(json.dumps(cache_data))
    
    try:
        # Should use cache, not call the API
        with patch('console_link.workflow.commands.autocomplete_k8s_labels._fetch_workflow_labels') as mock_fetch:
            results = get_label_completions(ctx, None, '')
            mock_fetch.assert_not_called()
            
        values = [r.value for r in results]
        assert 'env=prod' in values
    finally:
        cache_file.unlink(missing_ok=True)


def test_get_label_completions_breadth_first_extensions():
    """Test that value completions include both standalone and extended combinations."""
    ctx = Mock()
    ctx.params = {'workflow_name': 'test-workflow'}
    
    label_map = {
        'env': {'prod', 'dev'},
        'region': {'us-east', 'us-west'},
        'tier': {'frontend', 'backend'}
    }
    valid_combos = [
        frozenset([('env', 'prod')]),
        frozenset([('env', 'prod'), ('region', 'us-east')]),
        frozenset([('env', 'prod'), ('tier', 'frontend')]),
        frozenset([('env', 'dev'), ('region', 'us-west')])
    ]
    
    with patch('console_link.workflow.commands.autocomplete_k8s_labels._get_cached_label_data',
               return_value=(label_map, valid_combos)):
        results = get_label_completions(ctx, None, 'env=prod')
        
    values = [r.value for r in results]
    
    # Should include standalone
    assert 'env=prod' in values
    
    # Should include extensions with other valid keys
    assert 'env=prod,region=us-east' in values
    assert 'env=prod,tier=frontend' in values
    
    # Should NOT include invalid combinations
    assert 'env=prod,region=us-west' not in values


def test_get_label_completions_empty_data():
    """Test completion with no label data available."""
    ctx = Mock()
    ctx.params = {'workflow_name': 'test-workflow'}
    
    with patch('console_link.workflow.commands.autocomplete_k8s_labels._get_cached_label_data',
               return_value=({}, [])):
        results = get_label_completions(ctx, None, 'env')
        
    assert results == []
