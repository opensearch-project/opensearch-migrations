from datetime import datetime
from enum import Enum
from pprint import pprint
from typing import Any, Dict, List, Optional, Tuple

import boto3
from cerberus import Validator
from console_link.logic.utils import raise_for_aws_api_error
import requests

MetricsSourceType = Enum("MetricsSourceType", ["CLOUDWATCH", "PROMETHEUS"])
MetricStatistic = Enum(
    "MetricStatistic", ["SampleCount", "Average", "Sum", "Minimum", "Maximum"]
)


class Component(Enum):
    CAPTUREPROXY = "captureProxy"
    REPLAYER = "replayer"


SCHEMA = {
    "type": {
        "type": "string",
        "allowed": [m.name.lower() for m in MetricsSourceType],
        "required": True,
    },
    "endpoint": {
        "type": "string",
        "required": False,
    },
}


def get_metrics_source(config):
    metric_source_type = MetricsSourceType[config["type"].upper()]
    if metric_source_type == MetricsSourceType.CLOUDWATCH:
        return CloudwatchMetricsSource(config)
    elif metric_source_type == MetricsSourceType.PROMETHEUS:
        return PrometheusMetricsSource(config)
    else:
        raise ValueError(f"Unsupported metrics source type: {config['type']}")


class MetricsSource:
    def __init__(self, config: Dict) -> None:
        v = Validator(SCHEMA)
        if not v.validate(config):
            raise ValueError("Invalid config file for cluster", v.errors)

    def get_metrics(self) -> Dict[str, List[str]]:
        raise NotImplementedError()

    def get_metric_data(
        self,
        component: Component,
        metric: str,
        statistc: MetricStatistic,
        startTime: datetime,
        period_in_seconds: int = 60,
        endTime: Optional[datetime] = None,
        dimensions: Optional[Dict] = None,
    ) -> List[Tuple[str, float]]:
        raise NotImplementedError


CLOUDWATCH_METRICS_NAMESPACE = "TrafficCaptureReplay"


class CloudwatchMetricMetadata:
    def __init__(self, list_metric_data: Dict[str, Any]):
        """
        example:
        {'Dimensions': [{'Name': 'targetStatusCode', 'Value': '200'},
                             {'Name': 'method', 'Value': 'GET'},
                             {'Name': 'sourceStatusCode', 'Value': '200'},
                             {'Name': 'OTelLib', 'Value': 'replayer'},
                             {'Name': 'statusCodesMatch', 'Value': 'true'}],
              'MetricName': 'tupleComparison',
              'Namespace': 'TrafficCaptureReplay'},
        """
        assert "Namespace" in list_metric_data, "Namespace is missing"
        assert "MetricName" in list_metric_data, "MetricName is missing"
        self.namespace = list_metric_data["Namespace"]
        self.metric_name = list_metric_data["MetricName"]

        if "Dimensions" in list_metric_data:
            self.dimensions = {
                d["Name"]: d["Value"] for d in list_metric_data["Dimensions"]
            }
        else:
            self.dimensions = {}
        self.component = self.dimensions.get("OTelLib", "None")


class CloudwatchMetricsSource(MetricsSource):
    client = boto3.client("cloudwatch")

    def __init__(self, config: Dict) -> None:
        super().__init__(config)

    def get_metrics(self, recent=True) -> Dict[str, List[str]]:
        response = self.client.list_metrics(  # TODO: implement pagination
            Namespace=CLOUDWATCH_METRICS_NAMESPACE,
            RecentlyActive="PT3H" if recent else None,
        )
        raise_for_aws_api_error(response)
        assert "Metrics" in response
        metrics = [CloudwatchMetricMetadata(m) for m in response["Metrics"]]
        components = set([m.component for m in metrics])
        metrics_by_component = {}
        for component in components:
            metrics_by_component[component] = [
                m.metric_name for m in metrics if m.component == component
            ]
        return metrics_by_component

    def get_metric_data(
        self,
        component: Component,
        metric: str,
        statistic: MetricStatistic,
        startTime: datetime,
        period_in_seconds: int = 60,
        endTime: Optional[datetime] = None,
        dimensions: Optional[Dict[str, str]] = None,
    ) -> List[Tuple[str, float]]:
        aws_dimensions = [{"Name": "OTelLib", "Value": component.value}]
        if dimensions:
            aws_dimensions += [{"Name": k, "Value": v} for k, v in dimensions.items()]
        if not endTime:
            endTime = datetime.now()
        response = self.client.get_metric_data(
            MetricDataQueries=[
                {
                    "Id": metric,
                    "MetricStat": {
                        "Metric": {
                            "Namespace": CLOUDWATCH_METRICS_NAMESPACE,
                            "MetricName": metric,
                            "Dimensions": aws_dimensions,
                        },
                        "Period": period_in_seconds,
                        "Stat": statistic.name,
                    },
                },
            ],
            StartTime=startTime,
            EndTime=endTime,
            ScanBy="TimestampAscending",
        )
        raise_for_aws_api_error(response)
        data_length = len(response["MetricDataResults"][0]["Timestamps"])
        return [
            (
                response["MetricDataResults"][0]["Timestamps"][i].isoformat(),
                response["MetricDataResults"][0]["Values"][i],
            )
            for i in range(data_length)
        ]


def prometheus_component_names(c: Component) -> str:
    if c == Component.CAPTUREPROXY:
        return "capture"
    elif c == Component.REPLAYER:
        return "replay"
    else:
        raise ValueError(f"Unsupported component: {c}")


class PrometheusMetricsSource(MetricsSource):
    def __init__(self, config: Dict) -> None:
        super().__init__(config)
        assert "endpoint" in config  # TODO: add to validation
        self.endpoint = config["endpoint"]

    def get_metrics(self, recent=True) -> Dict[str, List[str]]:
        metrics_by_component = {}
        if recent:
            pass
        for c in Component:
            exported_job = prometheus_component_names(c)
            r = requests.get(
                f"{self.endpoint}/api/v1/query",
                params={"query": f'{{exported_job="{exported_job}"}}'},
            )
            r.raise_for_status()
            assert "data" in r.json() and "result" in r.json()["data"]
            metrics_by_component[c.value] = list(
                set(m["metric"]["__name__"] for m in r.json()["data"]["result"])
            )
        return metrics_by_component

    def get_metric_data(
        self,
        component: Component,
        metric: str,
        statistc: MetricStatistic,
        startTime: datetime,
        period_in_seconds: int = 60,
        endTime: Optional[datetime] = None,
        dimensions: Optional[Dict] = None,
    ) -> List[Tuple[str, float]]:
        if not endTime:
            endTime = datetime.now()
        print(
            f'delta({metric}{{exported_job="{prometheus_component_names(component)}}}[$__interval])"'
        )
        r = requests.get(
            f"{self.endpoint}/api/v1/query_range",
            params={  # type: ignore
                "query": f'{metric}{{exported_job="{prometheus_component_names(component)}"}}',
                # delta(kafkaCommitCount_total{exported_job="capture"}[$__interval])
                # "query": f'delta({metric}{{exported_job="{prometheus_component_names(component)}}}[$__interval])"',
                "start": startTime.timestamp(),
                "end": endTime.timestamp(),
                "step": period_in_seconds,
            },
        )
        r.raise_for_status()
        assert "data" in r.json() and "result" in r.json()["data"]
        if not r.json()["data"]["result"]:
            return []
        return [
            (datetime.fromtimestamp(ts).isoformat(), float(v))
            for ts, v in r.json()["data"]["result"][0]["values"]
        ]
