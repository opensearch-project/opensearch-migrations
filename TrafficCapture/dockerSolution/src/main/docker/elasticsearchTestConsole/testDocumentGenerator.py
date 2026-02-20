#!/usr/bin/env python
import sys
import requests
import time
import argparse
from datetime import datetime
import urllib3
from collections import deque
import logging
import json
import random
import string
import uuid
from console_link.environment import Environment
from console_link.models.cluster import Cluster

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


def parse_args():
    """Parse command line arguments."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--enable_multi_type", action='store_true',
                        help="Flag to enable sending documents to a multi-type index.")
    parser.add_argument("--no-clear-output", action='store_true',
                        help="Flag to not clear the output before each run. " +
                             "Helpful for piping to a file or other utility.")
    parser.add_argument("--requests-per-sec", type=float, default=10.0, help="Target requests per second to be sent.")
    parser.add_argument("--no-refresh", action='store_true', help="Flag to disable refresh after each request.")
    parser.add_argument("--cluster", type=str, default="source", choices=["source", "target"],
                        help="Specify 'source' or 'target' cluster.")
    parser.add_argument("--config-file", default="/etc/migration_services.yaml", help="Path to config file")
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


def get_cluster_and_auth(config_file, cluster_type):
    env = Environment(config_file=config_file)
    cluster: Cluster = env.source_cluster if cluster_type == "source" else env.target_cluster
    auth = cluster._generate_auth_object()
    return cluster.endpoint, auth


def generate_small_doc(doc_size_bytes=150):
    """Generate a small document of approximately the specified size"""
    # Base document structure
    doc = {
        "id": str(uuid.uuid4()),
        "timestamp": datetime.now().isoformat(),
        "user": ''.join(random.choices(string.ascii_lowercase, k=8)),
        "value": random.randint(1, 1000),
        "category": random.choice(['A', 'B', 'C', 'D', 'E'])
    }
    
    # Calculate remaining bytes needed for message field
    base_size = len(json.dumps(doc))
    remaining_bytes = max(20, doc_size_bytes - base_size - 20)  # Leave room for "message" field overhead
    
    doc["message"] = ''.join(random.choices(string.ascii_letters + ' ', k=remaining_bytes))
    return doc


def bulk_insert_data(cluster, index_name, num_docs, doc_size_bytes=150, batch_size=100):
    """
    Insert bulk data into a cluster using the bulk API.
    
    Args:
        cluster: Cluster object with endpoint and auth
        index_name: Name of the index to insert into
        num_docs: Total number of documents to insert
        doc_size_bytes: Approximate size of each document in bytes
        batch_size: Number of documents per batch request
    
    Returns:
        dict: Results including total_inserted, errors, and timing info
    """
    session = requests.Session()
    total_inserted = 0
    total_errors = 0
    start_time = time.time()
    
    try:
        while total_inserted < num_docs:
            # Generate batch of documents
            batch_docs = min(batch_size, num_docs - total_inserted)
            docs = [generate_small_doc(doc_size_bytes) for _ in range(batch_docs)]
            
            # Prepare bulk request
            bulk_data = []
            for doc in docs:
                bulk_data.append(json.dumps({"index": {"_index": index_name}}))
                bulk_data.append(json.dumps(doc))
            
            bulk_body = '\n'.join(bulk_data) + '\n'
            headers = {'Content-Type': 'application/x-ndjson'}
            
            # Send bulk request
            try:
                response = session.post(
                    f"{cluster.endpoint}/_bulk",
                    data=bulk_body,
                    headers=headers,
                    auth=cluster._generate_auth_object(),
                    verify=False,
                    timeout=30
                )
                
                if response.status_code == 200:
                    result = response.json()
                    # Count successful insertions
                    successful = sum(1 for item in result.get('items', [])
                                     if item.get('index', {}).get('status') in [200, 201])
                    total_inserted += successful
                    total_errors += (batch_docs - successful)
                else:
                    total_errors += batch_docs
                    logger.error(f"Bulk insert failed with status {response.status_code}")
                    
            except Exception as e:
                total_errors += batch_docs
                logger.error(f"Error during bulk insert: {e}")
                
    except KeyboardInterrupt:
        logger.info("Bulk insert interrupted by user")
    finally:
        session.close()
    
    elapsed_time = time.time() - start_time
    return {
        'total_inserted': total_inserted,
        'total_errors': total_errors,
        'elapsed_time': elapsed_time,
        'docs_per_sec': total_inserted / elapsed_time if elapsed_time > 0 else 0,
        'estimated_size_mb': (total_inserted * doc_size_bytes) / (1024 * 1024)
    }


def main():
    args = parse_args()

    url_base, auth = get_cluster_and_auth(args.config_file, args.cluster)

    session = requests.Session()
    keep_alive_headers = {'Connection': 'keep-alive'}

    total_counts = {'2xx': 0, '4xx': 0, '5xx': 0, 'error': 0}
    start_time = time.time()
    request_timestamps = deque()
    total_requests = 0

    while True:
        total_requests = total_requests + 1
        request_timestamps.append(datetime.now())
        current_index = get_current_date_index()

        # Alternate between sending multi-type requests of 'type1' and 'type2'
        if args.enable_multi_type:
            if total_requests % 2 != 0:
                type_name = "type1"
                payload = {"title": "This is title of type 1"}
            else:
                type_name = "type2"
                payload = {"content": "This is content of type 2", "contents": "This is contents of type 2"}
            response_code, request_timestamp_or_error, response_json = send_multi_type_request(
                session, "multi_type_index", type_name, payload, url_base, auth, keep_alive_headers, args.no_refresh
            )
        # Send simple document request
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

        # Flush the stdout buffer to ensure the log messages are displayed immediately and in sync
        sys.stdout.flush()

        if (sleep_time > 0):
            time.sleep(sleep_time)

        if time.time() - start_time >= 5:
            session.close()
            session = requests.Session()
            start_time = time.time()

        # Remove timestamps older than 5 seconds
        while request_timestamps and (datetime.now() - request_timestamps[0]).total_seconds() > 5:
            request_timestamps.popleft()


if __name__ == "__main__":
    main()
