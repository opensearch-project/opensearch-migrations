import sys
import time
import requests
import os
import boto3

print(f"App started with arguments: {sys.argv}")

env_endpoint = os.environ['MIGRATION_ENDPOINT']
print(f"Environment variable is: {env_endpoint}")

cw_arn = "arn:aws:logs:us-east-1:730935383469:log-group:/aws/lambda/OSServiceDomainCDKStack-D-AWS679f53fac002430cb0da5-Iau2iPVlAfXJ:*"

cw_client = boto3.client('logs')
while True:
    try:
        request_endpoint = env_endpoint + "/_cat/nodes?v"
        print(f"Making a GET request on {request_endpoint}")
        r = requests.get(request_endpoint)
        print("Response received is: " + str(r))
        cw_response = cw_client.describe_log_streams(
            logGroupIdentifier=cw_arn
        )
        print("CW response received is: " + str(cw_response))
    except Exception as e:
        print("Request failed with error: " + str(e))
    time.sleep(15)