import logging
import subprocess

import pytest

from console_link.workflow.commands.crd_utils import list_migration_resources, resource_display_name
from console_link.workflow.models.utils import load_k8s_config

from .test_cases.ma_argo_test_base import MATestBase


logger = logging.getLogger(__name__)


def _run_workflow_reset(namespace: str = "ma"):
    """Run 'workflow reset --all --include-proxies --delete-storage' to delete all migration CRDs.

    The --delete-storage flag ensures Kafka PVCs are cleaned up between consecutive
    test runs (e.g. --test-ids=0031,0040), preventing cluster ID conflicts.
    """
    cmd = ["workflow", "reset", "--all", "--include-proxies", "--delete-storage", "--namespace", namespace]
    logger.info("Running workflow reset: %s", " ".join(cmd))
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        if result.stdout:
            logger.info("workflow reset stdout:\n%s", result.stdout)
        if result.stderr:
            logger.warning("workflow reset stderr:\n%s", result.stderr)
        if result.returncode != 0:
            pytest.fail(f"workflow reset exited with code {result.returncode}")
    except subprocess.TimeoutExpired:
        pytest.fail("workflow reset timed out after 300s")
    except FileNotFoundError:
        pytest.fail("'workflow' CLI not found on PATH; cannot reset migration CRDs")


def _fail_if_migration_resources_exist(namespace: str = "ma"):
    """Fail before a test starts if a previous case left migration resources behind."""
    try:
        load_k8s_config()
        resources = list_migration_resources(namespace)
    except Exception as e:
        pytest.fail(f"Unable to verify clean migration-resource state before test: {e}")

    if not resources:
        return

    formatted = [resource_display_name(plural, name) for plural, name, _, _ in resources]
    remaining_resources = ", ".join(formatted)
    pytest.fail(
        "Migration resources already exist before test starts; a previous workflow reset likely failed. "
        f"Remaining resources in namespace {namespace}: {remaining_resources}"
    )


@pytest.fixture(autouse=True)
def setup_and_teardown(
    request,
    keep_workflows,
    skip_workflow_reset,
    dump_all_workflow_output_artifacts,
    test_case: MATestBase,
):
    #-----Setup-----
    logger.info("Performing setup...")
    _fail_if_migration_resources_exist()

    #-----Execute test-----
    yield

    #-----Teardown-----
    logger.info("Performing teardown...")
    if test_case.workflow_name:
        status_result = test_case.argo_service.get_workflow_status(workflow_name=test_case.workflow_name)
        if status_result.value.get("phase", "") not in ("Succeeded", "Failed", "Error", "Stopped", "Terminated"):
            test_case.argo_service.stop_workflow(workflow_name=test_case.workflow_name)
            test_case.argo_service.wait_for_ending_phase(workflow_name=test_case.workflow_name)
        # On success the full workflow-status JSON and migration-resource YAML are just noise.
        # Only dump them (along with the heavier details/diagnostics) when the test failed.
        if request.node.rep_call and request.node.rep_call.failed:
            logger.info(f"Test failed - printing workflow details for {test_case.workflow_name}")
            test_case.argo_service.print_workflow_status(workflow_name=test_case.workflow_name)
            test_case.argo_service.print_migration_resource_status()
            test_case.argo_service.print_workflow_details(workflow_name=test_case.workflow_name)
            test_case.argo_service.print_namespace_diagnostics(
                workflow_name=test_case.workflow_name,
                include_all_workflow_output_artifacts=dump_all_workflow_output_artifacts,
            )
            test_case.argo_service.save_namespace_diagnostics(
                "./logs",
                workflow_name=test_case.workflow_name,
                include_all_workflow_output_artifacts=dump_all_workflow_output_artifacts,
            )
        if not keep_workflows:
            test_case.argo_service.delete_workflow(workflow_name=test_case.workflow_name)
    # Reset all migration CRDs before test-specific cleanup unless the outer runner is preserving the run.
    if not skip_workflow_reset:
        _run_workflow_reset()
    test_case.cleanup()


def record_test(test_case: MATestBase, record_data) -> None:
    record_data({"name": test_case.__class__.__name__, "description": test_case.description})


# The test_case parameter here is dynamically provided by the pytest_generate_tests() function in conftest.py. This
# function will add a parametrize tag on this test to provide the 'test_case' it has collected
def test_migration_assistant_workflow(record_data, keep_workflows, test_case: MATestBase):
    logger.info(f"Performing the following test case: {test_case}")
    record_test(test_case=test_case, record_data=record_data)

    # Enable for stepping through workflows with Python debugger
    #breakpoint()

    # Test lifecycle:
    #   prepare_clusters        → seed test data on source cluster
    #   workflow_start           → submit Argo workflow
    #   workflow_perform_migrations → wait for migrations to complete (or replayer ready for CDC)
    #   post_migration_actions   → hook for CDC: enable capture, send traffic through proxy
    #   verify_clusters          → assert expected docs on target
    #   test_after               → assert workflow phase (overridden by CDC to skip)
    test_case.test_before()
    test_case.import_existing_clusters()
    test_case.prepare_workflow_snapshot_and_migration_config()
    test_case.prepare_workflow_parameters(keep_workflows=keep_workflows)
    # For imported clusters, we should load test data before the workflow starts as we will not
    # set up clusters and suspend
    if test_case.imported_clusters:
        test_case.prepare_clusters()
    test_case.workflow_start()
    test_case.workflow_setup_clusters()
    if not test_case.imported_clusters:
        test_case.prepare_clusters()
    test_case.workflow_perform_migrations()
    test_case.post_migration_actions()
    test_case.display_final_cluster_state()
    test_case.verify_clusters()
    test_case.workflow_finish()
    test_case.test_after()
