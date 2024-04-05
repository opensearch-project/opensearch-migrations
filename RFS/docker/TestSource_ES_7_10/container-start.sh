#!/bin/bash

echo "Setting AWS Creds from ENV Variables"
bin/elasticsearch-keystore create
echo $AWS_ACCESS_KEY_ID | bin/elasticsearch-keystore add s3.client.default.access_key --stdin
echo $AWS_SECRET_ACCESS_KEY | bin/elasticsearch-keystore add s3.client.default.secret_key --stdin

if [ -n "$AWS_SESSION_TOKEN" ]; then
    echo $AWS_SESSION_TOKEN | bin/elasticsearch-keystore add s3.client.default.session_token --stdin
fi

echo "Starting Elasticsearch"
/usr/local/bin/docker-entrypoint.sh eswrapper