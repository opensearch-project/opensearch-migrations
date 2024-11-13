from console_link.environment import Environment
from src.cluster_tools.base.utils import console_curl
import logging

logger = logging.getLogger(__name__)


def target_cluster_refresh(env: Environment) -> None:
    """Refreshes the target cluster's indices."""
    console_curl(
        env=env,
        path="/_refresh",
        cluster='target_cluster',
        method='POST'
    )


def get_target_index_info(env: Environment, index_name: str) -> dict:
    """Retrieves information about the target index."""
    response = console_curl(
        env=env,
        path=f"/{index_name}",
        cluster='target_cluster',
        method='GET'
    )
    return response
