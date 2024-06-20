from typing import List, Tuple
from console_link.models.metrics_source import Component, MetricStatistic
from datetime import datetime, timedelta
import logging

from console_link.environment import Environment

logger = logging.getLogger(__name__)


def get_metric_data(env: Environment, component: str, metric_name: str,
                    statistic: str, lookback: int) -> List[Tuple[str, float]]:
    logger.info(f"Called get_metric_data with {component=}, {metric_name=},"
                f"{statistic=}, {lookback=}")
    metrics_source = env.metrics_source
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
