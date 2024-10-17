#!/bin/bash

# To have running during local development

# Allow executing this script from any dir
script_abs_path=$(readlink -f "$0")
script_dir_abs_path=$(dirname "$script_abs_path")
cd "$script_dir_abs_path" || exit

cd ../.. || exit

helm repo add opensearch-operator https://opensearch-project.github.io/opensearch-k8s-operator/
helm repo add strimzi https://strimzi.io/charts/

minikube mount .:/opensearch-migrations > /dev/null 2>&1 &
minikube tunnel > /dev/null 2>&1 &
