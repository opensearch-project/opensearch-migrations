#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

import argparse
import logging
import subprocess
import time
from subprocess import Popen
from typing import Optional, List

import requests
from prometheus_client import Metric
from prometheus_client.parser import text_string_to_metric_families

from endpoint_info import EndpointInfo
from migration_monitor_params import MigrationMonitorParams
from progress_metrics import ProgressMetrics

# Path to the Data Prepper Prometheus metrics API endpoint
# Used to monitor the progress of the migration
__METRICS_API_PATH: str = "/metrics/prometheus"
__SHUTDOWN_API_PATH: str = "/shutdown"
__DOC_SUCCESS_METRIC: str = "_opensearch_documentsSuccess"
__RECORDS_IN_FLIGHT_METRIC: str = "_BlockingBuffer_recordsInFlight"
__NO_PARTITIONS_METRIC: str = "_noPartitionsAcquired"
__IDLE_THRESHOLD: int = 5
__TIMEOUT_SECONDS: int = 5


def is_process_alive(proc: Popen) -> bool:
    return proc.returncode is None


# Gracefully shutdown a subprocess
def shutdown_process(proc: Popen) -> Optional[int]:
    # Process is still running, send SIGTERM
    proc.terminate()
    try:
        proc.wait(timeout=60)
    except subprocess.TimeoutExpired:
        if is_process_alive(proc):
            # Failed to terminate, send SIGKILL
            proc.kill()
    return proc.returncode


def shutdown_pipeline(endpoint: EndpointInfo):
    shutdown_endpoint = endpoint.add_path(__SHUTDOWN_API_PATH)
    requests.post(shutdown_endpoint, auth=endpoint.get_auth(), verify=endpoint.is_verify_ssl(),
                  timeout=__TIMEOUT_SECONDS)


def fetch_prometheus_metrics(endpoint: EndpointInfo) -> Optional[List[Metric]]:
    metrics_endpoint = endpoint.add_path(__METRICS_API_PATH)
    try:
        response = requests.get(metrics_endpoint, auth=endpoint.get_auth(), verify=endpoint.is_verify_ssl(),
                                timeout=__TIMEOUT_SECONDS)
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


def check_and_log_progress(endpoint_info: EndpointInfo, progress: ProgressMetrics) -> ProgressMetrics:
    # If the API call fails, the response is empty
    metrics = fetch_prometheus_metrics(endpoint_info)
    if metrics is not None:
        # Reset API failure counter
        progress.reset_metric_api_failure()
        success_docs = get_metric_value(metrics, __DOC_SUCCESS_METRIC)
        progress.update_records_in_flight_count(get_metric_value(metrics, __RECORDS_IN_FLIGHT_METRIC))
        progress.update_no_partitions_count(get_metric_value(metrics, __NO_PARTITIONS_METRIC))
        if success_docs is not None:
            completion_percentage = progress.update_success_doc_count(success_docs)
            progress_message: str = "Completed " + str(success_docs) + \
                                    " docs ( " + str(completion_percentage) + "% )"
            logging.info(progress_message)
            if progress.all_docs_migrated():
                logging.info("All documents migrated...")
        else:
            progress.record_success_doc_value_failure()
            logging.warning("Could not fetch progress stats from Data Prepper response, " +
                            "will retry on next polling cycle...")
    else:
        progress.record_metric_api_failure()
        logging.warning("Data Prepper metrics API call failed, will retry on next polling cycle...")
    return progress


def __should_continue_monitoring(progress: ProgressMetrics, proc: Optional[Popen] = None) -> bool:
    return not progress.is_in_terminal_state() and (proc is None or is_process_alive(proc))


def __log_migration_end_reason(progress: ProgressMetrics):  # pragma no cover
    if progress.is_migration_complete_success():
        logging.info("Migration monitor observed successful migration, shutting down...\n")
    elif progress.is_migration_idle():
        logging.warning("Migration monitor observed idle pipeline (migration may be incomplete), shutting down...")
    elif progress.is_too_may_api_failures():
        logging.warning("Migration monitor was unable to fetch migration metrics, terminating...")


# The "dp_process" parameter is optional, and signifies a local Data Prepper process
def run(args: MigrationMonitorParams, dp_process: Optional[Popen] = None, poll_interval_seconds: int = 30) -> int:
    endpoint_info = EndpointInfo(args.data_prepper_endpoint)
    progress_metrics = ProgressMetrics(args.target_count, __IDLE_THRESHOLD)
    logging.info("Starting migration monitor until target doc count: " + str(progress_metrics.get_target_doc_count()))
    while __should_continue_monitoring(progress_metrics, dp_process):
        if dp_process is not None:
            # Wait on local process
            try:
                dp_process.wait(timeout=poll_interval_seconds)
            except subprocess.TimeoutExpired:
                pass
        else:
            # Thread sleep
            time.sleep(poll_interval_seconds)
        if dp_process is None or is_process_alive(dp_process):
            progress_metrics = check_and_log_progress(endpoint_info, progress_metrics)
    # Loop terminated
    if not progress_metrics.is_in_terminal_state():
        # This will only happen for a local Data Prepper process
        logging.error("Migration did not complete, process exited with code: " + str(dp_process.returncode))
        # TODO - Implement rollback
        logging.error("Please delete any partially migrated indices before retrying the migration.")
        return dp_process.returncode
    else:
        __log_migration_end_reason(progress_metrics)
        # Shut down Data Prepper pipeline via API
        shutdown_pipeline(endpoint_info)
        if dp_process is None:
            # No local process
            return 0
        elif is_process_alive(dp_process):
            # Workaround for https://github.com/opensearch-project/data-prepper/issues/3141
            return shutdown_process(dp_process)
        else:
            return dp_process.returncode


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
    logging.info("\n##### Starting monitor tool... #####\n")
    run(MigrationMonitorParams(namespace.target_count, namespace.data_prepper_endpoint))
    logging.info("\n##### Ending monitor tool... #####\n")
