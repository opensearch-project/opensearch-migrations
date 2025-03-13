import logging
import pytest
import shutil
from typing import Callable, List

from .test_cases.ma_test_base import MATestBase
from console_link.middleware.clusters import connection_check, clear_cluster, ConnectionResult
from console_link.models.backfill_base import Backfill
from console_link.models.replayer_base import Replayer
from console_link.models.kafka import Kafka
from console_link.middleware.kafka import delete_topic

logger = logging.getLogger(__name__)


@pytest.fixture(autouse=True)
def setup_and_teardown(request, test_cases: List[MATestBase]):
    #-----Setup-----
    logger.info(f"Performing setup...")
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
        logger.info(f"No transformation files detected to cleanup")

    # Clear cluster data
    clear_cluster(source_cluster)
    clear_cluster(target_cluster)

    # Delete existing Kafka topic to clear records
    delete_topic(kafka=kafka, topic_name="logging-traffic-topic")

    #-----Execute test-----
    yield

    #-----Teardown-----
    logger.info("\nTearing down resources...")
    try:
        backfill: Backfill = test_case.console_link_env.backfill
        backfill.stop()
    except Exception as e:
        logger.error(f"Error encountered when stopping backfill, resources may not have been cleaned up: {e}")
    try:
        replayer: Replayer = test_case.console_link_env.replay
        replayer.stop()
    except Exception as e:
        logger.error(f"Error encountered when stopping replayer, resources may not have been cleaned up: {e}")


def run_on_all_cases(test_cases: List, operation: Callable) -> None:
    for tc in test_cases:
        operation(tc)

# The test_cases parameter here is dynamically provided by the pytest_generate_tests() function in conftest.py. This
# function will add a parametrize tag on this test to provide the 'test_cases' it has collected
def test_migration_assistant_workflow(test_cases: List[MATestBase]):
    logger.info(f"Performing the following test cases: {test_cases}")
    control_test_case = test_cases[0]

    breakpoint()

    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.perform_initial_operations())
    control_test_case.perform_snapshot_create()
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.perform_operations_after_snapshot())
    control_test_case.perform_metadata_migration()
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.perform_operations_after_metadata_migration())
    control_test_case.start_backfill_migration()
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.perform_operations_during_backfill_migration())
    control_test_case.stop_backfill_migration()
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.perform_operations_after_backfill_migration())
    control_test_case.start_live_capture_migration()
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.perform_operations_during_live_capture_migration())
    control_test_case.stop_live_capture_migration()
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.perform_operations_after_live_capture_migration())
    run_on_all_cases(test_cases=test_cases, operation=lambda case: case.perform_final_operations())

