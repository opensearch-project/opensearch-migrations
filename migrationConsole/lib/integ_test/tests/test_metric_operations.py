from datetime import datetime, timezone
from unittest.mock import Mock, patch

from integ_test.metric_operations import (
    CW_NAMESPACE,
    _any_candidate_has_recent_data,
    assert_cloudwatch_capture_replay_metrics_for_workflow_run,
)


def _dimensions(**values):
    return [{"Name": name, "Value": value} for name, value in values.items()]


def _dimension_key(dimensions):
    return tuple((d["Name"], d["Value"]) for d in dimensions)


def _list_metrics_page(metrics):
    return {
        "ResponseMetadata": {"HTTPStatusCode": 200},
        "Metrics": metrics,
    }


def _metric(dimensions):
    return {"Dimensions": dimensions}


class _FakePaginator:
    def __init__(self, client):
        self.client = client

    def paginate(self, **kwargs):
        self.client.list_metric_calls.append(kwargs)
        yield from self.client.list_metric_pages_by_name.get(kwargs["MetricName"], [])


class _FakeCloudWatchClient:
    def __init__(self, list_metric_pages_by_name, metric_data_by_query):
        self.list_metric_pages_by_name = list_metric_pages_by_name
        self.metric_data_by_query = metric_data_by_query
        self.list_metric_calls = []
        self.metric_data_calls = []

    def get_paginator(self, operation_name):
        assert operation_name == "list_metrics"
        return _FakePaginator(self)

    def get_metric_data(self, **kwargs):
        metric = kwargs["MetricDataQueries"][0]["MetricStat"]["Metric"]
        key = (metric["MetricName"], _dimension_key(metric["Dimensions"]))
        self.metric_data_calls.append(key)
        return {
            "ResponseMetadata": {"HTTPStatusCode": 200},
            "MetricDataResults": [{"Timestamps": self.metric_data_by_query.get(key, [])}],
        }


def test_any_candidate_scans_later_list_metrics_pages():
    required_dimensions = {"qualifier": "eksint-p1039", "OTelLib": "documentMigration"}
    target_dimensions = _dimensions(
        qualifier="eksint-p1039",
        OTelLib="documentMigration",
        indexName="test-index",
    )
    client = _FakeCloudWatchClient(
        {
            "pipelineDocsMigrated": [
                _list_metrics_page([_metric(_dimensions(qualifier="other", OTelLib="documentMigration"))]),
                _list_metrics_page([_metric(target_dimensions)]),
            ]
        },
        {
            ("pipelineDocsMigrated", _dimension_key(target_dimensions)): [datetime.now(timezone.utc)],
        },
    )

    assert _any_candidate_has_recent_data(
        client,
        [("pipelineDocsMigrated", required_dimensions)],
        lookback_minutes=20,
    )
    assert client.list_metric_calls == [
        {
            "Namespace": CW_NAMESPACE,
            "MetricName": "pipelineDocsMigrated",
            "RecentlyActive": "PT3H",
        }
    ]
    assert client.metric_data_calls == [("pipelineDocsMigrated", _dimension_key(target_dimensions))]


def test_any_candidate_skips_stale_matching_dimensions():
    required_dimensions = {"qualifier": "eksint-p1039", "OTelLib": "documentMigration"}
    stale_dimensions = _dimensions(
        qualifier="eksint-p1039",
        OTelLib="documentMigration",
        httpMethod="GET",
    )
    active_dimensions = _dimensions(
        qualifier="eksint-p1039",
        OTelLib="documentMigration",
        httpMethod="POST",
    )
    client = _FakeCloudWatchClient(
        {
            "bytesSent": [
                _list_metrics_page([_metric(stale_dimensions), _metric(active_dimensions)]),
            ]
        },
        {
            ("bytesSent", _dimension_key(active_dimensions)): [datetime.now(timezone.utc)],
        },
    )

    assert _any_candidate_has_recent_data(
        client,
        [("bytesSent", required_dimensions)],
        lookback_minutes=20,
    )
    assert client.metric_data_calls == [
        ("bytesSent", _dimension_key(stale_dimensions)),
        ("bytesSent", _dimension_key(active_dimensions)),
    ]


def test_any_candidate_returns_false_when_matching_dimensions_have_no_data():
    required_dimensions = {"qualifier": "eksint-p1039", "OTelLib": "documentMigration"}
    matching_dimensions = _dimensions(
        qualifier="eksint-p1039",
        OTelLib="documentMigration",
        indexName="test-index",
    )
    client = _FakeCloudWatchClient(
        {
            "pipelineDocsMigrated": [
                _list_metrics_page([_metric(matching_dimensions)]),
            ],
            "bytesRead": [
                _list_metrics_page([_metric(_dimensions(qualifier="other", OTelLib="captureProxy"))]),
            ],
        },
        {},
    )

    assert not _any_candidate_has_recent_data(
        client,
        [
            ("pipelineDocsMigrated", required_dimensions),
            ("bytesRead", {"qualifier": "eksint-p1039", "OTelLib": "captureProxy"}),
        ],
        lookback_minutes=20,
    )
    assert client.metric_data_calls == [("pipelineDocsMigrated", _dimension_key(matching_dimensions))]


def test_cloudwatch_workflow_assertion_prefers_backfill_completion_metrics():
    cloudwatch_client = Mock()
    with patch(
        "integ_test.metric_operations._load_aws_metadata",
        return_value={"AWS_REGION": "us-east-1", "STAGE_NAME": "eksint-p1039"},
    ), patch("integ_test.metric_operations.boto3.client", return_value=cloudwatch_client), patch(
        "integ_test.metric_operations._any_candidate_has_recent_data",
        return_value=True,
    ) as has_recent_data:
        assert_cloudwatch_capture_replay_metrics_for_workflow_run(attempts=1)

    candidates = has_recent_data.call_args[0][1]
    assert candidates[:3] == [
        ("pipelineDocsMigrated", {"qualifier": "eksint-p1039", "OTelLib": "documentMigration"}),
        ("pipelineBytesMigrated", {"qualifier": "eksint-p1039", "OTelLib": "documentMigration"}),
        ("bytesSent", {"qualifier": "eksint-p1039", "OTelLib": "documentMigration"}),
    ]
