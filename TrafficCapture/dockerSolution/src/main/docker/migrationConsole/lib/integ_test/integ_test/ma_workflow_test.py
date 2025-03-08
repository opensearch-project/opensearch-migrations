import logging
import pytest
from typing import List

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
    # TODO what if kafka is not provided
    kafka: Kafka = test_case.console_link_env.kafka

    # Clear cluster data
    clear_cluster(source_cluster)
    clear_cluster(target_cluster)

    # Delete existing Kafka topic to clear records
    delete_topic(kafka=kafka, topic_name="logging-traffic-topic")

    #-----Execute test-----
    yield

    #-----Teardown-----
    logger.info("\nTearing down resources...")
    backfill: Backfill = test_case.console_link_env.backfill
    backfill.stop()

    replayer: Replayer = test_case.console_link_env.replay
    replayer.stop()



# The test_cases parameter here is dynamically provided by the pytest_generate_tests() function in conftest.py. This
# function will add a parametrize tag on this test to provide the 'test_cases' it has collected
def test_migration_assistant_workflow(test_cases: List[MATestBase]):
    logger.info(f"Performing the following test cases: {test_cases}")
    control_test_case = test_cases[0]

    for case in test_cases:
        case.perform_initial_operations()

    control_test_case.perform_snapshot_create()

    for case in test_cases:
        case.perform_operations_after_snapshot()

    control_test_case.perform_metadata_migration()

    for case in test_cases:
        case.perform_operations_after_metadata_migration()

    control_test_case.start_backfill_migration()

    for case in test_cases:
        case.perform_operations_during_backfill_migration()

    control_test_case.stop_backfill_migration()

    for case in test_cases:
        case.perform_operations_after_backfill_migration()

    control_test_case.start_live_capture_migration()

    for case in test_cases:
        case.perform_operations_during_live_capture_migration()

    control_test_case.stop_live_capture_migration()

    for case in test_cases:
        case.perform_operations_after_live_capture_migration()

    for case in test_cases:
        case.perform_final_operations()

