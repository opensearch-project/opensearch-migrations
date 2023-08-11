import argparse
import time
from typing import Optional, List

import requests
from prometheus_client import Metric
from prometheus_client.parser import text_string_to_metric_families

from endpoint_info import EndpointInfo

__PROMETHEUS_METRICS_ENDPOINT = "/metrics/prometheus"
__SHUTDOWN_ENDPOINT = "/shutdown"
__DOC_SUCCESS_METRIC = "_opensearch_documentsSuccess"
__RECORDS_IN_FLIGHT_METRIC = "_BlockingBuffer_recordsInFlight"
__NO_PARTITIONS_METRIC = "_noPartitionsAcquired"


def shutdown_pipeline(endpoint: EndpointInfo):
    shutdown_endpoint = endpoint.url + __SHUTDOWN_ENDPOINT
    requests.post(shutdown_endpoint, auth=endpoint.auth, verify=endpoint.verify_ssl)


def fetch_prometheus_metrics(endpoint: EndpointInfo) -> List[Metric]:
    metrics_endpoint = endpoint.url + __PROMETHEUS_METRICS_ENDPOINT
    response = requests.get(metrics_endpoint, auth=endpoint.auth, verify=endpoint.verify_ssl)
    # Based on response headers defined in Data Prepper's PrometheusMetricsHandler.java class
    metrics = response.content.decode('utf-8')
    # Collect generator return values into list
    return list(text_string_to_metric_families(metrics))


def get_metric_value(metric_families: List, metric_suffix: str) -> Optional[int]:
    for metric_family in metric_families:
        if metric_family.name.endswith(metric_suffix):
            return int(metric_family.samples[0].value)
    return None


def check_if_complete(doc_count: Optional[int], in_flight: Optional[int], no_part_count: Optional[int],
                      prev_no_part_count: int, target: int) -> bool:
    # Check for target doc_count
    # TODO Add a check for partitionsCompleted = indices
    if doc_count is not None and doc_count >= target:
        # Check for idle pipeline
        if in_flight is not None and in_flight == 0:
            # No-partitions metrics should steadily tick up
            if no_part_count is not None and no_part_count > prev_no_part_count > 0:
                return True
    return False


def run(args: argparse.Namespace, wait_seconds: int) -> None:
    # TODO Remove hardcoded EndpointInfo
    default_auth = ('admin', 'admin')
    endpoint = EndpointInfo(args.dp_endpoint, default_auth, False)
    prev_no_partitions_count = 0
    terminal = False
    target_doc_count = int(args.target_count)
    while not terminal:
        metrics = fetch_prometheus_metrics(endpoint)
        success_docs = get_metric_value(metrics, __DOC_SUCCESS_METRIC)
        rec_in_flight = get_metric_value(metrics, __RECORDS_IN_FLIGHT_METRIC)
        no_partitions_count = get_metric_value(metrics, __NO_PARTITIONS_METRIC)
        terminal = check_if_complete(success_docs, rec_in_flight, no_partitions_count,
                                     prev_no_partitions_count, target_doc_count)
        if not terminal:
            # Save no_partitions_count
            prev_no_partitions_count = no_partitions_count
            time.sleep(wait_seconds)
    # Loop terminated, shut down the Data Prepper pipeline
    shutdown_pipeline(endpoint)


if __name__ == '__main__':  # pragma no cover
    # Set up parsing for command line arguments
    arg_parser = argparse.ArgumentParser(
        prog="python monitor.py",
        description="Monitoring process for a running Data Prepper pipeline.\n" +
                    "The first input is the Data Prepper URL endpoint.\n" +
                    "The second input is the target doc_count for termination.",
        formatter_class=argparse.RawTextHelpFormatter
    )
    # Required positional arguments
    arg_parser.add_argument(
        "dp_endpoint",
        help="URL endpoint for the running Data Prepper process"
    )
    arg_parser.add_argument(
        "target_count",
        help="Target doc_count to reach, after which the Data Prepper pipeline will be terminated"
    )
    cli_args = arg_parser.parse_args()
    print("\n##### Starting monitor tool... #####\n")
    run(cli_args, 30)
    print("\n##### Ending monitor tool... #####\n")
