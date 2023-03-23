import sys
import time
import requests
import os
import boto3
import git
import shutil
import shell_interactions
import subprocess


print(f"App started with arguments: {sys.argv}")

def main():
    env_endpoint = os.environ['MIGRATION_ENDPOINT']
    print(f"Environment variable for endpoint is: {env_endpoint}")

    cw_arn = os.environ['SOURCE_CW_LG_ARN']
    print(f"Environment variable for cw log group arn is: {cw_arn}")

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


def pull_repos():
    try:
        shutil.rmtree("./repos")
    except OSError as e:
        print("Error: %s - %s." % (e.filename, e.strerror))

    git.Repo.clone_from(url="https://github.com/okhasawn/opensearch-migrations.git", to_path="./repos/pull_logs", branch="PullCW")
    git.Repo.clone_from(url="https://github.com/okhasawn/opensearch-migrations.git", to_path="./repos/build_replayer", branch="replayerBuild")
    shell_interactions.call_shell_command("gradle assemble -p=./repos/build_replayer/TrafficReplayer")

    #fileName = "./repos/TrafficReplayer/pull_then_poll_cw_logs.py"
    #shell_interactions.call_shell_command("python3 ./repos/TrafficReplayer/pull_then_poll_cw_logs.py arn:aws:logs:us-east-1:730935383469:log-group:/aws/lambda/OSServiceDomainCDKStack-D-AWS679f53fac002430cb0da5-Iau2iPVlAfXJ")
    p1 = subprocess.Popen(["python3",  "./repos/pull_logs/TrafficReplayer/pull_then_poll_cw_logs.py", "arn:aws:logs:us-east-1:730935383469:log-group:/aws/lambda/OSServiceDomainCDKStack-D-AWS679f53fac002430cb0da5-Iau2iPVlAfXJ"], stdout=subprocess.PIPE)
    p2 = subprocess.Popen(["java", "-cp", "./repos/build_replayer/TrafficReplayer/build/libs/TrafficReplayer.jar", "org.opensearch.migrations.replay.TrafficReplayer"], stdin=p1.stdout, stdout=sys.stdout)
    p2.communicate()

    #exec(compile(open(fileName, "rb").read(), fileName, 'exec'))
    #exec(open(fileName).read())

#main()
pull_repos()