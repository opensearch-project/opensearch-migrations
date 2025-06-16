#!/bin/bash

# Wrapper around setting up Minikube

usage() {
  echo "Usage: $0 [--start | --pause | --delete]"
  exit 1
}

kill_minikube_processes() {
  mount_process_id=$(pgrep -f "minikube mount")
  if [ -n "$mount_process_id" ]; then
    kill "$mount_process_id"
  fi
}

start() {
  helm repo add opensearch-operator https://opensearch-project.github.io/opensearch-k8s-operator/
  helm repo add strimzi https://strimzi.io/charts/

  # Development setup to allow using an insecure registry
  minikube start --insecure-registry="0.0.0.0/0"
  minikube mount .:/opensearch-migrations > /dev/null 2>&1 &
  if ! docker network inspect minikube --format '{{range .Containers}}{{.Name}} {{end}}' | grep -qw docker-registry; then
    docker network connect minikube docker-registry 2>/dev/null || echo "⚠️  Warning: Could not connect docker-registry to minikube network"
  else
    echo "ℹ️  docker-registry is already connected to minikube network"
  fi
}

pause() {
  kill_minikube_processes
  minikube pause
}

delete() {
  kill_minikube_processes
  if docker network inspect minikube --format '{{range .Containers}}{{.Name}} {{end}}' | grep -qw docker-registry; then
    docker network disconnect minikube docker-registry 2>/dev/null || echo "⚠️  Warning: Could not disconnect docker-registry from minikube network"
  else
    echo "ℹ️  docker-registry is not connected to minikube network"
  fi
  minikube delete
}

# Check if the script was called with no arguments
if [ $# -eq 0 ]; then
    usage
fi

# Allow executing this script from any dir
script_abs_path=$(readlink -f "$0")
script_dir_abs_path=$(dirname "$script_abs_path")
cd "$script_dir_abs_path" || exit

cd ../.. || exit

# Parse the argument and call the appropriate function
case "$1" in
    --start)
        start
        ;;
    --pause)
        pause
        ;;
    --delete)
        delete
        ;;
    *)
        echo "Invalid option: $1"
        usage
        ;;
esac

