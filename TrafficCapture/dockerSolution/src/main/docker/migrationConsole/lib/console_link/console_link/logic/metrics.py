from typing import List, Tuple
from console_link.models.metrics_source import MetricsSource, Component, MetricStatistic
from datetime import datetime, timedelta


def get_metric_data(metrics_source: MetricsSource, component: str, metric_name: str,
                    statistic: str, lookback: int) -> List[Tuple[str, float]]:
    try:
        component_obj = Component[component.upper()]
    except KeyError:
        raise ValueError("Invalid component", {component})
    try:
        statistic_obj = MetricStatistic[statistic]
    except KeyError:
        raise ValueError("Invalid statistic", {statistic})
    
    starttime = datetime.now() - timedelta(minutes=lookback)

    return metrics_source.get_metric_data(
        component_obj,
        metric_name,
        statistic_obj,
        starttime
    )
