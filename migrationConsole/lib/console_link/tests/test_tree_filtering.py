"""
Pytest for nested-tree filtering with small test cases.
"""

import json
import pytest
from pathlib import Path
import sys
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent.parent))
from console_link.workflow.tree_utils import (
    APPROVAL_TEMPLATE_NAME,
    filter_tree_nodes,
    is_approval_node,
    overlay_approval_gate_status,
)


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

    def test_rfs_coordinator_retry(self, input_dir, expected_dir):
        """Leaf-only retries are collapsed; retries with statusOutput/groupName children are preserved."""
        self._test_filtering('rfs_coordinator_retry.json', input_dir, expected_dir)

    def test_retry_group_success(self, input_dir, expected_dir):
        """Retry group with success on first try collapses to single node."""
        self._test_filtering('retry_group_success.json', input_dir, expected_dir)

    def test_retry_group_waiting(self, input_dir, expected_dir):
        """Retry group waiting for fix shows as suspended."""
        self._test_filtering('retry_group_waiting.json', input_dir, expected_dir)

    def test_retry_group_after_retries(self, input_dir, expected_dir):
        """Retry group that succeeded after retries shows attempt count."""
        self._test_filtering('retry_group_after_retries.json', input_dir, expected_dir)

    @patch('console_link.workflow.tree_utils._fetch_approval_gate_phases')
    def test_overlay_approval_gate_status_uses_gate_phase(self, mock_fetch_phases):
        """An approved gate should display as approved before Argo reconciles."""
        mock_fetch_phases.return_value = {'gate-a': 'Approved'}
        tree_nodes = [{
            'id': 'wait-node',
            'display_name': 'waitForFix',
            'phase': 'Running',
            'is_approval': True,
            'inputs': {
                'parameters': [{'name': 'resourceName', 'value': 'gate-a'}]
            },
            'children': [],
        }]

        overlay_approval_gate_status(tree_nodes, 'ma')

        assert tree_nodes[0]['phase'] == 'Succeeded'
        assert tree_nodes[0]['approval_gate_phase'] == 'Approved'
        mock_fetch_phases.assert_called_once_with('ma', {'gate-a'})

    @patch('console_link.workflow.tree_utils._fetch_approval_gate_phases')
    def test_overlay_approval_gate_status_after_retry_group_collapse(
        self, mock_fetch_phases, input_dir
    ):
        """Collapsed change/retry gate nodes should use the live gate phase."""
        mock_fetch_phases.return_value = {'KafkaNodePool': 'Approved'}
        with open(input_dir / 'retry_group_waiting.json', 'r') as f:
            tree_nodes = json.load(f)

        filtered_tree = filter_tree_nodes(tree_nodes)
        overlay_approval_gate_status(filtered_tree, 'ma')

        assert filtered_tree[0]['phase'] == 'Succeeded'
        assert filtered_tree[0]['approval_gate_phase'] == 'Approved'
        mock_fetch_phases.assert_called_once_with('ma', {'KafkaNodePool'})
    
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


class TestIsApprovalNode:
    """Contract tests for approval-gate-wait detection.

    The matcher must survive template renames. The Argo template was
    ``waitForApproval`` and is now ``waitForUserApproval``; a previous
    exact-string matcher silently went blind on that rename and broke
    ``workflow approve``. These tests pin the rename-resilient behavior so a
    future ``waitFor<Anything>Approval`` keeps working and the sibling
    gate-mutating templates keep being excluded.
    """

    @pytest.mark.parametrize('template', [
        APPROVAL_TEMPLATE_NAME,          # current rendered name: 'waitforuserapproval'
        'waitForUserApproval',           # camelCase, in case rendering ever changes
        'waitforapproval',               # the pre-rename name
        'waitForApproval',
        'waitForOperatorApproval',       # a hypothetical future rename
    ])
    def test_matches_approval_wait_templates(self, template):
        assert is_approval_node({'templateRef': {'template': template}})
        assert is_approval_node({'templateName': template})
        assert is_approval_node({'template_ref': {'template': template}})
        assert is_approval_node({'template_name': template})

    def test_matches_explicit_is_approval_flag(self):
        # Collapsed retry-group nodes carry no templateRef; they set is_approval.
        assert is_approval_node({'is_approval': True})

    @pytest.mark.parametrize('template', [
        'patchApprovalGatePhase',        # mutates the gate, does not wait on it
        'patchApprovalAnnotation',
        'cleanupApprovalGates',
        'waitForKafkaClusterReady',      # a wait, but not for approval
        'runMetadata',
        '',
    ])
    def test_rejects_non_approval_wait_templates(self, template):
        assert not is_approval_node({'templateRef': {'template': template}})
        assert not is_approval_node({'templateName': template})

    def test_rejects_node_with_no_template_info(self):
        assert not is_approval_node({'displayName': 'evaluateMetadata', 'phase': 'Running'})
        assert not is_approval_node({})
