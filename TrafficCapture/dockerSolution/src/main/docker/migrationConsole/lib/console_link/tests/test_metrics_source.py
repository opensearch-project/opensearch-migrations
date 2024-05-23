import json

import requests
from console_link.models.metrics_source import MetricsSource, CloudwatchMetricsSource, PrometheusMetricsSource, \
    MetricStatistic, get_metrics_source, Component, UnsupportedMetricsSourceError
import pytest
import requests_mock

import botocore.session
from botocore.stub import Stubber

from console_link.logic.utils import AWSAPIError

mock_metrics_list = {'captureProxy': ['kafkaCommitCount', 'captureConnectionDuration'],
                     'replayer': ['kafkaCommitCount']}

mock_metric_data = [('2024-05-22T20:06:00-06:00', 0.0), ('2024-05-22T20:07:00-06:00', 1.0),
                    ('2024-05-22T20:08:00-06:00', 2.0), ('2024-05-22T20:09:00-06:00', 3.0),
                    ('2024-05-22T20:10:00-06:00', 4.0)]


@pytest.fixture
def prometheus_ms():
    # due to https://github.com/psf/requests/issues/6089, tests with request-mocker and query params will
    # fail if the endpoint doesn't start with http
    endpoint = "http://localhost:9090"
    return PrometheusMetricsSource({
        "type": "prometheus",
        "endpoint": endpoint
    })


@pytest.fixture
def cw_ms():
    return CloudwatchMetricsSource({
        "type": "cloudwatch",
    })


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


def test_cloudwatch_metrics_get_metrics(cw_ms):
    cw_session = botocore.session.get_session().create_client("cloudwatch")
    stubber = Stubber(cw_session)
    with open('lib/console_link/tests/data/cloudwatch_list_metrics_response.json') as f:
        stubber.add_response("list_metrics", json.load(f))
    stubber.activate()

    cw_ms.client = cw_session
    metrics = cw_ms.get_metrics(cw_session)
    assert sorted(metrics.keys()) == sorted(mock_metrics_list.keys())
    assert sorted(metrics['captureProxy']) == sorted(mock_metrics_list['captureProxy'])
    assert sorted(metrics['replayer']) == sorted(mock_metrics_list['replayer'])


def test_cloudwatch_metrics_get_metrics_error(cw_ms):
    cw_session = botocore.session.get_session().create_client("cloudwatch")
    stubber = Stubber(cw_session)
    with open('lib/console_link/tests/data/cloudwatch_error.json') as f:
        stubber.add_response("list_metrics", json.load(f))
    stubber.activate()

    cw_ms.client = cw_session
    with pytest.raises(AWSAPIError):
        cw_ms.get_metrics(cw_session)


# This one doesn't serialize to json nicely because of the datetime objects
import datetime
from dateutil.tz import tzlocal  # type: ignore
cw_get_metric_data = {
    'Messages': [],
    'MetricDataResults': [
        {
            'Id': 'kafkaCommitCount',
            'Label': 'kafkaCommitCount',
            'StatusCode': 'Complete',
            'Timestamps': [datetime.datetime(2024, 5, 22, 20, 6, tzinfo=tzlocal()),
                           datetime.datetime(2024, 5, 22, 20, 7, tzinfo=tzlocal()),
                           datetime.datetime(2024, 5, 22, 20, 8, tzinfo=tzlocal()),
                           datetime.datetime(2024, 5, 22, 20, 9, tzinfo=tzlocal()),
                           datetime.datetime(2024, 5, 22, 20, 10, tzinfo=tzlocal())
                           ],
            'Values': [0.0, 1.0, 2.0, 3.0, 4.0]
        }
    ],
    'ResponseMetadata': {'HTTPHeaders': {'content-length': '946',
                                         'content-type': 'text/xml',
                                         'date': 'Wed, 22 May 2024 20:11:30 GMT',
                                         'x-amzn-requestid': 'bb262432-40c9-48be-b1bf-7e4619f6fe8b'},
                         'HTTPStatusCode': 200,
                         'RequestId': 'bb262432-40c9-48be-b1bf-7e4619f6fe8b',
                         'RetryAttempts': 0}
}


def test_cloudwatch_metrics_get_metric_data(cw_ms):
    cw_session = botocore.session.get_session().create_client("cloudwatch")
    stubber = Stubber(cw_session)
    stubber.add_response("get_metric_data", cw_get_metric_data)
    stubber.activate()

    cw_ms.client = cw_session
    metrics = cw_ms.get_metric_data(Component.CAPTUREPROXY, "kafkaCommitCount",
                                    MetricStatistic.Average, startTime=datetime.datetime.now())
    assert metrics == mock_metric_data


def test_prometheus_get_metrics(prometheus_ms):
    with requests_mock.Mocker() as rm:
        with open('lib/console_link/tests/data/prometheus_list_metrics_capture_response.json') as f:
            rm.get(f"{prometheus_ms.endpoint}/api/v1/query?query=%7Bexported_job%3D%22capture%22%7D",
                   status_code=200,
                   json=json.load(f))
        with open('lib/console_link/tests/data/prometheus_list_metrics_replay_response.json') as f:
            rm.get(f"{prometheus_ms.endpoint}/api/v1/query?query=%7Bexported_job%3D%22replay%22%7D",
                   status_code=200,
                   json=json.load(f))

        metrics = prometheus_ms.get_metrics()
    assert sorted(metrics.keys()) == sorted(mock_metrics_list.keys())
    assert sorted(metrics['captureProxy']) == sorted(mock_metrics_list['captureProxy'])
    assert sorted(metrics['replayer']) == sorted(mock_metrics_list['replayer'])


def test_prometheus_get_metrics_error(prometheus_ms):
    with requests_mock.Mocker() as rm:
        rm.get(f"{prometheus_ms.endpoint}/api/v1/query",
               status_code=500)
        with pytest.raises(requests.exceptions.HTTPError):
            prometheus_ms.get_metrics()
