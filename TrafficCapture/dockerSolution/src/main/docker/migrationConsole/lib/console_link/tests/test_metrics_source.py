from datetime import datetime
import json
from typing import Dict, List, Tuple
from console_link.models.metrics_source import MetricsSource, CloudwatchMetricsSource, PrometheusMetricsSource
from console_link.models.metrics_source import MetricStatistic, get_metrics_source, MetricsSourceType, Component
from console_link.models.metrics_source import UnsupportedMetricsSourceError
import pytest

import botocore.session
from botocore.stub import Stubber

from console_link.logic.utils import AWSAPIError

mock_metrics_list = {'captureProxy': ['kafkaCommitCount', 'captureConnectionDuration'], 'replayer': ['kafkaCommitCount']}


def test_get_metrics_source():
    cw_config = {
        "type": "cloudwatch",
    }
    cw_metrics_source = get_metrics_source(cw_config)
    assert isinstance(cw_metrics_source, CloudwatchMetricsSource)
    assert isinstance(cw_metrics_source, MetricsSource)

    prometheus_config = {
        "type": "prometheus",
        "endpoint": "localhost:9090"
    }
    prometheus_metrics_source = get_metrics_source(prometheus_config)
    assert isinstance(prometheus_metrics_source, PrometheusMetricsSource)
    assert isinstance(prometheus_metrics_source, MetricsSource)

    unknown_conig = {
        "type": "made_up_metrics_source",
    }
    with pytest.raises(UnsupportedMetricsSourceError) as excinfo:
        get_metrics_source(unknown_conig)
    assert "Unsupported metrics source type" in str(excinfo.value.args[0])
    assert unknown_conig["type"] in str(excinfo.value.args[1])


def test_prometheus_metrics_source_validates_endpoint():
    wrong_prometheus_config = {
        "type": "prometheus",
    }
    with pytest.raises(ValueError) as excinfo:
        PrometheusMetricsSource(wrong_prometheus_config)
    assert "Invalid config file for PrometheusMetricsSource" in str(excinfo.value.args[0])
    assert "endpoint" in str(excinfo.value.args[1])


def test_cloudwatch_metrics_get_metrics():
    cw = CloudwatchMetricsSource({
        "type": "cloudwatch",
    })
    cw_session = botocore.session.get_session().create_client("cloudwatch")
    stubber = Stubber(cw_session)
    with open('lib/console_link/tests/data/cloudwatch_list_metrics_response.json') as f:
        stubber.add_response("list_metrics", json.load(f))
    stubber.activate()

    cw.client = cw_session
    metrics = cw.get_metrics(cw_session)
    assert metrics == mock_metrics_list


def test_cloudwatch_metrics_get_metrics_error():
    cw = CloudwatchMetricsSource({
        "type": "cloudwatch",
    })
    cw_session = botocore.session.get_session().create_client("cloudwatch")
    stubber = Stubber(cw_session)
    with open('lib/console_link/tests/data/cloudwatch_list_metrics_error.json') as f:
        stubber.add_response("list_metrics", json.load(f))
    stubber.activate()

    cw.client = cw_session
    with pytest.raises(AWSAPIError):
        cw.get_metrics(cw_session)   
