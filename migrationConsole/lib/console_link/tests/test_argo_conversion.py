"""
Pytest for argo-graph to nested-tree conversion with small test cases.
"""

import json
import pytest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent.parent))
from console_link.workflow.tree_utils import build_nested_workflow_tree


class TestArgoGraphConversion:
    """Test argo-graph to nested-tree conversion."""
    
    @pytest.fixture
    def input_dir(self):
        return Path(__file__).parent / 'workflows' / 'testData' / 'argo_conversion' / 'inputs'
    
    @pytest.fixture
    def expected_dir(self):
        return Path(__file__).parent / 'workflows' / 'testData' / 'argo_conversion' / 'outputs'
    
    def test_simple_linear(self, input_dir, expected_dir):
        """Test simple linear structure conversion."""
        self._test_conversion('simple_linear.json', input_dir, expected_dir)
    
    def test_nested_container(self, input_dir, expected_dir):
        """Test nested container structure conversion."""
        self._test_conversion('nested_container.json', input_dir, expected_dir)
    
    def test_deep_nesting(self, input_dir, expected_dir):
        """Test deep nesting structure conversion."""
        self._test_conversion('deep_nesting.json', input_dir, expected_dir)
    
    def _test_conversion(self, filename, input_dir, expected_dir):
        """Test conversion for a specific file."""
        # Load argo-graph
        input_file = input_dir / filename
        with open(input_file, 'r') as f:
            argo_data = json.load(f)
        
        # Convert to nested-tree
        nested_tree = build_nested_workflow_tree(argo_data)
        
        # Load expected output
        expected_file = expected_dir / filename
        if expected_file.exists():
            with open(expected_file, 'r') as f:
                expected = json.load(f)
            assert nested_tree == expected, f"Output changed for {filename}"
        else:
            # Save current output as expected
            expected_dir.mkdir(exist_ok=True)
            with open(expected_file, 'w') as f:
                json.dump(nested_tree, f, indent=2)
            pytest.skip(f"Created expected output for {filename}")
