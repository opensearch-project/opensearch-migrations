#!/usr/bin/env python3
import os
import time
import sys

import boto3

DEFAULT_REGION = "us-east-1"
DEFAULT_LOG_GROUP = sys.argv[1]
DEFAULT_LOG_STREAM = sys.argv[2]
DEFAULT_FILE = "/tmp/logfile.log"


def main():
    logs_client = boto3.client(
        'logs',
        aws_access_key_id=os.environ["AWS_ACCESS_KEY_ID"],
        aws_secret_access_key=os.environ["AWS_SECRET_ACCESS_KEY"],
        aws_session_token=os.environ["AWS_SESSION_TOKEN"],
        region_name=DEFAULT_REGION
    )

    messages = []

    # Continuously get events and add them to our final list until we see a repeat of the "nextForwardToken"
    # Then script will start checking periodically
    print(f"Pulling CW events from {DEFAULT_LOG_GROUP}:{DEFAULT_LOG_STREAM}...")
    current_response = logs_client.get_log_events(logGroupName=DEFAULT_LOG_GROUP,
                                                  logStreamName=DEFAULT_LOG_STREAM, startFromHead=True)
    current_events_raw = current_response["events"]
    messages.extend([event["message"] for event in current_events_raw])
    current_token = current_response["nextForwardToken"]

    next_response = logs_client.get_log_events(logGroupName=DEFAULT_LOG_GROUP,
                                               logStreamName=DEFAULT_LOG_STREAM,
                                               startFromHead=True, nextToken=current_token)
    next_token = next_response["nextForwardToken"]

    while current_token != next_token:
        print("More events to pull...")
        current_response = next_response
        current_events_raw = current_response["events"]
        messages.extend([event["message"] for event in current_events_raw])
        current_token = current_response["nextForwardToken"]

        next_response = logs_client.get_log_events(logGroupName=DEFAULT_LOG_GROUP,
                                                   logStreamName=DEFAULT_LOG_STREAM,
                                                   startFromHead=True, nextToken=current_token)
        next_token = next_response["nextForwardToken"]

    print(f"Pulled {len(messages)} events")

    print(f"Writing events to file: {DEFAULT_FILE}")
    with open(DEFAULT_FILE, 'w') as output_file:
        output_file.write("\n".join(messages))
        output_file.write("\n")

    # Now that all currently available CloudWatch events were logged.
    # We start checking for new events every now and then, and append new ones to already existing file, if available

    while True:
        messages.clear()

        next_response = logs_client.get_log_events(logGroupName=DEFAULT_LOG_GROUP,
                                                   logStreamName=DEFAULT_LOG_STREAM,
                                                   startFromHead=False, nextToken=current_token)
        next_token = next_response["nextForwardToken"]

        if current_token != next_token:
            current_response = next_response
            current_events_raw = current_response["events"]
            messages.extend([event["message"] for event in current_events_raw])
            current_token = current_response["nextForwardToken"]

            next_response = logs_client.get_log_events(logGroupName=DEFAULT_LOG_GROUP,
                                                       logStreamName=DEFAULT_LOG_STREAM,
                                                       startFromHead=False, nextToken=current_token)
            next_token = next_response["nextForwardToken"]

            if len(messages):
                print(f"Found new log events. Writing events to file: {DEFAULT_FILE} - Checking again in 10 seconds")
                with open(DEFAULT_FILE, 'a') as output_file:
                    output_file.write("\n".join(messages))

        if not len(messages):
            print("No new log events found, sleeping for 10 seconds then checking again")

        time.sleep(10)


if __name__ == "__main__":
    main()
