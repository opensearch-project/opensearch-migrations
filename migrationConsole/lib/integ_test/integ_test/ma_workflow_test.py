import logging
import subprocess

import pytest

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
            logger.warning("workflow reset exited with code %d", result.returncode)
    except subprocess.TimeoutExpired:
        logger.warning("workflow reset timed out after 300s")
    except FileNotFoundError:
        logger.warning("'workflow' CLI not found on PATH, skipping CRD reset")


@pytest.fixture(autouse=True)
def setup_and_teardown(request, keep_workflows, test_case: MATestBase):
    #-----Setup-----
    logger.info("Performing setup...")

    #-----Execute test-----
    yield

    #-----Teardown-----
    logger.info("Performing teardown...")
    if test_case.workflow_name:
        status_result = test_case.argo_service.get_workflow_status(workflow_name=test_case.workflow_name)
        if status_result.value.get("phase", "") not in ("Succeeded", "Failed", "Error", "Stopped", "Terminated"):
            test_case.argo_service.stop_workflow(workflow_name=test_case.workflow_name)
            test_case.argo_service.wait_for_ending_phase(workflow_name=test_case.workflow_name)
        # Print workflow details and save diagnostics if test failed
        if request.node.rep_call and request.node.rep_call.failed:
            logger.info(f"Test failed - printing workflow details for {test_case.workflow_name}")
            test_case.argo_service.print_workflow_details(workflow_name=test_case.workflow_name)
            test_case.argo_service.save_namespace_diagnostics("./logs", workflow_name=test_case.workflow_name)
        if not keep_workflows:
            test_case.argo_service.delete_workflow(workflow_name=test_case.workflow_name)
    # Reset all migration CRDs before test-specific cleanup
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
