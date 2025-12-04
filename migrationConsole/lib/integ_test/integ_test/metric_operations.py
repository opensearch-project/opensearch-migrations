import functools
import logging
import time
import pytest
from typing import List, Dict, Tuple
from unittest import TestCase
from console_link.models.metrics_source import MetricsSource, CloudwatchMetricsSource
from console_link.middleware.metrics import get_metric_data

logger = logging.getLogger(__name__)


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
                    deployment_type = "docker"
                    if isinstance(metrics_source, CloudwatchMetricsSource):
                        deployment_type = "cloud"
                    # Only look for metrics if the test passed
                    assert_metrics(test_case=self, deployment_type=deployment_type, *wrapper_args, **wrapper_kwargs)
        return wrapper
    return decorator
