#!/bin/sh

python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

DOCKER_IMAGE_BUILTINS=../src/main/docker/otelCollector

python3 consConfigSnippets.py awsCloudWatch awsXRay openSearchAws healthCheck > "${DOCKER_IMAGE_BUILTINS}/otel-config-aws.yaml"
python3 consConfigSnippets.py awsCloudWatch awsXRay openSearchAws healthCheck debugMetricsDetailed debugTracesDetailed debugLogsDetailed > "${DOCKER_IMAGE_BUILTINS}/otel-config-aws-debug.yaml"
python3 consConfigSnippets.py zpages pprof healthCheck debugTracesDetailed debugMetricsDetailed debugTracesDetailed debugLogsDetailed > "${DOCKER_IMAGE_BUILTINS}/otel-config-debug-only.yaml"

COMPOSE_EXTENSIONS=../src/main/docker/composeExtensions/configs
python3 consConfigSnippets.py awsCloudWatch awsXRay prometheus jaeger openSearchLocal zpages pprof healthCheck debugMetricsDetailed debugTracesDetailed debugLogsDetailed > "${COMPOSE_EXTENSIONS}/otel-config-everything.yaml"
python3 consConfigSnippets.py prometheus jaeger openSearchLocal zpages pprof healthCheck debugMetricsDetailed debugTracesDetailed debugLogsDetailed > "${COMPOSE_EXTENSIONS}/otel-config-prometheus-jaeger-opensearch.yaml"

echo Done making files

deactivate
rm -rf .venv