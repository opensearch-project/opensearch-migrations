"""Tests for workflow reset command (CRD-based teardown)."""
from unittest.mock import patch, Mock

from click.testing import CliRunner

from console_link.workflow.cli import workflow_cli
from console_link.workflow.commands.reset import (
    _list_migration_crds,
    _patch_teardown,
)


def _mock_crd_list(items_by_plural):
    """Create a mock CustomObjectsApi that returns items for each plural."""
    mock_custom = Mock()

    def list_fn(group, version, namespace, plural):
        return {'items': items_by_plural.get(plural, [])}

    mock_custom.list_namespaced_custom_object.side_effect = list_fn
    return mock_custom


class TestListMigrationCrds:
    @patch('console_link.workflow.commands.reset.client')
    def test_lists_crds_with_phases(self, mock_client):
        mock_custom = _mock_crd_list({
            'capturedtraffics': [
                {'metadata': {'name': 'source-proxy'}, 'status': {'phase': 'Ready'}},
            ],
            'trafficreplays': [
                {'metadata': {'name': 'src-tgt-replayer'}, 'status': {'phase': 'Running'}},
            ],
            'snapshotmigrations': [],
        })
        mock_client.CustomObjectsApi.return_value = mock_custom

        result = _list_migration_crds('ma')
        assert len(result) == 2
        assert ('capturedtraffics', 'source-proxy', 'Ready') in result
        assert ('trafficreplays', 'src-tgt-replayer', 'Running') in result

    @patch('console_link.workflow.commands.reset.client')
    def test_handles_missing_status(self, mock_client):
        mock_custom = _mock_crd_list({
            'capturedtraffics': [{'metadata': {'name': 'x'}}],
            'trafficreplays': [],
            'snapshotmigrations': [],
        })
        mock_client.CustomObjectsApi.return_value = mock_custom

        result = _list_migration_crds('ma')
        assert result == [('capturedtraffics', 'x', 'Unknown')]


class TestPatchTeardown:
    @patch('console_link.workflow.commands.reset.client')
    def test_patches_status(self, mock_client):
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom

        assert _patch_teardown('ma', 'capturedtraffics', 'source-proxy') is True
        mock_custom.patch_namespaced_custom_object_status.assert_called_once_with(
            group='migrations.opensearch.org', version='v1alpha1',
            namespace='ma', plural='capturedtraffics', name='source-proxy',
            body={'status': {'phase': 'Teardown'}}
        )


class TestResetCommandList:
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._list_migration_crds')
    def test_list_mode(self, mock_list, mock_k8s):
        mock_list.return_value = [
            ('capturedtraffics', 'source-proxy', 'Ready'),
            ('trafficreplays', 'src-tgt-replayer', 'Ready'),
        ]
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset'])
        assert result.exit_code == 0
        assert 'source-proxy' in result.output
        assert 'src-tgt-replayer' in result.output
        assert 'Capture Proxy' in result.output
        assert 'Ready' in result.output

    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._list_migration_crds')
    def test_no_resources(self, mock_list, mock_k8s):
        mock_list.return_value = []
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset'])
        assert 'No migration resources' in result.output


class TestResetCommandPatch:
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._patch_teardown')
    @patch('console_link.workflow.commands.reset._list_migration_crds')
    def test_patch_specific_resource(self, mock_list, mock_patch, mock_k8s):
        mock_list.return_value = [
            ('capturedtraffics', 'source-proxy', 'Ready'),
            ('trafficreplays', 'src-tgt-replayer', 'Ready'),
        ]
        mock_patch.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'source-proxy'])
        assert result.exit_code == 0
        assert '✓ Patched source-proxy' in result.output
        mock_patch.assert_called_once_with('ma', 'capturedtraffics', 'source-proxy')

    @patch('console_link.workflow.commands.reset.delete_workflow')
    @patch('console_link.workflow.commands.reset.wait_for_workflow_completion')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._patch_teardown')
    @patch('console_link.workflow.commands.reset._list_migration_crds')
    @patch('console_link.workflow.commands.reset.list_approval_gates')
    @patch('console_link.workflow.commands.reset.approve_gate')
    @patch('console_link.workflow.commands.reset._delete_migration_deployments')
    def test_reset_all(self, mock_del_deps, mock_approve, mock_gates,
                       mock_list, mock_patch, mock_k8s, mock_wait, mock_delete):
        mock_list.return_value = [
            ('capturedtraffics', 'source-proxy', 'Ready'),
            ('trafficreplays', 'src-tgt-replayer', 'Ready'),
        ]
        mock_gates.return_value = []
        mock_patch.return_value = True
        mock_approve.return_value = True
        mock_wait.return_value = 'Succeeded'
        mock_delete.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '--all'])
        assert result.exit_code == 0
        assert mock_patch.call_count == 2
        mock_del_deps.assert_called_once_with('ma')
        assert 'Deleted workflow' in result.output

    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._list_migration_crds')
    def test_skips_already_teardown(self, mock_list, mock_k8s):
        mock_list.return_value = [
            ('capturedtraffics', 'source-proxy', 'Teardown'),
        ]
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'source-proxy'])
        assert 'No resources to teardown' in result.output
