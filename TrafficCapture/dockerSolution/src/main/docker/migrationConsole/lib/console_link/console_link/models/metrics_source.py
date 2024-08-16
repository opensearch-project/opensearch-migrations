from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple

import boto3
import botocore
from cerberus import Validator
from console_link.models.utils import raise_for_aws_api_error
import requests
import logging

from console_link.models.schema_tools import contains_one_of

logger = logging.getLogger(__name__)


MetricStatistic = Enum(
    "MetricStatistic", ["SampleCount", "Average", "Sum", "Minimum", "Maximum"]
)


class Component(Enum):
    CAPTUREPROXY = "captureProxy"
    REPLAYER = "replayer"


MetricsSourceType = Enum("MetricsSourceType", ["CLOUDWATCH", "PROMETHEUS"])


CLOUDWATCH_SCHEMA = {
    "type": "dict",
    "schema": {
        "aws_region": {
            "type": "string",
            "required": False,
        },
    },
    "nullable": True
}

PROMETHEUS_SCHEMA = {
    "type": "dict",
    "schema": {
        "endpoint": {
            "type": "string",
            "required": True,
        },
    },
}

SCHEMA = {
    "metrics": {
        "type": "dict",
        "check_with": contains_one_of({ms.name.lower() for ms in MetricsSourceType}),
        "schema": {
            "prometheus": PROMETHEUS_SCHEMA,
            "cloudwatch": CLOUDWATCH_SCHEMA
        },
    }
}


class MetricsSource:
    def __init__(self, config: Dict) -> None:
        v = Validator(SCHEMA)
        if not v.validate({"metrics": config}):
            raise ValueError("Invalid config file for MetricsSource", v.errors)

    def get_metrics(self) -> Dict[str, List[str]]:
        raise NotImplementedError()

    def get_metric_data(
        self,
        component: Component,
        metric: str,
        statistic: MetricStatistic,
        startTime: datetime,
        period_in_seconds: int = 60,
        endTime: Optional[datetime] = None,
        dimensions: Optional[Dict] = None,
    ) -> List[Tuple[str, float]]:
        raise NotImplementedError


CLOUDWATCH_METRICS_NAMESPACE = "OpenSearchMigrations"


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
              'Namespace': 'OpenSearchMigrations'},
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
    def __init__(self, config: Dict) -> None:
        super().__init__(config)
        logger.info(f"Initializing CloudwatchMetricsSource from config {config}")
        if type(config["cloudwatch"]) is dict and "aws_region" in config["cloudwatch"]:
            self.aws_region = config["cloudwatch"]["aws_region"]
            self.boto_config = botocore.config.Config(region_name=self.aws_region)
        else:
            self.aws_region = None
            self.boto_config = None
        self.client = boto3.client("cloudwatch", config=self.boto_config)

    def get_metrics(self, recent=True) -> Dict[str, List[str]]:
        logger.info(f"{self.__class__.__name__}.get_metrics called with {recent=}")
        response = self.client.list_metrics(  # TODO: implement pagination
            Namespace=CLOUDWATCH_METRICS_NAMESPACE,
            RecentlyActive="PT3H" if recent else None,
        )
        raise_for_aws_api_error(response)
        logger.debug(f"ResponseMetadata from list_metrics: {response['ResponseMetadata']}")
        assert "Metrics" in response
        metrics = [CloudwatchMetricMetadata(m) for m in response["Metrics"]]
        components = set([m.component for m in metrics])
        logger.debug(f"Components found in returned metrics: {components}")
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
        logger.info(f"{self.__class__.__name__}.get_metric_data called with {component=}, {metric=}, {statistic=},"
                    f"{startTime=}, {period_in_seconds=}, {endTime=}, {dimensions=}")

        aws_dimensions = [{"Name": "OTelLib", "Value": component.value}]
        if dimensions:
            aws_dimensions += [{"Name": k, "Value": v} for k, v in dimensions.items()]
        logger.debug(f"AWS Dimensions set to: {aws_dimensions}")
        if not endTime:
            endTime = datetime.now()
            logger.debug(f"No endTime provided, using current time: {endTime}")
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
        logger.debug(f"ResponseMetadata from get_metric_data: {response['ResponseMetadata']}")
        data_length = len(response["MetricDataResults"][0]["Timestamps"])
        logger.debug(f"Number of datapoints returned: {data_length}")
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
        logger.info(f"Initializing PrometheusMetricsSource from config {config}")

        self.endpoint = config["prometheus"]["endpoint"]

    def get_metrics(self, recent=False) -> Dict[str, List[str]]:
        logger.info(f"{self.__class__.__name__}.get_metrics called with {recent=}")
        metrics_by_component = {}
        if recent:
            raise NotImplementedError("Recent metrics are not implemented for Prometheus")
        for c in Component:
            exported_job = prometheus_component_names(c)
            r = requests.get(
                f"{self.endpoint}/api/v1/query",
                params={"query": f'{{exported_job="{exported_job}"}}'},
            )
            logger.debug(f"Request to Prometheus: {r.request}")
            logger.debug(f"Response status code: {r.status_code}")
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
        statistic: MetricStatistic,
        startTime: datetime,
        period_in_seconds: int = 60,
        endTime: Optional[datetime] = None,
        dimensions: Optional[Dict] = None,
    ) -> List[Tuple[str, float]]:
        logger.info(f"{self.__class__.__name__} get_metric_data called with {component=}, {metric=}, {statistic=},"
                    f"{startTime=}, {period_in_seconds=}, {endTime=}, {dimensions=}")
        if not endTime:
            endTime = datetime.now()
        r = requests.get(
            f"{self.endpoint}/api/v1/query_range",
            params={  # type: ignore
                "query": f'{metric}{{exported_job="{prometheus_component_names(component)}"}}',
                "start": startTime.timestamp(),
                "end": endTime.timestamp(),
                "step": period_in_seconds,
            },
        )
        logger.debug(f"Request to Prometheus: {r.request}")
        logger.debug(f"Response status code: {r.status_code}")
        r.raise_for_status()
        assert "data" in r.json() and "result" in r.json()["data"]
        if not r.json()["data"]["result"]:
            return []
        return [
            (datetime.fromtimestamp(ts).isoformat(), float(v))
            for ts, v in r.json()["data"]["result"][0]["values"]
        ]
