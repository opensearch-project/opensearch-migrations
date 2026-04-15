"""Tests for the reset command and K8sResetService."""

from click.testing import CliRunner
from unittest.mock import Mock, patch, call
import pytest

from console_link.workflow.cli import workflow_cli
from console_link.workflow.services.k8s_reset_service import (
    K8sResetService,
    DeleteResult,
    ResetResult,
    MIGRATION_API_GROUP,
    MIGRATION_API_VERSION,
    KAFKA_API_GROUP,
    TEARDOWN_ANNOTATION,
)


# ─────────────────────────────────────────────────────────────
# K8sResetService unit tests
# ─────────────────────────────────────────────────────────────

class TestK8sResetService:
    """Test the K8s reset service."""

    @patch('console_link.workflow.services.k8s_reset_service.client.CustomObjectsApi')
    def test_reset_basic_crds_only(self, mock_api_class):
        """Reset without --include-proxy deletes only replay/snapshot CRDs."""
        mock_api = Mock()
        mock_api_class.return_value = mock_api

        # list returns one resource of each type
        def list_side_effect(**kwargs):
            plural = kwargs.get("plural", "")
            resources = {
                "trafficreplays": [{"metadata": {"name": "replay-1"}}],
                "snapshotmigrations": [{"metadata": {"name": "snap-mig-1"}}],
                "datasnapshots": [{"metadata": {"name": "ds-1"}}],
            }
            return {"items": resources.get(plural, [])}

        mock_api.list_namespaced_custom_object.side_effect = list_side_effect
        mock_api.delete_namespaced_custom_object.return_value = {}

        service = K8sResetService(namespace="ma")
        result = service.reset(include_proxy=False)

        assert result.success is True
        assert len(result.results) == 3

        # Verify deletion calls — only migration CRDs, no kafka
        delete_calls = mock_api.delete_namespaced_custom_object.call_args_list
        assert len(delete_calls) == 3
        deleted_names = [c.kwargs["name"] for c in delete_calls]
        assert "replay-1" in deleted_names
        assert "snap-mig-1" in deleted_names
        assert "ds-1" in deleted_names

    @patch('console_link.workflow.services.k8s_reset_service.client.CustomObjectsApi')
    def test_reset_with_proxy_includes_kafka(self, mock_api_class):
        """Reset with include_proxy=True also deletes proxy and Kafka."""
        mock_api = Mock()
        mock_api_class.return_value = mock_api

        def list_side_effect(**kwargs):
            plural = kwargs.get("plural", "")
            resources = {
                "trafficreplays": [],
                "snapshotmigrations": [],
                "datasnapshots": [],
                "capturedtraffics": [{"metadata": {"name": "proxy-1"}}],
                "kafkatopics": [{"metadata": {"name": "topic-1"}}],
                "kafkanodepools": [{"metadata": {"name": "pool-1"}}],
                "kafkas": [{"metadata": {"name": "kafka-1"}}],
            }
            return {"items": resources.get(plural, [])}

        mock_api.list_namespaced_custom_object.side_effect = list_side_effect
        mock_api.delete_namespaced_custom_object.return_value = {}
        mock_api.patch_namespaced_custom_object.return_value = {}

        service = K8sResetService(namespace="ma")
        result = service.reset(include_proxy=True)

        assert result.success is True

        # Should have patched teardown annotation on 3 kafka resources
        patch_calls = mock_api.patch_namespaced_custom_object.call_args_list
        assert len(patch_calls) == 3
        for c in patch_calls:
            body = c.kwargs["body"]
            assert TEARDOWN_ANNOTATION in body["metadata"]["annotations"]

        # Should have deleted: proxy-1 + topic-1 + pool-1 + kafka-1 = 4
        delete_calls = mock_api.delete_namespaced_custom_object.call_args_list
        assert len(delete_calls) == 4

    @patch('console_link.workflow.services.k8s_reset_service.client.CustomObjectsApi')
    def test_reset_named_resource(self, mock_api_class):
        """Reset with resource_name targets a specific resource by name."""
        mock_api = Mock()
        mock_api_class.return_value = mock_api
        mock_api.delete_namespaced_custom_object.return_value = {}

        service = K8sResetService(namespace="ma")
        result = service.reset(include_proxy=False, resource_name="my-migration")

        assert result.success is True
        # Should have attempted to delete exactly 3 resources by name
        # (TrafficReplay, SnapshotMigration, DataSnapshot — each with resource_name)
        delete_calls = mock_api.delete_namespaced_custom_object.call_args_list
        assert len(delete_calls) == 3
        for c in delete_calls:
            assert c.kwargs["name"] == "my-migration"
        # No list calls when resource_name is specified
        mock_api.list_namespaced_custom_object.assert_not_called()

    @patch('console_link.workflow.services.k8s_reset_service.client.CustomObjectsApi')
    def test_reset_not_found_is_success(self, mock_api_class):
        """Resources that don't exist should be silently skipped."""
        from kubernetes.client.rest import ApiException

        mock_api = Mock()
        mock_api_class.return_value = mock_api

        # All deletes return 404
        mock_api.delete_namespaced_custom_object.side_effect = ApiException(status=404)

        service = K8sResetService(namespace="ma")
        result = service.reset(include_proxy=False, resource_name="nonexistent")

        assert result.success is True
        assert all(r.not_found for r in result.results)
        assert len(result.results) == 3

    @patch('console_link.workflow.services.k8s_reset_service.client.CustomObjectsApi')
    def test_reset_failure_propagates(self, mock_api_class):
        """A real API error should result in failure."""
        from kubernetes.client.rest import ApiException

        mock_api = Mock()
        mock_api_class.return_value = mock_api
        mock_api.delete_namespaced_custom_object.side_effect = ApiException(status=403, reason="Forbidden")

        service = K8sResetService(namespace="ma")
        result = service.reset(include_proxy=False, resource_name="forbidden")

        assert result.success is False
        assert any(not r.success for r in result.results)

    @patch('console_link.workflow.services.k8s_reset_service.client.CustomObjectsApi')
    def test_teardown_annotation_failure_skips_delete(self, mock_api_class):
        """If annotation stamp fails, the resource should NOT be deleted."""
        from kubernetes.client.rest import ApiException

        mock_api = Mock()
        mock_api_class.return_value = mock_api

        def list_side_effect(**kwargs):
            plural = kwargs.get("plural", "")
            if plural == "kafkas":
                return {"items": [{"metadata": {"name": "kafka-1"}}]}
            return {"items": []}

        mock_api.list_namespaced_custom_object.side_effect = list_side_effect

        # Patch (annotation) fails with 500
        mock_api.patch_namespaced_custom_object.side_effect = ApiException(
            status=500, reason="Internal Server Error"
        )

        service = K8sResetService(namespace="ma")
        # Call teardown_kafka directly
        results = service._teardown_kafka(resource_name=None)

        # Should have one failure result, and delete should NOT have been called for kafka
        failed = [r for r in results if not r.success]
        assert len(failed) == 1
        assert "annotation" in failed[0].message.lower()
        # delete should not have been called since annotation failed
        mock_api.delete_namespaced_custom_object.assert_not_called()

    @patch('console_link.workflow.services.k8s_reset_service.client.CustomObjectsApi')
    def test_reset_empty_namespace(self, mock_api_class):
        """Reset on an empty namespace returns success with no results."""
        mock_api = Mock()
        mock_api_class.return_value = mock_api

        mock_api.list_namespaced_custom_object.return_value = {"items": []}

        service = K8sResetService(namespace="ma")
        result = service.reset(include_proxy=False)

        assert result.success is True
        assert len(result.results) == 0

    @patch('console_link.workflow.services.k8s_reset_service.client.CustomObjectsApi')
    def test_deletion_order(self, mock_api_class):
        """Verify deletion order: TrafficReplay -> SnapshotMigration -> DataSnapshot."""
        mock_api = Mock()
        mock_api_class.return_value = mock_api
        mock_api.delete_namespaced_custom_object.return_value = {}

        service = K8sResetService(namespace="ma")
        result = service.reset(include_proxy=False, resource_name="test")

        delete_calls = mock_api.delete_namespaced_custom_object.call_args_list
        assert len(delete_calls) == 3
        # Verify the plural order matches our expected dependency order
        plurals = [c.kwargs["plural"] for c in delete_calls]
        assert plurals == ["trafficreplays", "snapshotmigrations", "datasnapshots"]

    @patch('console_link.workflow.services.k8s_reset_service.client.CustomObjectsApi')
    def test_kafka_deletion_order(self, mock_api_class):
        """Verify Kafka deletion order: topics -> node pools -> clusters."""
        mock_api = Mock()
        mock_api_class.return_value = mock_api
        mock_api.delete_namespaced_custom_object.return_value = {}
        mock_api.patch_namespaced_custom_object.return_value = {}

        service = K8sResetService(namespace="ma")
        results = service._teardown_kafka(resource_name="test")

        # Annotation stamp + delete for each of 3 kafka resource types
        patch_calls = mock_api.patch_namespaced_custom_object.call_args_list
        assert len(patch_calls) == 3
        patch_plurals = [c.kwargs["plural"] for c in patch_calls]
        assert patch_plurals == ["kafkatopics", "kafkanodepools", "kafkas"]

    def test_reset_result_summary(self):
        """Test the ResetResult.summary property."""
        result = ResetResult(success=True, results=[
            DeleteResult(kind="TrafficReplay", name="r1", success=True,
                        message="Deleted TrafficReplay/r1"),
            DeleteResult(kind="DataSnapshot", name="ds1", success=True,
                        message="DataSnapshot/ds1 not found", not_found=True),
            DeleteResult(kind="SnapshotMigration", name="sm1", success=False,
                        message="Failed to delete SnapshotMigration/sm1: Forbidden (403)"),
        ])
        summary = result.summary
        assert "1 deleted" in summary
        assert "1 not found (skipped)" in summary
        assert "1 failed" in summary

    def test_reset_result_summary_empty(self):
        """Test ResetResult.summary when nothing happened."""
        result = ResetResult(success=True, results=[])
        assert result.summary == "nothing to do"


# ─────────────────────────────────────────────────────────────
# CLI command tests
# ─────────────────────────────────────────────────────────────

class TestResetCLICommand:
    """Test the reset CLI command."""

    @patch('console_link.workflow.commands.reset.K8sResetService')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    def test_reset_basic_confirmed(self, mock_k8s_config, mock_service_class):
        """Test basic reset with --yes confirmation."""
        mock_service = Mock()
        mock_service_class.return_value = mock_service
        mock_service.reset.return_value = ResetResult(
            success=True,
            results=[
                DeleteResult(kind="TrafficReplay", name="replay-1", success=True,
                           message="Deleted TrafficReplay/replay-1"),
            ]
        )

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '-y'])

        assert result.exit_code == 0
        assert "Reset complete" in result.output
        mock_service.reset.assert_called_once_with(
            include_proxy=False,
            resource_name=None,
        )

    @patch('console_link.workflow.commands.reset.K8sResetService')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    def test_reset_with_include_proxy(self, mock_k8s_config, mock_service_class):
        """Test reset with --include-proxy flag."""
        mock_service = Mock()
        mock_service_class.return_value = mock_service
        mock_service.reset.return_value = ResetResult(success=True, results=[])

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '--include-proxy', '-y'])

        assert result.exit_code == 0
        mock_service.reset.assert_called_once_with(
            include_proxy=True,
            resource_name=None,
        )

    @patch('console_link.workflow.commands.reset.K8sResetService')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    def test_reset_with_resource_name(self, mock_k8s_config, mock_service_class):
        """Test reset with --resource-name flag."""
        mock_service = Mock()
        mock_service_class.return_value = mock_service
        mock_service.reset.return_value = ResetResult(success=True, results=[])

        runner = CliRunner()
        result = runner.invoke(workflow_cli, [
            'reset', '--resource-name', 'my-migration', '-y'
        ])

        assert result.exit_code == 0
        mock_service.reset.assert_called_once_with(
            include_proxy=False,
            resource_name="my-migration",
        )

    @patch('console_link.workflow.commands.reset.K8sResetService')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    def test_reset_with_namespace(self, mock_k8s_config, mock_service_class):
        """Test reset with custom --namespace."""
        mock_service = Mock()
        mock_service_class.return_value = mock_service
        mock_service.reset.return_value = ResetResult(success=True, results=[])

        runner = CliRunner()
        result = runner.invoke(workflow_cli, [
            'reset', '--namespace', 'custom-ns', '-y'
        ])

        assert result.exit_code == 0
        mock_service_class.assert_called_once_with(namespace='custom-ns')

    def test_reset_shows_help(self):
        """Test that reset --help works."""
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '--help'])

        assert result.exit_code == 0
        assert "Reset migration resources" in result.output
        assert "--include-proxy" in result.output
        assert "--resource-name" in result.output

    @patch('console_link.workflow.commands.reset.K8sResetService')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    def test_reset_abort_on_no_confirm(self, mock_k8s_config, mock_service_class):
        """Test that reset aborts when user declines confirmation."""
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset'], input='n\n')

        assert "Aborted" in result.output
        mock_service_class.assert_not_called()

    @patch('console_link.workflow.commands.reset.K8sResetService')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    def test_reset_failure_shows_errors(self, mock_k8s_config, mock_service_class):
        """Test that reset shows error details on failure."""
        mock_service = Mock()
        mock_service_class.return_value = mock_service
        mock_service.reset.return_value = ResetResult(
            success=False,
            results=[
                DeleteResult(kind="TrafficReplay", name="replay-1", success=False,
                           message="Failed to delete TrafficReplay/replay-1: Forbidden (403)"),
            ]
        )

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '-y'])

        assert "errors" in result.output
        assert "Forbidden" in result.output

    @patch('console_link.workflow.commands.reset.load_k8s_config')
    def test_reset_k8s_config_failure(self, mock_k8s_config):
        """Test that reset handles K8s config failure gracefully."""
        mock_k8s_config.side_effect = Exception("No kubeconfig found")

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '-y'])

        assert "Could not load Kubernetes configuration" in result.output

    @patch('console_link.workflow.commands.reset.K8sResetService')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    def test_reset_proxy_warning_shown(self, mock_k8s_config, mock_service_class):
        """Test that --include-proxy shows a warning about Kafka teardown."""
        mock_service = Mock()
        mock_service_class.return_value = mock_service
        mock_service.reset.return_value = ResetResult(success=True, results=[])

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '--include-proxy', '-y'])

        assert "include-proxy" in result.output or "Kafka" in result.output

    @patch('console_link.workflow.commands.reset.K8sResetService')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    def test_reset_accept_confirm_prompt(self, mock_k8s_config, mock_service_class):
        """Test that reset proceeds when user confirms."""
        mock_service = Mock()
        mock_service_class.return_value = mock_service
        mock_service.reset.return_value = ResetResult(success=True, results=[])

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset'], input='y\n')

        assert result.exit_code == 0
        mock_service.reset.assert_called_once()
