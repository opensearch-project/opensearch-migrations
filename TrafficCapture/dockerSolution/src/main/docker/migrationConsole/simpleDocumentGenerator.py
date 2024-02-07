#!/usr/bin/env python
import requests
import time
import argparse
from datetime import datetime

# url_base="http://test.elb.us-west-2.amazonaws.com:9200"
username='admin'
password='admin'

# Function to get current date in a specific format for indexing
def get_current_date_index():
    return datetime.now().strftime("%Y-%m-%d")

# Function to send a request
def send_request(index, counter, url_base):
    url = f"{url_base}/{index}/_doc/{counter}"
    timestamp = datetime.now().isoformat()
    # Basic Authentication
    auth = (username, password)
    payload = {
        "timestamp": timestamp,
        "new_field": "apple"
    }

    try:
        #response = requests.put(url, json=payload, auth=auth)
        response = requests.put(url, auth=auth, json=payload, verify=False)
        print(response.text)
        print(f"Request sent at {timestamp}: {response.status_code}")
        return response.status_code
    except requests.RequestException as e:
        print(f"Error sending request: {e}")
        return None

def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", help="Source cluster endpoint e.g. http://test.elb.us-west-2.amazonaws.com:9200.")
    return parser.parse_args()

args = parse_args()
# Main loop
counter = 1
total2xxCount = 0
total4xxCount = 0
total5xxCount = 0
totalErrorCount = 0
while True:
    current_index = get_current_date_index()
    response_code = send_request(current_index, counter, args.endpoint)
    if (response_code is not None):
        first_digit = int(str(response_code)[:1])
        if (first_digit == 2):
            total2xxCount += 1
        elif (first_digit == 4):
            total4xxCount += 1
        elif (first_digit == 5):
            total5xxCount += 1
    else:
        totalErrorCount += 1
    print(f"Summary: 2xx responses = {total2xxCount}, 4xx responses = {total4xxCount}, 5xx responses = {total5xxCount}, Error requests = {totalErrorCount}")
    counter += 1
    time.sleep(0.1)
