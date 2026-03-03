from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple
from cerberus import Validator
from console_link.models.client_options import ClientOptions
from console_link.models.utils import raise_for_aws_api_error, create_boto3_client, \
    append_user_agent_header_for_requests
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
        "qualifier": {
            "type": "string",
            "required": True,
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
        start_time: datetime,
        period_in_seconds: int = 60,
        end_time: Optional[datetime] = None,
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
    def __init__(self, config: Dict, client_options: Optional[ClientOptions] = None) -> None:
        super().__init__(config)
        self.client_options = client_options
        logger.info(f"Initializing CloudwatchMetricsSource from config {config}")
        self.aws_region = None
        if isinstance(config["cloudwatch"], dict) and "aws_region" in config["cloudwatch"]:
            self.aws_region = config["cloudwatch"]["aws_region"]
        if isinstance(config["cloudwatch"], dict) and "qualifier" in config["cloudwatch"]:
            self.qualifier = config["cloudwatch"]["qualifier"]

        self.client = create_boto3_client(aws_service_name="cloudwatch", region=self.aws_region,
                                          client_options=self.client_options)

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
        components = {m.component for m in metrics}
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
        start_time: datetime,
        period_in_seconds: int = 60,
        end_time: Optional[datetime] = None,
        dimensions: Optional[Dict[str, str]] = None,
    ) -> List[Tuple[str, float]]:
        logger.info(f"{self.__class__.__name__}.get_metric_data called with {component=}, {metric=}, {statistic=},"
                    f"{start_time=}, {period_in_seconds=}, {end_time=}, {dimensions=}")

        aws_dimensions = [{"Name": "OTelLib", "Value": component.value}]
        aws_dimensions += [{"Name": "qualifier", "Value": self.qualifier}]
        if dimensions:
            aws_dimensions += [{"Name": k, "Value": v} for k, v in dimensions.items()]
        logger.debug(f"AWS Dimensions set to: {aws_dimensions}")
        if not end_time:
            end_time = datetime.now()
            logger.debug(f"No endTime provided, using current time: {end_time}")
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
            StartTime=start_time,
            EndTime=end_time,
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
    raise ValueError(f"Unsupported component: {c}")


class PrometheusMetricsSource(MetricsSource):
    def __init__(self, config: Dict, client_options: Optional[ClientOptions] = None) -> None:
        super().__init__(config)
        self.client_options = client_options
        logger.info(f"Initializing PrometheusMetricsSource from config {config}")

        self.endpoint = config["prometheus"]["endpoint"]

    def get_metrics(self, recent=False) -> Dict[str, List[str]]:
        logger.info(f"{self.__class__.__name__}.get_metrics called with {recent=}")
        metrics_by_component = {}
        if recent:
            raise NotImplementedError("Recent metrics are not implemented for Prometheus")
        for c in Component:
            exported_job = prometheus_component_names(c)
            headers = None
            if self.client_options and self.client_options.user_agent_extra:
                headers = append_user_agent_header_for_requests(headers=None,
                                                                user_agent_extra=self.client_options.user_agent_extra)
            r = requests.get(
                f"{self.endpoint}/api/v1/query",
                params={"query": f'{{exported_job="{exported_job}"}}'},
                headers=headers,
            )
            logger.debug(f"Request to Prometheus: {r.request}")
            logger.debug(f"Response status code: {r.status_code}")
            r.raise_for_status()
            assert "data" in r.json() and "result" in r.json()["data"]
            metrics_by_component[c.value] = {
                m["metric"]["__name__"]
                for m in r.json()["data"]["result"]
            }
        return metrics_by_component

    def get_metric_data(
        self,
        component: Component,
        metric: str,
        statistic: MetricStatistic,
        start_time: datetime,
        period_in_seconds: int = 60,
        end_time: Optional[datetime] = None,
        dimensions: Optional[Dict] = None,
    ) -> List[Tuple[str, float]]:
        logger.info(f"{self.__class__.__name__} get_metric_data called with {component=}, {metric=}, {statistic=},"
                    f"{start_time=}, {period_in_seconds=}, {end_time=}, {dimensions=}")
        if not end_time:
            end_time = datetime.now()
        headers = None
        if self.client_options and self.client_options.user_agent_extra:
            headers = append_user_agent_header_for_requests(headers=None,
                                                            user_agent_extra=self.client_options.user_agent_extra)
        r = requests.get(
            f"{self.endpoint}/api/v1/query_range",
            params={  # type: ignore
                "query": f'{metric}{{exported_job="{prometheus_component_names(component)}"}}',
                "start": start_time.timestamp(),
                "end": end_time.timestamp(),
                "step": period_in_seconds,
            },
            headers=headers,
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
