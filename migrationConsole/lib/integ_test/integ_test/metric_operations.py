import functools
import json
import logging
import subprocess
import time
import boto3
from botocore.config import Config as BotoConfig
from botocore.exceptions import ConnectTimeoutError, EndpointConnectionError
import pytest
from datetime import datetime, timedelta, timezone
from typing import List, Dict, Tuple
from unittest import TestCase
from console_link.models.metrics_source import MetricsSource, CloudwatchMetricsSource
from console_link.middleware.metrics import get_metric_data
from console_link.models.utils import create_boto3_client, raise_for_aws_api_error

logger = logging.getLogger(__name__)
CW_NAMESPACE = "OpenSearchMigrations"


# Note that the names of metrics are a bit different in a local vs cloud deployment.
# The transformation is somewhat hardcoded here--the user should put in the local name, and if its
# a cloud deployment, everything after the first `_` will be discarded. This should generally cause
# things to match, but it's possible there are edge cases that it doesn't account for
# Note as well, that currently the only way of assuming data is correlated with a given test is via
# the lookback time. Soon, we should implement a way to add a specific ID to metrics from a given run
# and check for the presence of that ID.
def assert_metric_has_data(component: str, metric: str, lookback_minutes: int, test_case: TestCase):
    metrics_source: MetricsSource = pytest.console_env.metrics_source

    metric_data: List[Tuple[str, float]] = get_metric_data(metrics_source=metrics_source,
                                                           component=component,
                                                           metric_name=metric,
                                                           statistic="Sum",
                                                           lookback=lookback_minutes)
    logger.info(f"Received the following data from get_metric_data: {metric_data}")
    test_case.assertNotEqual(
        len(metric_data), 0,
        f"Metric {metric} for component {component} does not exist or does "
        f"not have data within the last {lookback_minutes} minutes"
    )


def assert_metrics(expected_metrics: Dict[str, List[str]], test_case: TestCase, deployment_type: str,
                   lookback_minutes=2, wait_before_check_seconds=60):
    """
    This is the method invoked by the `@assert_metrics_present` decorator.
    params:
        expected_metrics: a dictionary of component->[metrics], for each metric that should be verified.
        lookback_minutes: the number of minutes into the past to query for metrics
        wait_before_check_seconds: the time in seconds to delay before checking for the presence of metrics
    """
    logger.debug(f"Waiting {wait_before_check_seconds} before checking for metrics.")
    time.sleep(wait_before_check_seconds)
    for component, expected_comp_metrics in expected_metrics.items():
        if component == "captureProxy" and deployment_type == "cloud":
            # We currently do not emit captureProxy metrics from a non-standalone proxy, which is the scenario
            # tested in our e2e tests. Therefore, we don't want to assert metrics exist in this situation. We
            # should remove this clause as soon as we start testing the standalone proxy scenario.
            logger.warning("Skipping metric verification for captureProxy metrics in a cloud deployment.")
            continue
        for expected_metric in expected_comp_metrics:
            if deployment_type == 'cloud':
                expected_metric = expected_metric.split('_', 1)[0]
            assert_metric_has_data(component, expected_metric, lookback_minutes, test_case)


def assert_metrics_present(*wrapper_args, **wrapper_kwargs):
    def decorator(test_func):
        @functools.wraps(test_func)
        def wrapper(self, *args, **kwargs):
            # Run the original test function
            try:
                test_func(self, *args, **kwargs)
                test_passed = True
            except AssertionError as e:
                test_passed = False
                raise e
            finally:
                if test_passed:
                    metrics_source: MetricsSource = pytest.console_env.metrics_source
                    if metrics_source is None:
                        logger.warning("No metrics_source configured; skipping metric assertions.")
                    else:
                        deployment_type = "docker"
                        if isinstance(metrics_source, CloudwatchMetricsSource):
                            deployment_type = "cloud"
                        # Only look for metrics if the test passed
                        assert_metrics(test_case=self, deployment_type=deployment_type, *wrapper_args, **wrapper_kwargs)
        return wrapper
    return decorator


def _load_aws_metadata(namespace: str = "ma"):
    result = subprocess.run(
        ["kubectl", "-n", namespace, "get", "configmap", "aws-metadata", "-o", "json"],
        capture_output=True,
        text=True,
        timeout=30
    )
    if result.returncode != 0:
        logger.info("No aws-metadata ConfigMap found; skipping CloudWatch metric assertion for local/non-EKS run.")
        return None
    return json.loads(result.stdout).get("data", {})


def _find_metric_dimensions(client, metric_name: str, required_dimensions: Dict[str, str]):
    response = client.list_metrics(
        Namespace=CW_NAMESPACE,
        MetricName=metric_name,
        RecentlyActive="PT3H"
    )
    raise_for_aws_api_error(response)
    for metric in response.get("Metrics", []):
        dimensions = {d["Name"]: d["Value"] for d in metric.get("Dimensions", [])}
        if all(dimensions.get(k) == v for k, v in required_dimensions.items()):
            return metric.get("Dimensions", [])
    return None


def _metric_has_recent_data(client, metric_name: str, dimensions, lookback_minutes: int) -> bool:
    end_time = datetime.now(timezone.utc)
    response = client.get_metric_data(
        MetricDataQueries=[
            {
                "Id": "metric0",
                "MetricStat": {
                    "Metric": {
                        "Namespace": CW_NAMESPACE,
                        "MetricName": metric_name,
                        "Dimensions": dimensions,
                    },
                    "Period": 60,
                    "Stat": "Sum",
                },
            },
        ],
        StartTime=end_time - timedelta(minutes=lookback_minutes),
        EndTime=end_time,
        ScanBy="TimestampAscending",
    )
    raise_for_aws_api_error(response)
    results = response.get("MetricDataResults", [])
    return bool(results and results[0].get("Timestamps"))


def _any_candidate_has_recent_data(client, candidates, lookback_minutes: int) -> bool:
    for metric_name, required_dimensions in candidates:
        dimensions = _find_metric_dimensions(client, metric_name, required_dimensions)
        if dimensions and _metric_has_recent_data(client, metric_name, dimensions, lookback_minutes):
            logger.info("Found CloudWatch metric data for %s with dimensions %s", metric_name, dimensions)
            return True
    return False


def assert_cloudwatch_capture_replay_metrics_for_workflow_run(
        namespace: str = "ma",
        lookback_minutes: int = 20,
        attempts: int = 5,
        wait_seconds: int = 60):
    aws_metadata = _load_aws_metadata(namespace)
    if not aws_metadata:
        return

    region = aws_metadata.get("AWS_REGION")
    qualifier = aws_metadata.get("STAGE_NAME")
    if not region or not qualifier:
        raise AssertionError(f"aws-metadata ConfigMap is missing AWS_REGION or STAGE_NAME: {aws_metadata}")

    # Add a connect timeout to fail fast in case connection is not available
    # (e.g. isolated VPC without proper VPC endpoint).
    client = boto3.client(
        "cloudwatch",
        region_name=region,
        config=BotoConfig(connect_timeout=10, read_timeout=10, retries={"max_attempts": 1})
    )
    app_metric_candidates = [
        ("bytesSent", {"qualifier": qualifier, "OTelLib": "documentMigration"}),
        ("kafkaCommitCount", {"qualifier": qualifier, "OTelLib": "replayer"}),
        ("bytesRead", {"qualifier": qualifier, "OTelLib": "captureProxy"}),
    ]
    for attempt in range(1, attempts + 1):
        try:
            app_metric_found = _any_candidate_has_recent_data(client, app_metric_candidates, lookback_minutes)
        except (ConnectTimeoutError, EndpointConnectionError, OSError) as e:
            logger.warning(
                "CloudWatch Metrics API unreachable (likely no VPC endpoint for 'monitoring' in isolated VPC); "
                "skipping metric assertion. Error: %s", e
            )
            return
        if app_metric_found:
            return
        if attempt < attempts:
            logger.info(
                "CloudWatch capture/replay app metrics not visible yet (attempt %s/%s, app=%s); waiting %ss",
                attempt,
                attempts,
                app_metric_found,
                wait_seconds
            )
            time.sleep(wait_seconds)

    raise AssertionError(
        "Expected CloudWatch capture/replay app metrics were not found in namespace "
        f"{CW_NAMESPACE} for qualifier {qualifier} within the last {lookback_minutes} minutes"
    )
