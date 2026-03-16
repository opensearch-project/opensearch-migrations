#!/bin/bash

cd /webhook-trigger || exit
echo "Executing jenkins webhook trigger for url: $INPUT_JENKINS_URL and job: $INPUT_JOB_NAME"
exec python3 default_webhook_trigger.py \
  --jenkins_url="${INPUT_JENKINS_URL}" \
  --pipeline_token="${INPUT_API_TOKEN}" \
  --job_name="${INPUT_JOB_NAME}" \
  --job_params="${INPUT_JOB_PARAMS}" \
  --job_timeout_minutes="${INPUT_JOB_TIMEOUT_MINUTES}"
