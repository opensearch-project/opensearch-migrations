"""Tests for workflow pause, resume, and scale commands."""

from click.testing import CliRunner
from unittest.mock import patch, Mock

from console_link.workflow.cli import workflow_cli
from console_link.workflow.services.deployment_service import DeploymentInfo


def _make_dep(name, replicas, task_name, pre_pause=None):
    return DeploymentInfo(name=name, namespace="ma", replicas=replicas,
                          task_name=task_name, pre_pause_replicas=pre_pause)


RUNNING_BACKFILL = _make_dep("test-rfs", 5, "src.tgt.backfill")
RUNNING_REPLAYER = _make_dep("test-replayer", 3, "src.tgt.replayer")
PAUSED_BACKFILL = _make_dep("test-rfs", 0, "src.tgt.backfill", pre_pause=5)
PAUSED_REPLAYER = _make_dep("test-replayer", 0, "src.tgt.replayer", pre_pause=3)


class TestDeploymentInfo:
    def test_is_paused_true(self):
        dep = _make_dep("x", 0, "task", pre_pause=3)
        assert dep.is_paused is True

    def test_is_paused_false_when_running(self):
        dep = _make_dep("x", 3, "task")
        assert dep.is_paused is False

    def test_is_paused_false_when_zero_no_annotation(self):
        dep = _make_dep("x", 0, "task")
        assert dep.is_paused is False

    def test_display_name_uses_task_name(self):
        dep = _make_dep("deployment-name", 1, "my.task")
        assert dep.display_name == "my.task"

    def test_display_name_falls_back_to_name(self):
        dep = _make_dep("deployment-name", 1, None)
        assert dep.display_name == "deployment-name"


class TestDeploymentService:
    def test_filter_by_task_names_exact(self):
        from console_link.workflow.services.deployment_service import DeploymentService
        service = DeploymentService.__new__(DeploymentService)
        result = service.filter_by_task_names([RUNNING_BACKFILL, RUNNING_REPLAYER],
                                              ("src.tgt.backfill",))
        assert len(result) == 1
        assert result[0].task_name == "src.tgt.backfill"

    def test_filter_by_task_names_glob(self):
        from console_link.workflow.services.deployment_service import DeploymentService
        service = DeploymentService.__new__(DeploymentService)
        result = service.filter_by_task_names([RUNNING_BACKFILL, RUNNING_REPLAYER],
                                              ("*.replayer",))
        assert len(result) == 1
        assert result[0].task_name == "src.tgt.replayer"

    def test_filter_by_task_names_empty_returns_all(self):
        from console_link.workflow.services.deployment_service import DeploymentService
        service = DeploymentService.__new__(DeploymentService)
        result = service.filter_by_task_names([RUNNING_BACKFILL, RUNNING_REPLAYER], ())
        assert len(result) == 2

    def test_filter_by_task_names_no_match(self):
        from console_link.workflow.services.deployment_service import DeploymentService
        service = DeploymentService.__new__(DeploymentService)
        result = service.filter_by_task_names([RUNNING_BACKFILL], ("*.nonexistent",))
        assert len(result) == 0

    def test_pause_skips_already_paused(self):
        from console_link.workflow.services.deployment_service import DeploymentService
        service = DeploymentService.__new__(DeploymentService)
        service.apps_api = Mock()
        result = service.pause_deployment(PAUSED_BACKFILL)
        assert result["success"] is False
        assert "already paused" in result["message"]
        service.apps_api.patch_namespaced_deployment.assert_not_called()

    def test_pause_skips_zero_replicas(self):
        from console_link.workflow.services.deployment_service import DeploymentService
        service = DeploymentService.__new__(DeploymentService)
        service.apps_api = Mock()
        dep = _make_dep("x", 0, "task")
        result = service.pause_deployment(dep)
        assert result["success"] is False
        assert "0 replicas" in result["message"]
        service.apps_api.patch_namespaced_deployment.assert_not_called()

    def test_pause_sets_annotation_and_scales_to_zero(self):
        from console_link.workflow.services.deployment_service import DeploymentService
        service = DeploymentService.__new__(DeploymentService)
        service.apps_api = Mock()
        result = service.pause_deployment(RUNNING_BACKFILL)
        assert result["success"] is True
        call_kwargs = service.apps_api.patch_namespaced_deployment.call_args
        body = call_kwargs[1]["body"] if "body" in call_kwargs[1] else call_kwargs[0][2]
        assert body["spec"]["replicas"] == 0
        assert body["metadata"]["annotations"]["migrations.opensearch.org/pre-pause-replicas"] == "5"

    def test_resume_restores_replicas_and_clears_annotation(self):
        from console_link.workflow.services.deployment_service import DeploymentService
        service = DeploymentService.__new__(DeploymentService)
        service.apps_api = Mock()
        result = service.resume_deployment(PAUSED_BACKFILL)
        assert result["success"] is True
        call_kwargs = service.apps_api.patch_namespaced_deployment.call_args
        body = call_kwargs[1]["body"] if "body" in call_kwargs[1] else call_kwargs[0][2]
        assert body["spec"]["replicas"] == 5
        assert body["metadata"]["annotations"]["migrations.opensearch.org/pre-pause-replicas"] is None

    def test_resume_skips_not_paused(self):
        from console_link.workflow.services.deployment_service import DeploymentService
        service = DeploymentService.__new__(DeploymentService)
        service.apps_api = Mock()
        result = service.resume_deployment(RUNNING_BACKFILL)
        assert result["success"] is False
        assert "not paused" in result["message"]
        service.apps_api.patch_namespaced_deployment.assert_not_called()

    def test_scale_to_zero_sets_annotation(self):
        from console_link.workflow.services.deployment_service import DeploymentService
        service = DeploymentService.__new__(DeploymentService)
        service.apps_api = Mock()
        result = service.scale_deployment(RUNNING_BACKFILL, 0)
        assert result["success"] is True
        body = service.apps_api.patch_namespaced_deployment.call_args[1]["body"]
        assert body["spec"]["replicas"] == 0
        assert body["metadata"]["annotations"]["migrations.opensearch.org/pre-pause-replicas"] == "5"

    def test_scale_to_nonzero_clears_annotation(self):
        from console_link.workflow.services.deployment_service import DeploymentService
        service = DeploymentService.__new__(DeploymentService)
        service.apps_api = Mock()
        result = service.scale_deployment(PAUSED_BACKFILL, 3)
        assert result["success"] is True
        body = service.apps_api.patch_namespaced_deployment.call_args[1]["body"]
        assert body["spec"]["replicas"] == 3
        assert body["metadata"]["annotations"]["migrations.opensearch.org/pre-pause-replicas"] is None

    def test_scale_normal_no_annotation_change(self):
        from console_link.workflow.services.deployment_service import DeploymentService
        service = DeploymentService.__new__(DeploymentService)
        service.apps_api = Mock()
        result = service.scale_deployment(RUNNING_BACKFILL, 10)
        assert result["success"] is True
        body = service.apps_api.patch_namespaced_deployment.call_args[1]["body"]
        assert body["spec"]["replicas"] == 10
        assert "metadata" not in body


class TestPauseCommand:
    @patch('console_link.workflow.commands.pause.DeploymentService')
    def test_pause_all_with_yes(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [RUNNING_BACKFILL, RUNNING_REPLAYER]
        mock_svc.filter_by_task_names.return_value = [RUNNING_BACKFILL, RUNNING_REPLAYER]
        mock_svc.pause_deployment.return_value = {"success": True, "message": "Paused"}

        result = CliRunner().invoke(workflow_cli, ['pause', '--yes'])
        assert result.exit_code == 0
        assert "Paused 2 of 2" in result.output
        assert mock_svc.pause_deployment.call_count == 2

    @patch('console_link.workflow.commands.pause.DeploymentService')
    def test_pause_with_glob(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [RUNNING_BACKFILL, RUNNING_REPLAYER]
        mock_svc.filter_by_task_names.return_value = [RUNNING_BACKFILL]
        mock_svc.pause_deployment.return_value = {"success": True, "message": "Paused"}

        result = CliRunner().invoke(workflow_cli, ['pause', '*.backfill'])
        assert result.exit_code == 0
        assert "Paused 1 of 1" in result.output

    @patch('console_link.workflow.commands.pause.DeploymentService')
    def test_pause_no_deployments(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = []

        result = CliRunner().invoke(workflow_cli, ['pause', '--yes'])
        assert "No pausable Deployments found" in result.output

    @patch('console_link.workflow.commands.pause.DeploymentService')
    def test_pause_no_match_shows_available(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [RUNNING_BACKFILL]
        mock_svc.filter_by_task_names.return_value = []

        result = CliRunner().invoke(workflow_cli, ['pause', '*.nonexistent'])
        assert "No matching running Deployments found" in result.output
        assert "src.tgt.backfill" in result.output

    @patch('console_link.workflow.commands.pause.DeploymentService')
    def test_pause_skips_already_paused_in_no_args_mode(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [PAUSED_BACKFILL]
        mock_svc.filter_by_task_names.return_value = [PAUSED_BACKFILL]

        result = CliRunner().invoke(workflow_cli, ['pause', '--yes'])
        assert "No matching running Deployments found" in result.output

    @patch('console_link.workflow.commands.pause.DeploymentService')
    def test_pause_confirmation_abort(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [RUNNING_BACKFILL]
        mock_svc.filter_by_task_names.return_value = [RUNNING_BACKFILL]

        result = CliRunner().invoke(workflow_cli, ['pause'], input='n\n')
        assert "Aborted" in result.output
        mock_svc.pause_deployment.assert_not_called()

    @patch('console_link.workflow.commands.pause.DeploymentService')
    def test_pause_displays_targets(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [RUNNING_BACKFILL]
        mock_svc.filter_by_task_names.return_value = [RUNNING_BACKFILL]
        mock_svc.pause_deployment.return_value = {"success": True, "message": "Paused"}

        result = CliRunner().invoke(workflow_cli, ['pause', 'src.tgt.backfill'])
        assert "The following Deployments will be paused:" in result.output
        assert "src.tgt.backfill (5 replicas)" in result.output


class TestResumeCommand:
    @patch('console_link.workflow.commands.resume.DeploymentService')
    def test_resume_all_with_yes(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [PAUSED_BACKFILL, PAUSED_REPLAYER]
        mock_svc.filter_by_task_names.return_value = [PAUSED_BACKFILL, PAUSED_REPLAYER]
        mock_svc.resume_deployment.return_value = {"success": True, "message": "Resumed"}

        result = CliRunner().invoke(workflow_cli, ['resume', '--yes'])
        assert result.exit_code == 0
        assert "Resumed 2 of 2" in result.output

    @patch('console_link.workflow.commands.resume.DeploymentService')
    def test_resume_with_exact_name(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [PAUSED_BACKFILL, PAUSED_REPLAYER]
        mock_svc.filter_by_task_names.return_value = [PAUSED_BACKFILL]
        mock_svc.resume_deployment.return_value = {"success": True, "message": "Resumed"}

        result = CliRunner().invoke(workflow_cli, ['resume', 'src.tgt.backfill'])
        assert result.exit_code == 0
        assert "Resumed 1 of 1" in result.output

    @patch('console_link.workflow.commands.resume.DeploymentService')
    def test_resume_skips_running_in_no_args_mode(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [RUNNING_BACKFILL]
        mock_svc.filter_by_task_names.return_value = [RUNNING_BACKFILL]

        result = CliRunner().invoke(workflow_cli, ['resume', '--yes'])
        assert "No matching paused Deployments found" in result.output

    @patch('console_link.workflow.commands.resume.DeploymentService')
    def test_resume_displays_restore_count(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [PAUSED_BACKFILL]
        mock_svc.filter_by_task_names.return_value = [PAUSED_BACKFILL]
        mock_svc.resume_deployment.return_value = {"success": True, "message": "Resumed"}

        result = CliRunner().invoke(workflow_cli, ['resume', 'src.tgt.backfill'])
        assert "will restore to 5 replicas" in result.output


class TestScaleCommand:
    @patch('console_link.workflow.commands.scale.DeploymentService')
    def test_scale_with_glob(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [RUNNING_BACKFILL, RUNNING_REPLAYER]
        mock_svc.filter_by_task_names.return_value = [RUNNING_REPLAYER]
        mock_svc.scale_deployment.return_value = {"success": True, "message": "Scaled"}

        result = CliRunner().invoke(workflow_cli, ['scale', '*.replayer', '--replicas', '10'])
        assert result.exit_code == 0
        assert "Scaled 1 of 1" in result.output

    @patch('console_link.workflow.commands.scale.DeploymentService')
    def test_scale_requires_replicas(self, mock_cls):
        result = CliRunner().invoke(workflow_cli, ['scale'])
        assert result.exit_code != 0
        assert "Missing option '--replicas'" in result.output

    @patch('console_link.workflow.commands.scale.DeploymentService')
    def test_scale_all_with_yes(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [RUNNING_BACKFILL, RUNNING_REPLAYER]
        mock_svc.filter_by_task_names.return_value = [RUNNING_BACKFILL, RUNNING_REPLAYER]
        mock_svc.scale_deployment.return_value = {"success": True, "message": "Scaled"}

        result = CliRunner().invoke(workflow_cli, ['scale', '--replicas', '7', '--yes'])
        assert result.exit_code == 0
        assert "Scaled 2 of 2" in result.output

    @patch('console_link.workflow.commands.scale.DeploymentService')
    def test_scale_displays_current_replicas(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [RUNNING_BACKFILL]
        mock_svc.filter_by_task_names.return_value = [RUNNING_BACKFILL]
        mock_svc.scale_deployment.return_value = {"success": True, "message": "Scaled"}

        result = CliRunner().invoke(workflow_cli, ['scale', 'src.tgt.backfill', '--replicas', '3'])
        assert "currently 5 replicas" in result.output

    @patch('console_link.workflow.commands.scale.DeploymentService')
    def test_scale_partial_failure_counts_correctly(self, mock_cls):
        mock_svc = Mock()
        mock_cls.return_value = mock_svc
        mock_svc.discover_pausable_deployments.return_value = [RUNNING_BACKFILL, RUNNING_REPLAYER]
        mock_svc.filter_by_task_names.return_value = [RUNNING_BACKFILL, RUNNING_REPLAYER]
        mock_svc.scale_deployment.side_effect = [
            {"success": True, "message": "Scaled"},
            {"success": False, "message": "Failed"},
        ]

        result = CliRunner().invoke(workflow_cli, ['scale', '--replicas', '3', '--yes'])
        assert "Scaled 1 of 2" in result.output
