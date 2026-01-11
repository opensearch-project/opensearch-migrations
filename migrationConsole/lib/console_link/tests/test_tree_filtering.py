"""
Pytest for nested-tree filtering with small test cases.
"""

import json
import pytest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent.parent))
from console_link.workflow.tree_utils import filter_tree_nodes


class TestTreeFiltering:
    """Test nested-tree filtering."""
    
    @pytest.fixture
    def input_dir(self):
        return Path(__file__).parent / 'workflows' / 'testData' / 'tree_filtering' / 'inputs'
    
    @pytest.fixture
    def expected_dir(self):
        return Path(__file__).parent / 'workflows' / 'testData' / 'tree_filtering' / 'outputs'
    
    def test_simple_pods(self, input_dir, expected_dir):
        """Test filtering simple pods (should preserve all)."""
        self._test_filtering('simple_pods.json', input_dir, expected_dir)
    
    def test_container_with_pods(self, input_dir, expected_dir):
        """Test filtering container with pods."""
        self._test_filtering('container_with_pods.json', input_dir, expected_dir)
    
    def test_wrapper_noise(self, input_dir, expected_dir):
        """Test filtering wrapper noise (should remove wrapper)."""
        self._test_filtering('wrapper_noise.json', input_dir, expected_dir)
    
    def test_parallel_snapshots(self, input_dir, expected_dir):
        """Test filtering parallel snapshots (should preserve structure)."""
        self._test_filtering('parallel_snapshots.json', input_dir, expected_dir)
    
    def test_multiple_checks(self, input_dir, expected_dir):
        """Test filtering multiple checks (should group under container)."""
        self._test_filtering('multiple_checks.json', input_dir, expected_dir)
    
    def test_meaningful_vs_noise(self, input_dir, expected_dir):
        """Test filtering meaningful containers vs noise wrappers."""
        self._test_filtering('meaningful_vs_noise.json', input_dir, expected_dir)
    
    def _test_filtering(self, filename, input_dir, expected_dir):
        """Test filtering for a specific file."""
        # Load nested-tree
        input_file = input_dir / filename
        with open(input_file, 'r') as f:
            nested_tree = json.load(f)
        
        # Apply filtering
        filtered_tree = filter_tree_nodes(nested_tree)
        
        # Load expected output
        expected_file = expected_dir / filename
        if expected_file.exists():
            with open(expected_file, 'r') as f:
                expected = json.load(f)
            assert filtered_tree == expected, f"Filtering output changed for {filename}"
        else:
            # Save current output as expected
            expected_dir.mkdir(exist_ok=True)
            with open(expected_file, 'w') as f:
                json.dump(filtered_tree, f, indent=2)
            pytest.skip(f"Created expected filtering output for {filename}")
