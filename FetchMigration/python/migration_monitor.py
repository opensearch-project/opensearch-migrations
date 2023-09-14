import argparse
import logging
import time
from typing import Optional, List
import math

import requests
from prometheus_client import Metric
from prometheus_client.parser import text_string_to_metric_families

from endpoint_info import EndpointInfo
from migration_monitor_params import MigrationMonitorParams

__PROMETHEUS_METRICS_ENDPOINT = "/metrics/prometheus"
__SHUTDOWN_ENDPOINT = "/shutdown"
__DOC_SUCCESS_METRIC = "_opensearch_documentsSuccess"
__RECORDS_IN_FLIGHT_METRIC = "_BlockingBuffer_recordsInFlight"
__NO_PARTITIONS_METRIC = "_noPartitionsAcquired"


def shutdown_pipeline(endpoint: EndpointInfo):
    shutdown_endpoint = endpoint.url + __SHUTDOWN_ENDPOINT
    requests.post(shutdown_endpoint, auth=endpoint.auth, verify=endpoint.verify_ssl)


def fetch_prometheus_metrics(endpoint: EndpointInfo) -> Optional[List[Metric]]:
    metrics_endpoint = endpoint.url + __PROMETHEUS_METRICS_ENDPOINT
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


def run(args: MigrationMonitorParams, wait_seconds: int = 30) -> None:
    endpoint = EndpointInfo(args.data_prepper_endpoint)
    target_doc_count: int = args.target_count
    prev_no_partitions_count = 0
    terminal = False
    logging.info("Starting migration monitor until target doc count: " + str(target_doc_count))
    while not terminal:
        time.sleep(poll_interval_seconds)
        # If the API call fails, the response is empty
        metrics = fetch_prometheus_metrics(endpoint)
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
                logging.info("Could not fetch metrics from Data Prepper, will retry on next polling cycle...")
            terminal = check_if_complete(success_docs, rec_in_flight, no_partitions_count,
                                         prev_no_partitions_count, target_doc_count)
            if not terminal:
                # Save no_partitions_count
                prev_no_partitions_count = no_partitions_count
    # Loop terminated, shut down the Data Prepper pipeline
    logging.info("Migration monitor observed successful migration and idle pipeline, shutting down...\n")
    shutdown_pipeline(endpoint)


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
