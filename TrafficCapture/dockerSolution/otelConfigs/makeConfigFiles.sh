#!/bin/sh

pipenv install

DOCKER_IMAGE_BUILTINS=../src/main/docker/otelCollector

pipenv run python3 consConfigSnippets.py awsCloudWatch awsXRay healthCheck > "${DOCKER_IMAGE_BUILTINS}/otel-config-aws.yaml"
pipenv run python3 consConfigSnippets.py awsCloudWatch awsXRay healthCheck debugMetricsDetailed debugTracesDetailed debugLogsDetailed > "${DOCKER_IMAGE_BUILTINS}/otel-config-aws-debug.yaml"
pipenv run python3 consConfigSnippets.py zpages pprof healthCheck debugTracesDetailed debugMetricsDetailed debugTracesDetailed debugLogsDetailed > "${DOCKER_IMAGE_BUILTINS}/otel-config-debug-only.yaml"

COMPOSE_EXTENSIONS=../src/main/docker/composeExtensions/configs
pipenv run python3 consConfigSnippets.py awsCloudWatch awsXRay prometheus jaeger zpages pprof healthCheck debugMetricsDetailed debugTracesDetailed debugLogsDetailed > "${COMPOSE_EXTENSIONS}/otel-config-everything.yaml"
pipenv run python3 consConfigSnippets.py prometheus jaeger zpages pprof healthCheck debugMetricsDetailed debugTracesDetailed debugLogsDetailed > "${COMPOSE_EXTENSIONS}/otel-config-prometheus-jaeger.yaml"

echo Done making files