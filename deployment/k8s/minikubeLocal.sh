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

  minikube start
  minikube mount .:/opensearch-migrations > /dev/null 2>&1 &
}

pause() {
  kill_minikube_processes
  minikube pause
}

delete() {
  kill_minikube_processes
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

