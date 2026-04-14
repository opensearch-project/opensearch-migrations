"""Tests for workflow reset command (CRD deletion-based teardown)."""
from unittest.mock import patch, Mock

from click.testing import CliRunner

from console_link.workflow.cli import workflow_cli
from console_link.workflow.commands.reset import (
    _delete_crd,
    _find_dependents,
)
from console_link.workflow.commands.crd_utils import list_migration_resources


def _mock_crd_list(items_by_plural):
    """Create a mock CustomObjectsApi that returns items for each plural."""
    mock_custom = Mock()

    def list_fn(group, version, namespace, plural):
        return {'items': items_by_plural.get(plural, [])}

    mock_custom.list_namespaced_custom_object.side_effect = list_fn
    return mock_custom


class TestListMigrationCrds:
    @patch('console_link.workflow.commands.crd_utils.client')
    def test_lists_crds_with_phases_and_deps(self, mock_client):
        mock_custom = _mock_crd_list({
            'capturedtraffics': [
                {'metadata': {'name': 'source-proxy'}, 'spec': {'dependsOn': ['kafka1']}, 'status': {'phase': 'Ready'}},
            ],
            'trafficreplays': [
                {'metadata': {'name': 'src-tgt-replayer'},
                 'spec': {'dependsOn': ['source-proxy']},
                 'status': {'phase': 'Running'}},
            ],
            'snapshotmigrations': [],
            'kafkaclusters': [],
            'approvalgates': [],
            'datasnapshots': [],
        })
        mock_client.CustomObjectsApi.return_value = mock_custom

        result = list_migration_resources('ma')
        assert len(result) == 2
        assert ('capturedtraffics', 'source-proxy', 'Ready', ['kafka1']) in result
        assert ('trafficreplays', 'src-tgt-replayer', 'Running', ['source-proxy']) in result

    @patch('console_link.workflow.commands.crd_utils.client')
    def test_handles_missing_status_and_deps(self, mock_client):
        mock_custom = _mock_crd_list({
            'capturedtraffics': [{'metadata': {'name': 'x'}, 'spec': {}}],
            'trafficreplays': [],
            'snapshotmigrations': [],
            'kafkaclusters': [],
            'approvalgates': [],
            'datasnapshots': [],
        })
        mock_client.CustomObjectsApi.return_value = mock_custom

        result = list_migration_resources('ma')
        assert result == [('capturedtraffics', 'x', 'Unknown', [])]


class TestFindDependents:
    def test_finds_direct_dependents(self):
        resources = [
            ('kafkaclusters', 'kafka1', 'Ready', []),
            ('capturedtraffics', 'proxy1', 'Ready', ['kafka1']),
            ('trafficreplays', 'replay1', 'Ready', ['proxy1']),
        ]
        deps = _find_dependents({'kafka1'}, resources)
        assert set(deps) == {'proxy1', 'replay1'}

    def test_no_dependents(self):
        resources = [
            ('trafficreplays', 'replay1', 'Ready', ['proxy1']),
        ]
        assert _find_dependents({'replay1'}, resources) == []

    def test_parallel_migrations_only_affect_own_deps(self):
        resources = [
            ('datasnapshots', 'snap-a', 'Ready', ['proxy1']),
            ('datasnapshots', 'snap-b', 'Ready', ['proxy1']),
            ('snapshotmigrations', 'mig-a', 'Ready', ['snap-a']),
            ('snapshotmigrations', 'mig-b', 'Ready', ['snap-b']),
        ]
        # Deleting snap-a should only affect mig-a, not mig-b
        deps = _find_dependents({'snap-a'}, resources)
        assert deps == ['mig-a']


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
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    def test_list_mode_shows_deps(self, mock_list, mock_k8s):
        mock_list.return_value = [
            ('capturedtraffics', 'source-proxy', 'Ready', ['kafka1']),
            ('trafficreplays', 'src-tgt-replayer', 'Ready', ['source-proxy']),
        ]
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset'])
        assert result.exit_code == 0
        assert 'source-proxy' in result.output
        assert 'depends on: kafka1' in result.output
        assert 'depends on: source-proxy' in result.output

    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    def test_no_resources(self, mock_list, mock_k8s):
        mock_list.return_value = []
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset'])
        assert 'No migration resources' in result.output


class TestResetCommandDelete:
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    @patch('console_link.workflow.commands.reset._wait_until_gone')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._delete_crd')
    @patch('console_link.workflow.commands.reset._find_resource_by_name')
    def test_delete_leaf_resource(self, mock_find, mock_delete, mock_k8s, mock_wait, mock_list):
        mock_find.return_value = ('trafficreplays', 'replayer1', 'Ready', [])
        mock_delete.return_value = True
        mock_list.return_value = []  # no other resources

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'replayer1'])
        assert result.exit_code == 0
        assert '✓ Deleted replayer1' in result.output

    @patch('console_link.workflow.commands.reset.list_migration_resources')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._find_resource_by_name')
    def test_blocked_when_dependents_exist(self, mock_find, mock_k8s, mock_list):
        mock_find.return_value = ('datasnapshots', 'snap1', 'Ready', [])
        mock_list.return_value = [
            ('snapshotmigrations', 'mig1', 'Ready', ['snap1']),
        ]

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'snap1'])
        assert result.exit_code != 0
        assert 'Cannot delete' in result.output
        assert 'mig1' in result.output

    @patch('console_link.workflow.commands.reset._stop_and_delete_workflows')
    @patch('console_link.workflow.commands.reset._wait_until_gone')
    @patch('console_link.workflow.commands.reset._delete_crd')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    def test_reset_all_include_proxies(self, mock_list, mock_k8s, mock_delete, mock_wait, mock_wf):
        mock_list.return_value = [
            ('capturedtraffics', 'source-proxy', 'Ready', []),
            ('trafficreplays', 'src-tgt-replayer', 'Ready', ['source-proxy']),
        ]
        mock_delete.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '--all', '--include-proxies'])
        assert result.exit_code == 0
        assert '✓ Deleted source-proxy' in result.output
        assert '✓ Deleted src-tgt-replayer' in result.output
        mock_wf.assert_called_once_with('ma')
        assert 'Done.' in result.output

    @patch('console_link.workflow.commands.reset._stop_and_delete_workflows')
    @patch('console_link.workflow.commands.proxy._set_capture_mode_headless')
    @patch('console_link.workflow.commands.reset._wait_until_gone')
    @patch('console_link.workflow.commands.reset._delete_crd')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    def test_reset_all_default_disables_proxy_capture(self, mock_list, mock_k8s, mock_delete, mock_wait, mock_headless, mock_wf):
        mock_list.return_value = [
            ('capturedtraffics', 'source-proxy', 'Ready', []),
            ('trafficreplays', 'src-tgt-replayer', 'Ready', ['source-proxy']),
        ]
        mock_delete.return_value = True
        mock_headless.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '--all'])
        assert result.exit_code == 0
        mock_headless.assert_called_once_with('ma', ['source-proxy'], enable=False)
        assert '✓ Deleted src-tgt-replayer' in result.output
        # Proxy is NOT deleted — capture is disabled instead
        assert '✓ Deleted source-proxy' not in result.output
        assert 'Done.' in result.output

    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._find_resource_by_name')
    def test_nonexistent_resource(self, mock_find, mock_k8s):
        mock_find.return_value = None
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'does-not-exist'])
        assert 'No resources matching' in result.output
