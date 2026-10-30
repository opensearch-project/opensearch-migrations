"""CLI-level tests for `console failed-document-stream seal` and `... redrive`.

Covers what the commands promise an operator: a dry run submits nothing, filters are validated, an
unsealed session is refused, and the confirmation names the indices about to be overwritten.
"""
import json
import pathlib

import pytest
from click.testing import CliRunner

import console_link.middleware.failed_document_stream as fds
from console_link.cli import cli

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"
VALID_SERVICES_YAML = TEST_DATA_DIRECTORY / "services.yaml"

ORDERS = "orders-2024"
USERS = "users"


@pytest.fixture
def runner():
    return CliRunner()


@pytest.fixture
def cfg():
    return fds.FailedDocumentStreamConfig(
        bucket="failure-bucket", prefix="rfs-failed-document-stream/",
        session_id="session-1", region="us-east-1")


@pytest.fixture
def identity():
    return fds.MigrationIdentity(
        name="sm-1", migration_label="docs", source_label="source", target_label="target",
        target_endpoint="http://target:9200")


@pytest.fixture
def sealed(mocker, cfg, identity):
    """A sealed session holding three failures across two indices."""
    mocker.patch.object(fds, "load_config_and_identity", return_value=(cfg, identity))
    manifest = {
        "schemaVersion": 1,
        "sessionId": cfg.session_id,
        "collections": [
            {"name": ORDERS, "partitions": [{"name": "worker-a", "objectKeys": ["k1"]}]},
            {"name": USERS, "partitions": [{"name": "worker-a", "objectKeys": ["k2"]}]},
        ],
    }
    mocker.patch.object(fds, "read_manifest", return_value=manifest)
    mocker.patch.object(fds, "plan_redrive", return_value={
        "indices": {ORDERS: 2, USERS: 1},
        "documents": [{"documentId": "doc-1", "targetIndex": ORDERS}],
        "total": 3,
        "skipped_without_id": 0,
    })
    return manifest


def _invoke(runner, *args, **kwargs):
    return runner.invoke(cli, ["--config-file", str(VALID_SERVICES_YAML)] + list(args), **kwargs)


class TestRedrive:

    def test_dry_run_submits_nothing(self, runner, mocker, sealed):
        submit = mocker.patch("console_link.cli._submit_redrive_workflow")

        result = _invoke(runner, "failed-document-stream", "redrive", "--dry-run")

        assert result.exit_code == 0, result.output
        submit.assert_not_called()
        assert "Dry run: nothing was submitted." in result.output

    def test_dry_run_names_every_index_that_would_be_written(self, runner, mocker, sealed):
        mocker.patch("console_link.cli._submit_redrive_workflow")

        result = _invoke(runner, "failed-document-stream", "redrive", "--dry-run")

        assert f"{ORDERS}\t2 document(s)" in result.output
        assert f"{USERS}\t1 document(s)" in result.output

    def test_warns_that_existing_documents_will_be_replaced(self, runner, mocker, sealed):
        # Only the operator can rule out overwriting a different index's document.
        mocker.patch("console_link.cli._submit_redrive_workflow")

        result = _invoke(runner, "failed-document-stream", "redrive", "--dry-run")

        assert "will be REPLACED" in result.output

    def test_asks_before_writing(self, runner, mocker, sealed):
        submit = mocker.patch("console_link.cli._submit_redrive_workflow")

        result = _invoke(runner, "failed-document-stream", "redrive", input="n\n")

        assert result.exit_code != 0, "declining must abort"
        submit.assert_not_called()

    def test_submits_when_confirmed(self, runner, mocker, sealed):
        submit = mocker.patch("console_link.cli._submit_redrive_workflow")

        result = _invoke(runner, "failed-document-stream", "redrive", "--yes")

        assert result.exit_code == 0, result.output
        submit.assert_called_once()
        source_config = submit.call_args.args[1]
        assert source_config["sessionId"] == "session-1"
        assert source_config["streamUri"] == "s3://failure-bucket/rfs-failed-document-stream"

    def test_passes_the_filters_into_the_source_configuration(self, runner, mocker, sealed):
        submit = mocker.patch("console_link.cli._submit_redrive_workflow")

        result = _invoke(runner, "failed-document-stream", "redrive", "--yes",
                         "--index", ORDERS, "--failure-class", "RETRYABLE_EXHAUSTED")

        assert result.exit_code == 0, result.output
        source_config = submit.call_args.args[1]
        assert source_config["indexAllowlist"] == [ORDERS]
        assert source_config["failureClasses"] == ["RETRYABLE_EXHAUSTED"]

    def test_rejects_an_unknown_failure_class(self, runner, mocker, sealed):
        result = _invoke(runner, "failed-document-stream", "redrive", "--failure-class", "NOPE")

        assert result.exit_code != 0
        assert "NOPE" in result.output

    def test_refuses_an_unsealed_session(self, runner, mocker, cfg, identity):
        mocker.patch.object(fds, "load_config_and_identity", return_value=(cfg, identity))
        mocker.patch.object(fds, "read_manifest", return_value=None)
        submit = mocker.patch("console_link.cli._submit_redrive_workflow")

        result = _invoke(runner, "failed-document-stream", "redrive", "--yes")

        assert result.exit_code != 0
        assert "has not been sealed" in result.output
        assert "failed-document-stream seal" in result.output, "name the command that fixes it"
        submit.assert_not_called()

    def test_says_so_when_nothing_matches(self, runner, mocker, cfg, identity):
        mocker.patch.object(fds, "load_config_and_identity", return_value=(cfg, identity))
        mocker.patch.object(fds, "read_manifest", return_value={"collections": []})
        mocker.patch.object(fds, "plan_redrive", return_value={
            "indices": {}, "documents": [], "total": 0, "skipped_without_id": 0})
        submit = mocker.patch("console_link.cli._submit_redrive_workflow")

        result = _invoke(runner, "failed-document-stream", "redrive", "--yes")

        assert result.exit_code == 0
        assert "nothing to redrive" in result.output
        submit.assert_not_called()

    def test_json_emits_counts_and_per_document_results(self, runner, mocker, sealed):
        mocker.patch("console_link.cli._submit_redrive_workflow")

        result = _invoke(runner, "--json", "failed-document-stream", "redrive", "--dry-run")

        payload = json.loads(result.output)
        assert payload["total"] == 3
        assert payload["indices"] == {ORDERS: 2, USERS: 1}
        assert payload["dryRun"] is True
        assert payload["documents"][0]["documentId"] == "doc-1"

    def test_reports_documents_that_would_be_skipped_for_having_no_id(self, runner, mocker, cfg, identity):
        mocker.patch.object(fds, "load_config_and_identity", return_value=(cfg, identity))
        mocker.patch.object(fds, "read_manifest", return_value={"collections": []})
        mocker.patch.object(fds, "plan_redrive", return_value={
            "indices": {ORDERS: 1}, "documents": [], "total": 1, "skipped_without_id": 1})
        mocker.patch("console_link.cli._submit_redrive_workflow")

        result = _invoke(runner, "failed-document-stream", "redrive", "--dry-run")

        assert "1 document(s) have no _id and will be skipped" in result.output

    def test_opting_in_changes_what_it_says_about_documents_with_no_id(self, runner, mocker, cfg, identity):
        mocker.patch.object(fds, "load_config_and_identity", return_value=(cfg, identity))
        mocker.patch.object(fds, "read_manifest", return_value={"collections": []})
        mocker.patch.object(fds, "plan_redrive", return_value={
            "indices": {ORDERS: 1}, "documents": [], "total": 1, "skipped_without_id": 1})
        mocker.patch("console_link.cli._submit_redrive_workflow")

        result = _invoke(runner, "failed-document-stream", "redrive", "--dry-run",
                         "--allow-missing-document-ids")

        assert "may create duplicates" in result.output

    def test_json_submission_is_one_document(self, runner, mocker, sealed):
        # Must not interleave a JSON object with progress text.
        mocker.patch("console_link.cli._submit_redrive_workflow",
                     return_value={"workflowName": "redrive-1", "configSession": "default",
                                   "warnings": []})

        result = _invoke(runner, "--json", "failed-document-stream", "redrive", "--yes")

        payload = json.loads(result.output)
        assert payload["submitted"] is True
        assert payload["submission"]["workflowName"] == "redrive-1"

    def test_json_suppresses_the_submission_progress_text(self, runner, mocker, sealed):
        submit = mocker.patch("console_link.cli._submit_redrive_workflow",
                              return_value={"workflowName": "redrive-1", "configSession": "default",
                                            "warnings": []})

        _invoke(runner, "--json", "failed-document-stream", "redrive", "--yes")

        assert submit.call_args.kwargs["quiet"] is True

    def test_json_refuses_to_submit_without_an_explicit_acknowledgement(self, runner, mocker, sealed):
        # No prompt to answer, so the confirmation must be on the command line.
        submit = mocker.patch("console_link.cli._submit_redrive_workflow")

        result = _invoke(runner, "--json", "failed-document-stream", "redrive")

        assert result.exit_code != 0
        submit.assert_not_called()

    def test_json_empty_result_is_still_one_document(self, runner, mocker, cfg, identity):
        mocker.patch.object(fds, "load_config_and_identity", return_value=(cfg, identity))
        mocker.patch.object(fds, "read_manifest", return_value={"collections": []})
        mocker.patch.object(fds, "plan_redrive", return_value={
            "indices": {}, "documents": [], "total": 0, "skipped_without_id": 0})

        result = _invoke(runner, "--json", "failed-document-stream", "redrive", "--yes")

        assert json.loads(result.output)["submitted"] is False

    def test_preview_limit_caps_only_the_preview(self, runner, mocker, sealed):
        # It cannot bound a coordinated run: independent workers share no count.
        plan = mocker.patch.object(fds, "plan_redrive", return_value={
            "indices": {ORDERS: 2}, "documents": [], "total": 3, "skipped_without_id": 0})
        mocker.patch("console_link.cli._submit_redrive_workflow")

        result = _invoke(runner, "failed-document-stream", "redrive", "--yes", "--preview-limit", "1")

        assert result.exit_code == 0, result.output
        assert plan.call_args.kwargs["limit"] == 1

    def test_names_the_migration_being_redriven(self, runner, mocker, sealed):
        mocker.patch("console_link.cli._submit_redrive_workflow")

        result = _invoke(runner, "failed-document-stream", "redrive", "--dry-run")

        assert "sm-1" in result.output


class TestConfigurationProvenance:
    """A redrive must reuse the settings of the run that produced the failures."""

    def test_verifies_the_configuration_against_the_migration(self, runner, mocker, sealed, identity):
        store = mocker.patch("console_link.cli.WorkflowConfigStore")
        store.return_value.load_config.return_value = mocker.MagicMock(
            data={"snapshotMigrationConfigs": []})
        mocker.patch("console_link.workflow.services.script_runner.ScriptRunner")

        result = _invoke(runner, "failed-document-stream", "redrive", "--yes")

        assert result.exit_code != 0
        assert "declares no document backfill" in result.output

    def test_refuses_when_the_target_has_been_repointed(self, runner, mocker, sealed, identity):
        store = mocker.patch("console_link.cli.WorkflowConfigStore")
        store.return_value.load_config.return_value = mocker.MagicMock(data={
            "targetClusters": {"target": {"endpoint": "http://somewhere-else:9200"}},
            "snapshotMigrationConfigs": [{
                "fromSource": "source", "toTarget": "target",
                "perSnapshotConfig": {"snap1": [{"label": "docs", "documentBackfillConfig": {}}]},
            }],
        })
        submitter = mocker.patch("console_link.workflow.services.script_runner.ScriptRunner")

        result = _invoke(runner, "failed-document-stream", "redrive", "--yes")

        assert result.exit_code != 0
        assert "different cluster" in result.output
        submitter.return_value.submit_workflow.assert_not_called()

    def test_submits_when_the_configuration_matches(self, runner, mocker, sealed, identity):
        store = mocker.patch("console_link.cli.WorkflowConfigStore")
        store.return_value.load_config.return_value = mocker.MagicMock(data={
            "targetClusters": {"target": {"endpoint": "http://target:9200"}},
            "snapshotMigrationConfigs": [{
                "fromSource": "source", "toTarget": "target",
                "perSnapshotConfig": {"snap1": [{"label": "docs", "documentBackfillConfig": {}}]},
            }],
        })
        runner_cls = mocker.patch("console_link.workflow.services.script_runner.ScriptRunner")
        runner_cls.return_value.submit_workflow.return_value = {"workflow_name": "redrive-1"}

        result = _invoke(runner, "failed-document-stream", "redrive", "--yes")

        assert result.exit_code == 0, result.output
        submitted_yaml = runner_cls.return_value.submit_workflow.call_args.args[0]
        assert "failed-document-stream" in submitted_yaml
        assert "sourceKind" in submitted_yaml

    def test_reports_a_missing_configuration_session(self, runner, mocker, sealed):
        store = mocker.patch("console_link.cli.WorkflowConfigStore")
        store.return_value.load_config.return_value = None

        result = _invoke(runner, "failed-document-stream", "redrive", "--yes",
                         "--config-session", "nope")

        assert result.exit_code != 0
        assert "--config-session" in result.output


class TestSeal:

    def test_reports_what_the_seal_covers(self, runner, mocker, cfg):
        mocker.patch.object(fds, "load_config", return_value=cfg)
        mocker.patch.object(fds, "seal", return_value={
            "manifest": {"collections": [
                {"name": ORDERS, "partitions": [
                    {"name": "worker-a", "objectKeys": ["k1", "k2"]},
                    {"name": "worker-b", "objectKeys": ["k3"]}]}]},
            "digest": "abc123",
            "published": True,
        })

        result = _invoke(runner, "failed-document-stream", "seal")

        assert result.exit_code == 0, result.output
        assert "Sealed:" in result.output
        assert "abc123" in result.output
        assert f"{ORDERS}\t2 worker(s)\t3 object(s)" in result.output

    def test_says_when_it_was_already_sealed(self, runner, mocker, cfg):
        mocker.patch.object(fds, "load_config", return_value=cfg)
        mocker.patch.object(fds, "seal", return_value={
            "manifest": {"collections": []}, "digest": "abc123", "published": False})

        result = _invoke(runner, "failed-document-stream", "seal")

        assert "Already sealed:" in result.output

    def test_reports_a_session_that_kept_being_written(self, runner, mocker, cfg):
        mocker.patch.object(fds, "load_config", return_value=cfg)
        mocker.patch.object(fds, "seal",
                            side_effect=fds.SessionSealMismatch("A seal is permanent; copy ..."))

        result = _invoke(runner, "failed-document-stream", "seal")

        assert result.exit_code != 0
        assert "A seal is permanent" in result.output
