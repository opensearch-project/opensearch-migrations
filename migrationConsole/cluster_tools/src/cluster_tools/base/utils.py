import json
from typing import Dict, Optional, Union, Any
from console_link.models.cluster import HttpMethod
from console_link.environment import Environment


def console_curl(
    env: Environment,
    path: str,
    cluster: str = 'target_cluster',
    method: str = 'GET',
    headers: Optional[Dict[str, str]] = None,
    json_data: Optional[Dict] = None
) -> Union[Dict[str, Any], str]:
    """
    Utility function to call the 'console clusters curl' command programmatically.

    :param env: Environment object
    :param path: API path to call
    :param cluster: 'source_cluster' or 'target_cluster'
    :param method: HTTP method (e.g., 'GET', 'POST', etc.)
    :param headers: Dictionary of headers to include in the request
    :param json_data: JSON data to send in the request body (as a dictionary)
    :return: Parsed JSON response as a dictionary or raw response string
    """
    try:
        http_method = HttpMethod[method.upper()]
    except KeyError:
        message = f"Invalid HTTP method: {method}"
        raise ValueError(message)

    if cluster == 'source_cluster':
        cluster_obj = env.source_cluster
    elif cluster == 'target_cluster':
        cluster_obj = env.target_cluster
    else:
        message = "`cluster` must be either 'source_cluster' or 'target_cluster'."
        raise ValueError(message)

    if cluster_obj is None:
        message = f"{cluster} is not defined in the environment."
        raise ValueError(message)

    if json_data is not None:
        if headers is None:
            headers = {}
        headers.setdefault('Content-Type', 'application/json')
        response = cluster_obj.call_api(
            path=path,
            method=http_method,
            headers=headers,
            data=json.dumps(json_data)
        )
    else:
        response = cluster_obj.call_api(
            path=path,
            method=http_method,
            headers=headers,
        )

    try:
        return response.json()
    except json.JSONDecodeError:
        return response.text
