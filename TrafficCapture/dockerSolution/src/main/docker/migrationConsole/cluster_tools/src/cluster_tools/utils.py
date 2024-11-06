import json
from typing import Dict, Optional, Union, Any
from console_link.cli import cli
from click.testing import CliRunner

def console_curl(path: str,
                cluster: str = 'target_cluster',
                method: str = 'GET',
                headers: Optional[Dict[str, str]] = None,
                data: Optional[str] = None,
                json_data: Optional[Dict] = None) -> Union[Dict[str, Any], str]:
    """
    Utility function to call the 'console clusters curl' command programmatically.

    :param cluster: 'source_cluster' or 'target_cluster'
    :param path: API path to call
    :param method: HTTP method (e.g., 'GET', 'POST', etc.)
    :param headers: Dictionary of headers to include in the request
    :param data: Data to send in the request body
    :param json_data: JSON data to send in the request body (as a dictionary)
    :return: Parsed JSON response as a dictionary or raw response string
    """

    cmd_args = ['clusters', 'curl', cluster, path, '-X', method]

    if headers:
        for key, value in headers.items():
            cmd_args.extend(['-H', f'{key}:{value}'])

    if data:
        cmd_args.extend(['-d', data])

    if json_data:
        if not headers or 'Content-Type' not in headers:
            cmd_args.extend(['-H', 'Content-Type: application/json'])
        json_str = json.dumps(json_data)
        cmd_args.extend(['--json', json_str])

    runner = CliRunner()
    result = runner.invoke(cli, cmd_args)

    if result.exit_code != 0:
        raise Exception(f"Command failed with exit code {result.exit_code}\n{result.output}")

    try:
        return json.loads(result.output)
    except json.JSONDecodeError:
        return result.output