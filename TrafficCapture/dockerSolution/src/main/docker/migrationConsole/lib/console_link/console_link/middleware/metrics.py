from typing import List, Tuple
from console_link.models.metrics_source import MetricsSource, Component, MetricStatistic, PrometheusMetricsSource, \
    CloudwatchMetricsSource
from datetime import datetime, timedelta
import logging

logger = logging.getLogger(__name__)


class UnsupportedMetricsSourceError(Exception):
    def __init__(self, supplied_metrics_source: str):
        super().__init__("Unsupported metrics source type", supplied_metrics_source)


def get_metrics_source(config):
    if 'prometheus' in config:
        return PrometheusMetricsSource(config)
    elif 'cloudwatch' in config:
        return CloudwatchMetricsSource(config)
    else:
        logger.error(f"An unsupported metrics source type was provided: {config.keys()}")
        if len(config.keys()) > 1:
            raise UnsupportedMetricsSourceError(', '.join(config.keys()))
        raise UnsupportedMetricsSourceError(next(iter(config.keys())))


def get_metric_data(metrics_source: MetricsSource, component: str, metric_name: str,
                    statistic: str, lookback: int) -> List[Tuple[str, float]]:
    logger.info(f"Called get_metric_data with {component=}, {metric_name=},"
                f"{statistic=}, {lookback=}")
    try:
        component_obj = Component[component.upper()]
    except KeyError:
        logger.error(f"Component {component} was not found in {list(Component)}")
        raise ValueError("Invalid component", {component})
    try:
        statistic_obj = MetricStatistic[statistic]
    except KeyError:
        logger.error(f"Statistic {statistic} was not found in {list(MetricStatistic)}")
        raise ValueError("Invalid statistic", {statistic})
    
    starttime = datetime.now() - timedelta(minutes=lookback)
    logger.info(f"Setting starttime to current time ({datetime.now()}) minus lookback ({lookback}: {starttime}")

    return metrics_source.get_metric_data(
        component_obj,
        metric_name,
        statistic_obj,
        starttime
    )
