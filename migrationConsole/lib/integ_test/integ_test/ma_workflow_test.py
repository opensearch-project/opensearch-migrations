import logging
import pytest

from .test_cases.ma_argo_test_base import MATestBase


logger = logging.getLogger(__name__)


@pytest.fixture(autouse=True)
def setup_and_teardown(request, keep_workflows, test_case: MATestBase):
    #-----Setup-----
    logger.info("Performing setup...")

    #-----Execute test-----
    yield

    #-----Teardown-----
    logger.info("Performing teardown...")
    if test_case.workflow_name:
        phase_result = test_case.argo_service.is_workflow_completed(workflow_name=test_case.workflow_name)
        if not phase_result.success:
            test_case.argo_service.stop_workflow(workflow_name=test_case.workflow_name)
            test_case.argo_service.wait_for_ending_phase(workflow_name=test_case.workflow_name)
        # Print workflow details and save diagnostics if test failed
        if request.node.rep_call and request.node.rep_call.failed:
            logger.info(f"Test failed - printing workflow details for {test_case.workflow_name}")
            test_case.argo_service.print_workflow_details(workflow_name=test_case.workflow_name)
            # Save detailed diagnostics to logs directory (archived by Jenkins)
            test_case.argo_service.save_namespace_diagnostics("./logs")
        if not keep_workflows:
            test_case.argo_service.delete_workflow(workflow_name=test_case.workflow_name)


def record_test(test_case: MATestBase, record_data) -> None:
    record_data({"name": test_case.__class__.__name__, "description": test_case.description})


# The test_case parameter here is dynamically provided by the pytest_generate_tests() function in conftest.py. This
# function will add a parametrize tag on this test to provide the 'test_case' it has collected
def test_migration_assistant_workflow(record_data, test_case: MATestBase):
    logger.info(f"Performing the following test case: {test_case}")
    record_test(test_case=test_case, record_data=record_data)

    # Enable for stepping through workflows with Python debugger
    #breakpoint()

    test_case.test_before()
    test_case.import_existing_clusters()
    test_case.prepare_workflow_snapshot_and_migration_config()
    test_case.prepare_workflow_parameters()
    # For imported clusters, we should load test data before the workflow starts as we will not
    # set up clusters and suspend
    if test_case.imported_clusters:
        test_case.prepare_clusters()
    test_case.workflow_start()
    test_case.workflow_setup_clusters()
    if not test_case.imported_clusters:
        test_case.prepare_clusters()
    test_case.workflow_perform_migrations()
    test_case.display_final_cluster_state()
    test_case.verify_clusters()
    test_case.workflow_finish()
    test_case.test_after()
