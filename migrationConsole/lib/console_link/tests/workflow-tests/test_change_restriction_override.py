"""Tests for the hidden change-restriction override on `workflow approve change|retry`."""

from unittest.mock import MagicMock, patch

import pytest
from click.testing import CliRunner

from console_link.workflow.cli import workflow_cli
from console_link.workflow.commands import change_restriction_override as override
from console_link.workflow.commands.approve import GateInfo

MODULE = 'console_link.workflow.commands.change_restriction_override'
OVERRIDE = '--override-change-restrictions'

IMPOSSIBLE_REASON = ("ValidatingAdmissionPolicy 'migrations-snapshotmigration-policy' denied request: "
                     "Impossible: documentBackfillDocTransformerConfig cannot be changed. Delete and recreate")
GATED_REASON = ("Gated changes detected on SnapshotMigration fields: documentBackfillMaxConnections (increase), "
                "documentBackfillMaxShardSizeBytes. Approve the corresponding ApprovalGate to proceed")


def _retry_gate(reason=IMPOSSIBLE_REASON):
    return GateInfo(
        name='snapshotmigration.mig.vapretry', category='retry', status='waiting', reason=reason,
        labels={'migrations.opensearch.org/resource-kind': 'SnapshotMigration',
                'migrations.opensearch.org/resource-name': 'mig'})


def _workflow(run_number='42'):
    return {'spec': {'arguments': {'parameters': [{'name': 'migrationRunNumber', 'value': run_number}]}}}


@pytest.fixture
def k8s():
    custom = MagicMock()
    custom.get_namespaced_custom_object.return_value = {'metadata': {'name': 'mig'}, 'status': {'phase': 'Pending'}}
    with patch('console_link.workflow.commands.approve.load_k8s_config'), \
            patch('console_link.workflow.commands.approve._gather_gates', return_value=[_retry_gate()]), \
            patch(MODULE + '.get_workflow', return_value=_workflow()), \
            patch(MODULE + '.client.CustomObjectsApi', return_value=custom), \
            patch('console_link.workflow.commands.approve._retry_blockers') as blockers:
        custom.blockers = blockers
        yield custom


class TestFieldsFromDenial:
    def test_impossible_message(self):
        assert override.fields_from_denial(IMPOSSIBLE_REASON) == ['documentBackfillDocTransformerConfig']

    def test_gated_message_drops_the_increase_marker(self):
        assert override.fields_from_denial(GATED_REASON) == [
            'documentBackfillMaxConnections', 'documentBackfillMaxShardSizeBytes']

    def test_unrecognized_message(self):
        assert override.fields_from_denial('something else') == []
        assert override.fields_from_denial(None) == []


class TestHidden:
    @pytest.mark.parametrize('subcommand', ['change', 'retry'])
    def test_flags_are_absent_from_help(self, subcommand):
        result = CliRunner().invoke(workflow_cli, ['approve', subcommand, '--help'])
        assert result.exit_code == 0
        for flag in (OVERRIDE, '--field', '--all-fields', '--yes', 'change-restriction-override'):
            assert flag not in result.output


class TestChangeRestrictionOverride:
    def test_annotates_the_denied_field_for_this_run_and_approves_without_a_reset(self, k8s):
        result = CliRunner().invoke(workflow_cli,
                                    ['approve', 'retry', 'snapshotmigration.mig', OVERRIDE, '--yes'])

        assert result.exit_code == 0, result.output
        k8s.patch_namespaced_custom_object.assert_called_once()
        body = k8s.patch_namespaced_custom_object.call_args.kwargs['body']
        assert body == {'metadata': {'annotations': {
            'change-restriction-override.migrations.opensearch.org/documentBackfillDocTransformerConfig': '42'}}}
        status = k8s.patch_namespaced_custom_object_status.call_args.kwargs
        assert status['name'] == 'snapshotmigration.mig.vapretry'
        assert status['body']['status']['phase'] == 'Approved'
        assert status['body']['status']['message'].startswith(
            'change-restriction override: documentBackfillDocTransformerConfig by ')
        k8s.blockers.assert_not_called()
        assert 'OVERRIDING CHANGE RESTRICTIONS' in result.output

    def test_named_fields_and_the_resource_wide_key(self, k8s):
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['approve', 'retry', 'snapshotmigration.mig', OVERRIDE, '--yes',
                                              '--field', 'documentBackfillIndexAllowlist'])
        assert result.exit_code == 0, result.output
        assert k8s.patch_namespaced_custom_object.call_args.kwargs['body']['metadata']['annotations'] == {
            'change-restriction-override.migrations.opensearch.org/documentBackfillIndexAllowlist': '42'}

        result = runner.invoke(workflow_cli, ['approve', 'retry', 'snapshotmigration.mig', OVERRIDE, '--yes',
                                              '--all-fields'])
        assert result.exit_code == 0, result.output
        assert k8s.patch_namespaced_custom_object.call_args.kwargs['body']['metadata']['annotations'] == {
            'migrations.opensearch.org/change-restriction-override-all-fields': '42'}

    def test_requires_typed_confirmation_without_yes(self, k8s):
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['approve', 'retry', 'snapshotmigration.mig', OVERRIDE],
                               input='wrong\n')
        assert result.exit_code != 0
        k8s.patch_namespaced_custom_object.assert_not_called()
        k8s.patch_namespaced_custom_object_status.assert_not_called()

        result = runner.invoke(workflow_cli, ['approve', 'retry', 'snapshotmigration.mig', OVERRIDE],
                               input='mig\n')
        assert result.exit_code == 0, result.output
        k8s.patch_namespaced_custom_object.assert_called_once()

    @pytest.mark.parametrize('resource', [
        {'status': {'phase': 'Completed'}},
        {'status': {'phase': 'Deleting'}},
        {'metadata': {'deletionTimestamp': '2026-01-01T00:00:00Z'}, 'status': {'phase': 'Pending'}},
    ])
    def test_refuses_sealed_resources(self, k8s, resource):
        k8s.get_namespaced_custom_object.return_value = resource
        result = CliRunner().invoke(workflow_cli,
                                    ['approve', 'retry', 'snapshotmigration.mig', OVERRIDE, '--yes'])
        assert result.exit_code != 0
        assert 'workflow reset mig' in result.output
        k8s.patch_namespaced_custom_object.assert_not_called()

    def test_refuses_when_the_fields_are_unknown(self, k8s):
        with patch('console_link.workflow.commands.approve._gather_gates', return_value=[_retry_gate('no fields')]):
            result = CliRunner().invoke(workflow_cli, ['approve', 'retry', 'snapshotmigration.mig', OVERRIDE,
                                                       '--yes'])
        assert result.exit_code != 0
        assert '--field' in result.output
        k8s.patch_namespaced_custom_object.assert_not_called()

    def test_field_options_require_the_override(self, k8s):
        result = CliRunner().invoke(workflow_cli, ['approve', 'retry', 'snapshotmigration.mig', '--all-fields'])
        assert result.exit_code != 0
        k8s.patch_namespaced_custom_object.assert_not_called()

    def test_rejects_field_names_that_cannot_be_annotations(self, k8s):
        result = CliRunner().invoke(workflow_cli, ['approve', 'retry', 'snapshotmigration.mig', OVERRIDE,
                                                   '--yes', '--field', 'x' * 64])
        assert result.exit_code != 0
        k8s.patch_namespaced_custom_object.assert_not_called()

    def test_without_the_override_retry_still_requires_a_reset(self, k8s):
        k8s.blockers.return_value = [(_retry_gate(), ('Reset', 'workflow reset mig'))]
        result = CliRunner().invoke(workflow_cli, ['approve', 'retry', 'snapshotmigration.mig'])
        assert result.exit_code != 0
        assert 'workflow reset mig' in result.output
        k8s.patch_namespaced_custom_object.assert_not_called()
