import logging
import pytest
import shutil
from typing import Callable, List

from .common_utils import wait_for_service_status
from .test_cases.ma_test_base import MATestBase
from console_link.middleware.clusters import connection_check, clear_cluster, clear_indices, ConnectionResult
from console_link.models.backfill_base import Backfill, BackfillStatus
from console_link.models.replayer_base import Replayer, ReplayStatus
from console_link.models.kafka import Kafka
from console_link.middleware.kafka import delete_topic

logger = logging.getLogger(__name__)


@pytest.fixture(autouse=True)
def setup_and_teardown(request, test_cases: List[MATestBase]):
    #-----Setup-----
    logger.info("Performing setup...")
    test_case = test_cases[0]
    source_cluster = test_case.source_cluster
    target_cluster = test_case.target_cluster
    source_con_result: ConnectionResult = connection_check(source_cluster)
    assert source_con_result.connection_established is True
    target_con_result: ConnectionResult = connection_check(target_cluster)
    assert target_con_result.connection_established is True
    kafka: Kafka = test_case.console_link_env.kafka

    # Cleanup generated transformation files
    try:
        shutil.rmtree("/shared-logs-output/test-transformations")
        logger.info("Removed existing /shared-logs-output/test-transformations directory")
    except FileNotFoundError:
        logger.info("No transformation files detected to cleanup")

    # Clear cluster data
    clear_cluster(source_cluster)
    clear_indices(target_cluster)
    test_case.target_operations.clear_index_templates(cluster=target_cluster)

    # Delete existing Kafka topic to clear records
    delete_topic(kafka=kafka, topic_name="logging-traffic-topic")

    #-----Execute test-----
    yield

    #-----Teardown-----
    logger.info("\nTearing down resources...")
    try:
        backfill: Backfill = test_case.console_link_env.backfill
        backfill.stop()
        wait_for_service_status(status_func=lambda: backfill.get_status(), desired_status=BackfillStatus.STOPPED)
    except Exception as e:
        logger.error(f"Error encountered when stopping backfill, resources may not have been cleaned up: {e}")
    try:
        replayer: Replayer = test_case.console_link_env.replay
        replayer.stop()
        wait_for_service_status(status_func=lambda: replayer.get_status(), desired_status=ReplayStatus.STOPPED)
    except Exception as e:
        logger.error(f"Error encountered when stopping replayer, resources may not have been cleaned up: {e}")


def record_tests(test_cases: List, record_data) -> None:
    for tc in test_cases:
        record_data({"name": tc.__class__.__name__, "description": tc.description})


def run_on_all_cases(test_cases: List, operation: Callable) -> None:
    for tc in test_cases:
        operation(tc)


# The test_cases parameter here is dynamically provided by the pytest_generate_tests() function in conftest.py. This
# function will add a parametrize tag on this test to provide the 'test_cases' it has collected
def test_migration_assistant_workflow(record_data, test_cases: List[MATestBase]):
    logger.info(f"Performing the following test cases: {test_cases}")
    record_tests(test_cases=test_cases, record_data=record_data)
    control_test_case = test_cases[0]

    # Enable for stepping through workflows with Python debugger
    #breakpoint()

    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.test_before())
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.snapshot_before())
    control_test_case.snapshot_create()
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.snapshot_after())
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.metadata_before())
    control_test_case.metadata_migrate()
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.metadata_after())
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.backfill_before())
    control_test_case.backfill_start()
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.backfill_during())
    control_test_case.backfill_wait_for_stop()
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.backfill_after())
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.replay_before())
    control_test_case.replay_start()
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.replay_during())
    control_test_case.replay_wait_for_stop()
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.replay_after())
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.test_after())
