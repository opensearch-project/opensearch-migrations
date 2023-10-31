import argparse
import logging
import math
import subprocess
import time
from subprocess import Popen
from typing import Optional, List

import requests
from prometheus_client import Metric
from prometheus_client.parser import text_string_to_metric_families

from endpoint_info import EndpointInfo
from migration_monitor_params import MigrationMonitorParams

# Path to the Data Prepper Prometheus metrics API endpoint
# Used to monitor the progress of the migration
__METRICS_API_PATH = "/metrics/prometheus"
__SHUTDOWN_API_PATH = "/shutdown"
__DOC_SUCCESS_METRIC = "_opensearch_documentsSuccess"
__RECORDS_IN_FLIGHT_METRIC = "_BlockingBuffer_recordsInFlight"
__NO_PARTITIONS_METRIC = "_noPartitionsAcquired"


# Gracefully shutdown a subprocess
def shutdown_process(proc: Popen) -> Optional[int]:
    # Process is still running, send SIGTERM
    proc.terminate()
    try:
        proc.wait(timeout=60)
    except subprocess.TimeoutExpired:
        if proc.returncode is None:
            # Failed to terminate, send SIGKILL
            proc.kill()
    return proc.returncode


def shutdown_pipeline(endpoint: EndpointInfo):
    shutdown_endpoint = endpoint.url + __SHUTDOWN_API_PATH
    requests.post(shutdown_endpoint, auth=endpoint.auth, verify=endpoint.verify_ssl)


def fetch_prometheus_metrics(endpoint: EndpointInfo) -> Optional[List[Metric]]:
    metrics_endpoint = endpoint.url + __METRICS_API_PATH
    try:
        response = requests.get(metrics_endpoint, auth=endpoint.auth, verify=endpoint.verify_ssl)
        response.raise_for_status()
    except requests.exceptions.RequestException:
        return None
    # Based on response headers defined in Data Prepper's PrometheusMetricsHandler.java class
    metrics = response.content.decode('utf-8')
    # Collect generator return values into list
    return list(text_string_to_metric_families(metrics))


def get_metric_value(metric_families: List, metric_suffix: str) -> Optional[int]:
    for metric_family in metric_families:
        if metric_family.name.endswith(metric_suffix):
            return int(metric_family.samples[0].value)
    return None


def check_if_complete(doc_count: Optional[int], in_flight: Optional[int], no_partition_count: Optional[int],
                      prev_no_partition: int, target: int) -> bool:
    # Check for target doc_count
    # TODO Add a check for partitionsCompleted = indices
    if doc_count is not None and doc_count >= target:
        # Check for idle pipeline
        logging.info("Target doc count reached, checking for idle pipeline...")
        # Debug metrics
        if logging.getLogger().isEnabledFor(logging.DEBUG):  # pragma no cover
            debug_msg_template: str = "Idle pipeline metrics - " + \
                "Records in flight: [{0}], " + \
                "No-partitions counter: [{1}]" + \
                "Previous no-partition value: [{2}]"
            logging.debug(debug_msg_template.format(in_flight, no_partition_count, prev_no_partition))

        if in_flight is not None and in_flight == 0:
            # No-partitions metrics should steadily tick up
            if no_partition_count is not None and no_partition_count > prev_no_partition > 0:
                return True
    return False


def check_and_log_progress(endpoint_info: EndpointInfo, target_doc_count: int, prev_no_partitions_count: int) -> \
        tuple[bool, int]:
    terminal: bool = False
    # If the API call fails, the response is empty
    metrics = fetch_prometheus_metrics(endpoint_info)
    if metrics is not None:
        success_docs = get_metric_value(metrics, __DOC_SUCCESS_METRIC)
        rec_in_flight = get_metric_value(metrics, __RECORDS_IN_FLIGHT_METRIC)
        no_partitions_count = get_metric_value(metrics, __NO_PARTITIONS_METRIC)
        if success_docs is not None:  # pragma no cover
            completion_percentage: int = math.floor((success_docs * 100) / target_doc_count)
            progress_message: str = "Completed " + str(success_docs) + \
                                    " docs ( " + str(completion_percentage) + "% )"
            logging.info(progress_message)
        else:
            logging.warning("Could not fetch progress stats from Data Prepper response, " +
                            "will retry on next polling cycle...")
        terminal = check_if_complete(success_docs, rec_in_flight, no_partitions_count, prev_no_partitions_count,
                                     target_doc_count)
        if not terminal:
            # Save no_partitions_count
            prev_no_partitions_count = no_partitions_count
    else:
        logging.warning("Data Prepper metrics API call failed, will retry on next polling cycle...")
    # TODO - Handle idle non-terminal pipeline
    return terminal, prev_no_partitions_count


def monitor_local(args: MigrationMonitorParams, dp_process: Popen, poll_interval_seconds: int = 30) -> Optional[int]:
    endpoint_info = EndpointInfo(args.data_prepper_endpoint)
    target_doc_count: int = args.target_count
    # Counter to track the no_partition_count metric
    no_partition_count: int = 0
    is_migration_complete = False
    logging.info("Starting migration monitor until target doc count: " + str(target_doc_count))
    # Sets returncode. A value of None means the subprocess has not yet terminated
    dp_process.poll()
    while dp_process.returncode is None and not is_migration_complete:
        try:
            dp_process.wait(timeout=poll_interval_seconds)
        except subprocess.TimeoutExpired:
            pass
        if dp_process.returncode is None:
            is_migration_complete, no_partition_count = check_and_log_progress(
                endpoint_info, target_doc_count, no_partition_count)
    # Loop terminated
    if not is_migration_complete:
        logging.error("Migration did not complete, process exited with code: " + str(dp_process.returncode))
        # TODO - Implement rollback
        logging.error("Please delete any partially migrated indices before retrying the migration.")
        return dp_process.returncode
    else:
        # Shut down Data Prepper pipeline via API
        logging.info("Migration monitor observed successful migration and idle pipeline, shutting down...\n")
        shutdown_pipeline(endpoint_info)
        if dp_process.returncode is None:
            # Workaround for https://github.com/opensearch-project/data-prepper/issues/3141
            return shutdown_process(dp_process)
        else:
            return dp_process.returncode


def run(args: MigrationMonitorParams, poll_interval_seconds: int = 30) -> None:
    endpoint_info = EndpointInfo(args.data_prepper_endpoint)
    target_doc_count: int = args.target_count
    # Counter to track the no_partition_count metric
    no_partition_count: int = 0
    is_migration_complete = False
    logging.info("Starting migration monitor until target doc count: " + str(target_doc_count))
    while not is_migration_complete:
        time.sleep(poll_interval_seconds)
        is_migration_complete, no_partition_count = check_and_log_progress(
            endpoint_info, target_doc_count, no_partition_count)
    # Loop terminated, shut down the Data Prepper pipeline
    logging.info("Migration monitor observed successful migration and idle pipeline, shutting down...\n")
    shutdown_pipeline(endpoint_info)


if __name__ == '__main__':  # pragma no cover
    # Set up parsing for command line arguments
    arg_parser = argparse.ArgumentParser(
        prog="python monitor.py",
        description="""Monitoring process for a running Data Prepper pipeline.
        The first input is the Data Prepper URL endpoint.
        The second input is the target doc_count for termination.""",
        formatter_class=argparse.RawTextHelpFormatter
    )
    # Required positional arguments
    arg_parser.add_argument(
        "data_prepper_endpoint",
        help="URL endpoint for the running Data Prepper process"
    )
    arg_parser.add_argument(
        "target_count",
        type=int,
        help="Target doc_count to reach, after which the Data Prepper pipeline will be terminated"
    )
    namespace = arg_parser.parse_args()
    print("\n##### Starting monitor tool... #####\n")
    run(MigrationMonitorParams(namespace.target_count, namespace.data_prepper_endpoint))
    print("\n##### Ending monitor tool... #####\n")
