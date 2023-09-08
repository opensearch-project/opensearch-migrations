#!/bin/sh

# This script is used to set up the AWS Credentials required by the CloudWatch Agent inside of the demo container.
# It accepts the creds via ENV variables supplied to the Docker CLI "run" invocation originally set in the shell
# context the user invoked the demo script from.
#
# This approach bypasses a number of pitfalls, namely:
# 1) It avoids embedding demo-specific stuff into the Dockerfiles
# 2) It avoids logging the credentials or otherwise writing them to disk on the localhost
# 3) It is less painful than trying to execute the commands via a Docker CMD override
echo "Writing AWS Creds to /root/.aws/credentials..."

mkdir /root/.aws

touch /root/.aws/credentials
echo "[AmazonCloudWatchAgent]" >> /root/.aws/credentials
echo "aws_access_key_id = $AWS_ACCESS_KEY_ID" >> /root/.aws/credentials
echo "aws_secret_access_key = $AWS_SECRET_ACCESS_KEY" >> /root/.aws/credentials
echo "aws_session_token = $AWS_SESSION_TOKEN" >> /root/.aws/credentials

touch /root/.aws/config
echo "\n[profile AmazonCloudWatchAgent]" >> /root/.aws/credentials
echo "region = $AWS_REGION" >> /root/.aws/credentials

echo "AWS Creds written to /root/.aws/credentials"
echo "Running HAProxy now..."

service rsyslog start
/opt/aws/amazon-cloudwatch-agent/bin/start-amazon-cloudwatch-agent &
haproxy -f /usr/local/etc/haproxy/haproxy.cfg
