import json
import pathlib

import botocore.session
import pytest
import requests
import requests_mock
from botocore.stub import Stubber

from console_link.models.client_options import ClientOptions
from console_link.models.factories import (UnsupportedMetricsSourceError,
                                           get_metrics_source)
from console_link.models.metrics_source import (CloudwatchMetricsSource,
                                                Component, MetricsSource,
                                                MetricStatistic,
                                                PrometheusMetricsSource)
from console_link.models.utils import AWSAPIError

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"
AWS_REGION = "us-east-1"
USER_AGENT_EXTRA = "test-agent-v1.0"
STAGE = "test"

mock_metrics_list = {'captureProxy': ['kafkaCommitCount', 'captureConnectionDuration'],
                     'replayer': ['kafkaCommitCount']}

mock_metric_data = [('2024-05-22T20:06:00+00:00', 0.0), ('2024-05-22T20:07:00+00:00', 1.0),
                    ('2024-05-22T20:08:00+00:00', 2.0), ('2024-05-22T20:09:00+00:00', 3.0),
                    ('2024-05-22T20:10:00+00:00', 4.0)]


@pytest.fixture
def prometheus_ms():
    # due to https://github.com/psf/requests/issues/6089, tests with request-mocker and query params will
    # fail if the endpoint doesn't start with http
    endpoint = "http://localhost:9090"
    return PrometheusMetricsSource(
        config={
            "prometheus": {
                "endpoint": endpoint
            }
        },
        client_options=ClientOptions(config={"user_agent_extra": USER_AGENT_EXTRA})

    )


@pytest.fixture
def cw_stubber():
    cw_session = botocore.session.get_session().create_client("cloudwatch", region_name=AWS_REGION)
    stubber = Stubber(cw_session)
    return stubber


@pytest.fixture
def cw_ms():
    return CloudwatchMetricsSource(
        config={
            "cloudwatch": {
                "aws_region": AWS_REGION,
                "qualifier": STAGE
            }
        },
        client_options=ClientOptions(config={"user_agent_extra": USER_AGENT_EXTRA})
    )


def test_get_metrics_source():
    cw_config = {
        "cloudwatch": {
            "aws_region": AWS_REGION,
            "qualifier": STAGE
        }
    }
    cw_metrics_source = get_metrics_source(cw_config)
    assert isinstance(cw_metrics_source, CloudwatchMetricsSource)
    assert isinstance(cw_metrics_source, MetricsSource)

    prometheus_config = {
        "prometheus": {
            "endpoint": "localhost:9090"
        }
    }
    prometheus_metrics_source = get_metrics_source(prometheus_config)
    assert isinstance(prometheus_metrics_source, PrometheusMetricsSource)
    assert isinstance(prometheus_metrics_source, MetricsSource)

    unknown_conig = {
        "made_up_metrics_source": {"data": "xyz"}
    }
    with pytest.raises(UnsupportedMetricsSourceError) as excinfo:
        get_metrics_source(unknown_conig)
    assert "Unsupported metrics source type" in str(excinfo.value.args[0])
    assert "made_up_metrics_source" in str(excinfo.value.args[1])


def test_prometheus_metrics_source_validates_endpoint():
    wrong_prometheus_config = {
        "prometheus": {'different-key': 'value'}
    }
    with pytest.raises(ValueError) as excinfo:
        PrometheusMetricsSource(wrong_prometheus_config)
    assert "Invalid config file for MetricsSource" in str(excinfo.value.args[0])
    assert "endpoint" in str(excinfo.value.args[1]['metrics'][0]['prometheus'])


def test_cloudwatch_metrics_get_metrics(cw_ms, cw_stubber):
    with open(TEST_DATA_DIRECTORY / 'cloudwatch_list_metrics_response.json') as f:
        cw_stubber.add_response("list_metrics", json.load(f))
    cw_stubber.activate()

    cw_ms.client = cw_stubber.client
    metrics = cw_ms.get_metrics()
    assert sorted(metrics.keys()) == sorted(mock_metrics_list.keys())
    assert sorted(metrics['captureProxy']) == sorted(mock_metrics_list['captureProxy'])
    assert sorted(metrics['replayer']) == sorted(mock_metrics_list['replayer'])


def test_cloudwatch_metrics_get_metrics_error(cw_ms, cw_stubber):
    with open(TEST_DATA_DIRECTORY / 'cloudwatch_error.json') as f:
        cw_stubber.add_response("list_metrics", json.load(f))
    cw_stubber.activate()

    cw_ms.client = cw_stubber.client
    with pytest.raises(AWSAPIError):
        cw_ms.get_metrics()


# This one doesn't serialize to json nicely because of the datetime objects
import datetime

from dateutil.tz import tzutc  # type: ignore

cw_get_metric_data = {
    'Messages': [],
    'MetricDataResults': [
        {
            'Id': 'kafkaCommitCount',
            'Label': 'kafkaCommitCount',
            'StatusCode': 'Complete',
            'Timestamps': [datetime.datetime(2024, 5, 22, 20, 6, tzinfo=tzutc()),
                           datetime.datetime(2024, 5, 22, 20, 7, tzinfo=tzutc()),
                           datetime.datetime(2024, 5, 22, 20, 8, tzinfo=tzutc()),
                           datetime.datetime(2024, 5, 22, 20, 9, tzinfo=tzutc()),
                           datetime.datetime(2024, 5, 22, 20, 10, tzinfo=tzutc())
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


def test_cloudwatch_metrics_get_metric_data(cw_ms, cw_stubber):
    cw_stubber.add_response("get_metric_data", cw_get_metric_data)
    cw_stubber.activate()

    cw_ms.client = cw_stubber.client
    metrics = cw_ms.get_metric_data(Component.CAPTUREPROXY, "kafkaCommitCount",
                                    MetricStatistic.Average, start_time=datetime.datetime.now())
    assert metrics == mock_metric_data


def test_prometheus_get_metrics(prometheus_ms):
    with requests_mock.Mocker() as rm:
        with open(TEST_DATA_DIRECTORY / 'prometheus_list_metrics_capture_response.json') as f:
            rm.get(f"{prometheus_ms.endpoint}/api/v1/query?query=%7Bexported_job%3D%22capture%22%7D",
                   status_code=200,
                   json=json.load(f))
        with open(TEST_DATA_DIRECTORY / 'prometheus_list_metrics_replay_response.json') as f:
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


def test_prometheus_get_metric_for_nonexistent_component(prometheus_ms):
    with pytest.raises(ValueError):
        prometheus_ms.get_metric_data(
            Component(3), "kafkaCommitCount",
            MetricStatistic.Average, start_time=datetime.datetime.now()
        )
