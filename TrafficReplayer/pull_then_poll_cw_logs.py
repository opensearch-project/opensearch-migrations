#!/usr/bin/env python3
import os
import time
import sys

import boto3

TRAFFIC_LOG_GROUP = os.environ["CW_LOG_GROUP_NAME"]
TRAFFIC_LOG_STREAM = os.environ["CW_LOG_STREAM_NAME"]


def main():
    # This is intended to assume the IAM role of the fargate container.
    logs_client = boto3.client('logs')

    # Continuously get events and add them to our final list until we see a repeat of the "nextForwardToken"
    # Then script will start checking periodically
    print(f"Pulling CW events from {TRAFFIC_LOG_GROUP}:{TRAFFIC_LOG_STREAM}...", file=sys.stderr)
    current_response = logs_client.get_log_events(logGroupName=TRAFFIC_LOG_GROUP,
                                                  logStreamName=TRAFFIC_LOG_STREAM, startFromHead=True)
    current_events_raw = current_response["events"]
    for event in current_events_raw:
        print(event["message"])
    current_token = current_response["nextForwardToken"]

    next_response = logs_client.get_log_events(logGroupName=TRAFFIC_LOG_GROUP,
                                               logStreamName=TRAFFIC_LOG_STREAM,
                                               startFromHead=True, nextToken=current_token)
    next_token = next_response["nextForwardToken"]

    # Now that the first CW log event was logged.
    # We start checking for new events every now and then, and print new ones, if available.

    while True:
        next_response = logs_client.get_log_events(logGroupName=TRAFFIC_LOG_GROUP,
                                                   logStreamName=TRAFFIC_LOG_STREAM,
                                                   startFromHead=False, nextToken=current_token)
        next_token = next_response["nextForwardToken"]

        if current_token != next_token:

            print("Found new events", file=sys.stderr)
            current_response = next_response
            current_events_raw = current_response["events"]
            for event in current_events_raw:
                print(event["message"])
            current_token = current_response["nextForwardToken"]

            next_response = logs_client.get_log_events(logGroupName=TRAFFIC_LOG_GROUP,
                                                       logStreamName=TRAFFIC_LOG_STREAM,
                                                       startFromHead=False, nextToken=current_token)
            next_token = next_response["nextForwardToken"]

            print("Sleeping for 10 seconds then checking if any additional events are available", file=sys.stderr)

        time.sleep(10)


if __name__ == "__main__":
    main()
