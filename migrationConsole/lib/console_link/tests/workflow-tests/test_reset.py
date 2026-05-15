"""Tests for workflow reset command (deletion-based CRD reset)."""

from unittest.mock import Mock, patch

from click.testing import CliRunner

from console_link.workflow.cli import workflow_cli
from console_link.workflow.commands.reset import (
    _artifact_output_prefix,
    _build_child_map,
    _delete_crd,
    _find_ancestors,
    _find_dependents,
    _prune_ancestors_of_protected_proxies,
)


class TestResetHelpers:
    def test_find_dependents_returns_transitive_children(self):
        resources = [
            ('kafkaclusters', 'kafka', 'Ready', []),
            ('capturedtraffics', 'proxy', 'Ready', ['kafka']),
            ('trafficreplays', 'replay', 'Ready', ['proxy']),
        ]

        assert _find_dependents({'kafka'}, resources) == ['proxy', 'replay']

    def test_find_ancestors_returns_transitive_dependencies(self):
        resources = [
            ('kafkaclusters', 'kafka', 'Ready', []),
            ('capturedtraffics', 'proxy', 'Ready', ['kafka']),
            ('trafficreplays', 'replay', 'Ready', ['proxy']),
        ]

        assert _find_ancestors({'replay'}, resources) == {'proxy', 'kafka'}

    def test_build_child_map_reverses_dependency_edges(self):
        targets = [
            ('kafkaclusters', 'kafka', 'Ready', []),
            ('capturedtraffics', 'proxy', 'Ready', ['kafka']),
            ('trafficreplays', 'replay', 'Ready', ['proxy']),
        ]

        assert _build_child_map(targets) == {
            'kafka': {'proxy'},
            'proxy': {'replay'},
            'replay': set(),
        }

    def test_artifact_output_prefix_uses_resource_display_type(self):
        assert (
            _artifact_output_prefix('snapshotmigrations', 'mig-a', 'uid-1') ==
            'migration-outputs/snapshotmigration/mig-a/uid-1/'
        )

    def test_artifact_output_prefix_includes_resource_creation_timestamp(self):
        assert (
            _artifact_output_prefix(
                'snapshotmigrations',
                'mig-a',
                'uid-1',
                '2026-05-03T22:43:35Z',
            ) ==
            'migration-outputs/snapshotmigration/mig-a/2026-05-03T22:43:35Z_uid-1/'
        )

    def test_prune_ancestors_of_protected_proxies_removes_only_capture_proxies(self):
        resources = [
            ('kafkaclusters', 'kafka', 'Ready', []),
            ('capturedtraffics', 'topic', 'Ready', ['kafka']),
            ('captureproxies', 'proxy', 'Ready', ['topic']),
            ('trafficreplays', 'replay', 'Ready', ['proxy']),
        ]

        filtered, protected = _prune_ancestors_of_protected_proxies(resources, include_proxies=False)

        assert filtered == [
            ('kafkaclusters', 'kafka', 'Ready', []),
            ('capturedtraffics', 'topic', 'Ready', ['kafka']),
            ('trafficreplays', 'replay', 'Ready', ['proxy']),
        ]
        assert protected == {'proxy'}

    def test_prune_ancestors_is_noop_when_proxies_included(self):
        resources = [
            ('kafkaclusters', 'kafka', 'Ready', []),
            ('capturedtraffics', 'topic', 'Ready', ['kafka']),
            ('captureproxies', 'proxy', 'Ready', ['topic']),
        ]

        filtered, protected = _prune_ancestors_of_protected_proxies(resources, include_proxies=True)

        assert filtered == resources
        assert protected == set()


class TestDeleteCrd:
    @patch('console_link.workflow.commands.reset.client')
    def test_delete_crd_uses_foreground_propagation(self, mock_client):
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom
        mock_client.V1DeleteOptions.return_value = 'delete-options'

        assert _delete_crd('ma', 'capturedtraffics', 'source-proxy') is True
        mock_custom.delete_namespaced_custom_object.assert_called_once_with(
            group='migrations.opensearch.org',
            version='v1alpha1',
            namespace='ma',
            plural='capturedtraffics',
            name='source-proxy',
            body='delete-options',
        )


class TestResetCommandList:
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    def test_list_mode(self, mock_list, _mock_k8s):
        mock_list.return_value = [
            ('captureproxies', 'source-proxy', 'Ready', ['source-topic']),
            ('trafficreplays', 'src-tgt-replayer', 'Ready', ['source-proxy']),
        ]

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset'])

        assert result.exit_code == 0
        assert 'source-proxy' in result.output
        assert 'src-tgt-replayer' in result.output
        assert 'captureproxy' in result.output
        assert 'depends on: source-topic' in result.output

    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    def test_no_resources(self, mock_list, _mock_k8s):
        mock_list.return_value = []

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset'])

        assert 'No migration resources found.' in result.output


class TestResetCommandDelete:
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._delete_targets')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    @patch('console_link.workflow.commands.reset._find_resource_by_name')
    def test_delete_specific_resource(
        self, mock_find, mock_list, mock_delete_targets, _mock_k8s
    ):
        resource = ('trafficreplays', 'source-replay', 'Ready', [])
        mock_find.return_value = resource
        mock_list.return_value = [resource]
        mock_delete_targets.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'source-replay'])

        assert result.exit_code == 0
        mock_delete_targets.assert_called_once_with(
            [resource],
            'ma',
            True,
        )

    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._find_resource_by_name')
    def test_delete_missing_resource(self, mock_find, _mock_k8s):
        mock_find.return_value = None

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'does-not-exist'])

        assert result.exit_code == 0
        assert "No resources matching 'does-not-exist'." in result.output

    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._find_resource_by_name')
    def test_delete_proxy_blocked_without_include_proxies(self, mock_find, _mock_k8s):
        mock_find.return_value = ('captureproxies', 'source-proxy', 'Ready', ['source-topic'])

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'source-proxy'])

        assert result.exit_code != 0
        assert 'Proxies are protected by default' in result.output

    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._delete_targets')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    @patch('console_link.workflow.commands.reset._find_resource_by_name')
    @patch('console_link.workflow.commands.reset._handle_kafka_storage')
    def test_delete_kafka_is_not_blocked_by_protected_proxy(
        self, _mock_storage, mock_find, mock_list, mock_delete_targets, _mock_k8s
    ):
        mock_find.return_value = ('kafkaclusters', 'kafka', 'Ready', [])
        mock_list.return_value = [('kafkaclusters', 'kafka', 'Ready', [])]
        mock_delete_targets.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'kafka'])

        assert result.exit_code == 0
        mock_delete_targets.assert_called_once_with([('kafkaclusters', 'kafka', 'Ready', [])], 'ma', True)

    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    @patch('console_link.workflow.commands.reset._find_resource_by_name')
    def test_delete_blocks_on_dependents_without_cascade(
        self, mock_find, mock_list, _mock_k8s
    ):
        mock_find.return_value = ('datasnapshots', 'snap-a', 'Ready', [])
        mock_list.return_value = [
            ('datasnapshots', 'snap-a', 'Ready', []),
            ('snapshotmigrations', 'mig-a', 'Ready', ['snap-a']),
        ]

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'snap-a'])

        assert result.exit_code != 0
        assert 'Cannot delete' in result.output
        assert 'snapshotmigration.mig-a' in result.output
        assert '--cascade' in result.output

    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._delete_targets')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    @patch('console_link.workflow.commands.reset._find_resource_by_name')
    def test_delete_with_cascade_expands_dependents(
        self, mock_find, mock_list, mock_delete_targets, _mock_k8s
    ):
        resources = [
            ('datasnapshots', 'snap-a', 'Ready', []),
            ('snapshotmigrations', 'mig-a', 'Ready', ['snap-a']),
        ]
        mock_find.return_value = resources[0]
        mock_list.return_value = resources
        mock_delete_targets.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'snap-a', '--cascade'])

        assert result.exit_code == 0
        mock_delete_targets.assert_called_once_with(resources, 'ma', True)


class TestResetAll:
    @patch('console_link.workflow.commands.reset._handle_kafka_storage')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._delete_targets')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    def test_reset_all_skips_only_proxies(
        self, mock_list, mock_delete_targets, _mock_k8s, _mock_storage
    ):
        mock_list.return_value = [
            ('kafkaclusters', 'kafka', 'Ready', []),
            ('capturedtraffics', 'source-topic', 'Ready', ['kafka']),
            ('captureproxies', 'source-proxy', 'Ready', ['source-topic']),
            ('trafficreplays', 'replay', 'Ready', ['source-proxy']),
        ]
        mock_delete_targets.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '--all'])

        assert result.exit_code == 0
        mock_delete_targets.assert_called_once_with(
            [
                ('kafkaclusters', 'kafka', 'Ready', []),
                ('capturedtraffics', 'source-topic', 'Ready', ['kafka']),
                ('trafficreplays', 'replay', 'Ready', ['source-proxy']),
            ],
            'ma',
            True,
        )
        assert 'Keeping protected proxies alive: captureproxy.source-proxy' in result.output
        assert 'Use --include-proxies to delete them.' in result.output

    @patch('console_link.workflow.commands.reset._handle_kafka_storage')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._delete_targets')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    def test_reset_all_with_include_proxies_deletes_everything(
        self, mock_list, mock_delete_targets, _mock_k8s, _mock_storage
    ):
        resources = [
            ('kafkaclusters', 'kafka', 'Ready', []),
            ('capturedtraffics', 'source-topic', 'Ready', ['kafka']),
            ('captureproxies', 'source-proxy', 'Ready', ['source-topic']),
        ]
        mock_list.return_value = resources
        mock_delete_targets.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '--all', '--include-proxies'])

        assert result.exit_code == 0
        mock_delete_targets.assert_called_once_with(resources, 'ma', True)

    @patch('console_link.workflow.commands.reset._handle_kafka_storage')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._delete_targets')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    @patch('console_link.workflow.commands.reset._find_resource_by_name')
    def test_keep_output_artifacts_passes_delete_false(
        self, mock_find, mock_list, mock_delete_targets, _mock_k8s, _mock_storage
    ):
        resource = ('datasnapshots', 'snap-a', 'Ready', [])
        mock_find.return_value = resource
        mock_list.return_value = [resource]
        mock_delete_targets.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'snap-a', '--keep-output-artifacts'])

        assert result.exit_code == 0
        mock_delete_targets.assert_called_once_with([resource], 'ma', False)


class TestSnapshotMigrationTargetIndexReminder:
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._delete_targets')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    @patch('console_link.workflow.commands.reset._find_resource_by_name')
    def test_reminder_shown_when_resetting_snapshot_migration(
        self, mock_find, mock_list, mock_delete_targets, _mock_k8s
    ):
        resource = ('snapshotmigrations', 'mig-a', 'Ready', [])
        mock_find.return_value = resource
        mock_list.return_value = [resource]
        mock_delete_targets.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'mig-a'])

        assert result.exit_code == 0
        assert 'console clusters clear-indices --cluster target' in result.output

    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._delete_targets')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    @patch('console_link.workflow.commands.reset._find_resource_by_name')
    def test_reminder_not_shown_when_no_snapshot_migration(
        self, mock_find, mock_list, mock_delete_targets, _mock_k8s
    ):
        resource = ('trafficreplays', 'replay-a', 'Ready', [])
        mock_find.return_value = resource
        mock_list.return_value = [resource]
        mock_delete_targets.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'replay-a'])

        assert result.exit_code == 0
        assert 'clear-indices' not in result.output

    @patch('console_link.workflow.commands.reset._handle_kafka_storage')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._delete_targets')
    @patch('console_link.workflow.commands.reset.list_migration_resources')
    def test_reminder_shown_for_reset_all_with_snapshot_migration(
        self, mock_list, mock_delete_targets, _mock_k8s, _mock_storage
    ):
        mock_list.return_value = [
            ('datasnapshots', 'snap-a', 'Ready', []),
            ('snapshotmigrations', 'mig-a', 'Ready', ['snap-a']),
        ]
        mock_delete_targets.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '--all'])

        assert result.exit_code == 0
        assert 'console clusters clear-indices --cluster target' in result.output
