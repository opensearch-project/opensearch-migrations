#!/usr/bin/env python
import sys
import requests
import time
import argparse
from datetime import datetime
import urllib3
import os
from collections import deque
import logging
import json
import subprocess

# Disable InsecureRequestWarning
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Disable requests library logging
logging.getLogger("requests").setLevel(logging.WARNING)
logging.getLogger("urllib3").setLevel(logging.WARNING)


def get_current_date_index():
    """Get current date in a specific format for indexing."""
    return datetime.now().strftime("%Y-%m-%d")


def send_request(session, index_suffix, url_base, auth, headers, no_refresh):
    """Send a request to the specified URL with the given payload."""
    timestamp = datetime.now().isoformat()
    refresh_param = "false" if no_refresh else "true"
    url = f"{url_base}/simple_doc_{index_suffix}/_doc/{timestamp}?refresh={refresh_param}"
    payload = {
        "timestamp": timestamp,
        "new_field": "apple"
    }
    try:
        response = session.put(url, json=payload, auth=auth, headers=headers, verify=False, timeout=0.5)
        return response.status_code, timestamp, response.json()
    except requests.RequestException as e:
        return None, str(e), None


def send_multi_type_request(session, index_name, type_name, payload, url_base, auth, headers, no_refresh):
    """Send a request to the specified URL with the given payload."""
    timestamp = datetime.now().isoformat()
    refresh_param = "false" if no_refresh else "true"
    url = f"{url_base}/{index_name}/{type_name}/{timestamp}?refresh={refresh_param}"
    try:
        response = session.put(url, json=payload, auth=auth, headers=headers, verify=False, timeout=0.5)
        return response.status_code, timestamp, response.json()
    except requests.RequestException as e:
        return None, str(e), None


def send_request_sigv4(index_suffix, no_refresh, multi_type=False, type_name=None):
    """Send a request using console clusters command with SigV4 authentication."""
    timestamp = datetime.now().isoformat()
    refresh_param = "false" if no_refresh else "true"

    if multi_type:
        index_name = "multi_type_index"
        if type_name == "type1":
            payload = json.dumps({
                "title": "This is title of type 1",
                "timestamp": timestamp
            })
        else:
            payload = json.dumps({
                "content": "This is content of type 2",
                "contents": "This is contents of type 2",
                "timestamp": timestamp
            })
        url_path = f"{index_name}/{type_name}/{timestamp}"
    else:
        index_name = f"simple_doc_{index_suffix}"
        payload = json.dumps({
            "timestamp": timestamp,
            "new_field": "apple"
        })
        url_path = f"{index_name}/_doc/{timestamp}"

    command = (
        f'console clusters curl -XPUT source_cluster "{url_path}?refresh={refresh_param}" '
        f'-H "Content-Type: application/json" -d \'{payload}\''
    )

    try:
        result = subprocess.run(command, shell=True, check=True, capture_output=True, text=True)
        return 200, timestamp, json.loads(result.stdout)
    except subprocess.CalledProcessError as e:
        return None, str(e), None


def parse_args():
    """Parse command line arguments."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", help="Cluster endpoint e.g. http://test.elb.us-west-2.amazonaws.com:9200.")
    parser.add_argument("--username", help="Cluster username.")
    parser.add_argument("--password", help="Cluster password.")
    parser.add_argument("--enable_multi_type", action='store_true',
                        help="Flag to enable sending documents to a multi-type index.")
    parser.add_argument("--no-clear-output", action='store_true',
                        help="Flag to not clear the output before each run. " +
                             "Helpful for piping to a file or other utility.")
    parser.add_argument("--requests-per-sec", type=float, default=10.0, help="Target requests per second to be sent.")
    parser.add_argument("--no-refresh", action='store_true', help="Flag to disable refresh after each request.")
    parser.add_argument("--basic-auth", action='store_true', help="Use basic authentication")
    parser.add_argument("--no-auth", action='store_true', help="Use no authentication")
    parser.add_argument("--sigv4-auth", action='store_true', help="Use SigV4 authentication")
    return parser.parse_args()


def update_counts(response_code, total_counts):
    """Update the total counts based on the response code."""
    if response_code is not None:
        first_digit = str(response_code)[0]
        if first_digit == '2':
            total_counts['2xx'] += 1
        elif first_digit == '4':
            total_counts['4xx'] += 1
        elif first_digit == '5':
            total_counts['5xx'] += 1
    else:
        total_counts['error'] += 1


def calculate_throughput(request_timestamps):
    """Calculate throughput over the last 5 seconds."""
    if len(request_timestamps) < 2:
        return 1
    return len(request_timestamps) / (request_timestamps[-1] - request_timestamps[0]).total_seconds()


def calculate_sleep_time(request_timestamps, target_requests_per_sec):
    """
    Calculate the sleep time based on the target requests per second.
    
    This function calculates the sleep time required to achieve the target requests per second.
    It takes into account average time per iteration over the last few seconds.
    The sleep time is adjusted based on the difference between the target time
    per iteration and the actual average time per iteration.
    
    Args:
        request_timestamps (list): A list of datetime objects representing the timestamps of the recent requests.
        target_requests_per_sec (float): The target number of requests per second.
    
    Returns:
        float: The calculated sleep time in seconds.
    """
    if not request_timestamps:
        return 0

    target_time_per_iteration = 1.0 / target_requests_per_sec
    average_time_per_iteration = (datetime.now() -
                                  request_timestamps[0]).total_seconds() / (len(request_timestamps) + 1)

    sleep_time = (target_time_per_iteration - average_time_per_iteration) * len(request_timestamps)

    return max(0, sleep_time)


def main():
    """
    How to Run:
    - Basic Auth: python script.py --basic-auth --username admin --password secret
    - No Auth: python script.py --no-auth
    - SigV4 Auth: python script.py --sigv4-auth

    Note: Running 'python script.py' without specifying an auth type will result in an error.

    The script supports additional parameters:
    --endpoint: Cluster endpoint URL
    --enable_multi_type: Enable sending documents to a multi-type index
    --no-clear-output: Prevent clearing the output before each run
    --requests-per-sec: Set target requests per second (default: 10.0)
    --no-refresh: Disable refresh after each request

    Default behavior:
    - Endpoint: Uses SOURCE_DOMAIN_ENDPOINT environment variable or 'https://capture-proxy:9200'
    - Authentication: None (must be specified)
    - Index Type: Simple document requests (unless --enable_multi_type is used)
    - Output Clearing: Clears output before each run (unless --no-clear-output is specified)
    - Request Rate: 10 requests per second
    - Refresh: Enabled after each request (unless --no-refresh is specified)
    """

    args = parse_args()

    # Check if exactly one auth type is specified
    auth_types = [args.basic_auth, args.no_auth, args.sigv4_auth]
    if sum(auth_types) != 1:
        logger.error(
            "Error: You must specify exactly one authentication type (--basic-auth, --no-auth, or --sigv4-auth)"
        )
        sys.exit(1)

    # Handle basic auth
    if args.basic_auth:
        if not (args.username and args.password):
            logger.error("Error: --basic-auth requires both --username and --password")
            sys.exit(1)
        auth = (args.username, args.password)
    else:
        auth = None

    url_base = args.endpoint or os.environ.get('SOURCE_DOMAIN_ENDPOINT', 'https://capture-proxy:9200')

    session = requests.Session()
    keep_alive_headers = {'Connection': 'keep-alive'}

    total_counts = {'2xx': 0, '4xx': 0, '5xx': 0, 'error': 0}
    start_time = time.time()
    request_timestamps = deque()
    total_requests = 0

    while True:
        total_requests += 1
        request_timestamps.append(datetime.now())
        current_index = get_current_date_index()

        if args.enable_multi_type:
            if total_requests % 2 != 0:
                type_name = "type1"
                payload = {"title": "This is title of type 1"}
            else:
                type_name = "type2"
                payload = {"content": "This is content of type 2", "contents": "This is contents of type 2"}
        else:
            type_name = None
            payload = None

        if args.sigv4_auth:
            response_code, request_timestamp_or_error, response_json = send_request_sigv4(
                current_index, args.no_refresh, args.enable_multi_type, type_name
            )
        else:
            if args.enable_multi_type:
                response_code, request_timestamp_or_error, response_json = send_multi_type_request(
                    session, "multi_type_index", type_name, payload, url_base, auth, keep_alive_headers, args.no_refresh
                )
            else:
                response_code, request_timestamp_or_error, response_json = send_request(
                    session, current_index, url_base, auth, keep_alive_headers, args.no_refresh
                )

        update_counts(response_code, total_counts)

        if response_code is not None:
            request_message = f"Request sent at {request_timestamp_or_error}: {response_code}"
            response_pretty = f"Response: {json.dumps(response_json, indent=2)}"
        else:
            request_message = f"Error sending request: {request_timestamp_or_error}"
            response_pretty = "Response: N/A"

        throughput = calculate_throughput(request_timestamps)

        summary_message = (
            f"Summary: 2xx responses = {total_counts['2xx']}, 4xx responses = {total_counts['4xx']}, "
            f"5xx responses = {total_counts['5xx']}, Error requests = {total_counts['error']}"
        )
        throughput_message = f"Request throughput over the last 5 seconds: {throughput:.2f} req/sec"

        clear_output_message = "\033[H\033[J" if not args.no_clear_output else ""

        logger.info(f"{clear_output_message}" +
                    f"{request_message}\n" +
                    f"{response_pretty}\n" +
                    f"{summary_message}\n" +
                    f"{throughput_message}")

        sleep_time = calculate_sleep_time(request_timestamps, args.requests_per_sec)

        sys.stdout.flush()

        if sleep_time > 0:
            time.sleep(sleep_time)

        if time.time() - start_time >= 5:
            session.close()
            session = requests.Session()
            start_time = time.time()

        while request_timestamps and (datetime.now() - request_timestamps[0]).total_seconds() > 5:
            request_timestamps.popleft()


if __name__ == "__main__":
    main()
