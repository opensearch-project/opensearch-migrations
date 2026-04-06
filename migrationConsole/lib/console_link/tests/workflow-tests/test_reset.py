"""Tests for workflow reset command (CRD deletion-based teardown)."""
from unittest.mock import patch, Mock

from click.testing import CliRunner

from console_link.workflow.cli import workflow_cli
from console_link.workflow.commands.reset import (
    _list_migration_resources,
    _delete_crd,
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
            'kafkaclusters': [],
            'approvalgates': [],
            'datasnapshots': [],
        })
        mock_client.CustomObjectsApi.return_value = mock_custom

        result = _list_migration_resources('ma')
        assert len(result) == 2
        assert ('capturedtraffics', 'source-proxy', 'Ready') in result
        assert ('trafficreplays', 'src-tgt-replayer', 'Running') in result

    @patch('console_link.workflow.commands.reset.client')
    def test_handles_missing_status(self, mock_client):
        mock_custom = _mock_crd_list({
            'capturedtraffics': [{'metadata': {'name': 'x'}}],
            'trafficreplays': [],
            'snapshotmigrations': [],
            'kafkaclusters': [],
            'approvalgates': [],
            'datasnapshots': [],
        })
        mock_client.CustomObjectsApi.return_value = mock_custom

        result = _list_migration_resources('ma')
        assert result == [('capturedtraffics', 'x', 'Unknown')]


class TestDeleteCrd:
    @patch('console_link.workflow.commands.reset.client')
    def test_deletes_with_foreground_propagation(self, mock_client):
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom
        mock_client.V1DeleteOptions = Mock(return_value={'propagationPolicy': 'Foreground'})

        assert _delete_crd('ma', 'capturedtraffics', 'source-proxy') is True
        mock_custom.delete_namespaced_custom_object.assert_called_once()
        args = mock_custom.delete_namespaced_custom_object.call_args
        assert args.kwargs['name'] == 'source-proxy'
        assert args.kwargs['plural'] == 'capturedtraffics'


class TestResetCommandList:
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._list_migration_resources')
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
    @patch('console_link.workflow.commands.reset._list_migration_resources')
    def test_no_resources(self, mock_list, mock_k8s):
        mock_list.return_value = []
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset'])
        assert 'No migration resources' in result.output


class TestResetCommandDelete:
    @patch('console_link.workflow.commands.reset._wait_until_gone')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._delete_crd')
    @patch('console_link.workflow.commands.reset._find_resource_by_name')
    def test_delete_specific_resource(self, mock_find, mock_delete, mock_k8s, mock_wait):
        mock_find.return_value = ('capturedtraffics', 'source-proxy', 'Ready')
        mock_delete.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'source-proxy'])
        assert result.exit_code == 0
        assert '✓ Deleted source-proxy' in result.output
        mock_delete.assert_called_once_with('ma', 'capturedtraffics', 'source-proxy')

    @patch('console_link.workflow.commands.reset._stop_and_delete_workflows')
    @patch('console_link.workflow.commands.reset._wait_until_gone')
    @patch('console_link.workflow.commands.reset._delete_crd')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._list_migration_resources')
    def test_reset_all(self, mock_list, mock_k8s, mock_delete, mock_wait, mock_wf):
        mock_list.return_value = [
            ('capturedtraffics', 'source-proxy', 'Ready'),
            ('trafficreplays', 'src-tgt-replayer', 'Ready'),
        ]
        mock_delete.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '--all'])
        assert result.exit_code == 0
        assert '✓ Deleted source-proxy' in result.output
        assert '✓ Deleted src-tgt-replayer' in result.output
        mock_wf.assert_called_once_with('ma')
        assert 'Done.' in result.output

    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._find_resource_by_name')
    def test_nonexistent_resource(self, mock_find, mock_k8s):
        mock_find.return_value = None
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'does-not-exist'])
        assert 'No resources matching' in result.output
