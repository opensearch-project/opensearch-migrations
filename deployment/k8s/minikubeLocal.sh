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
  # Development setup to allow using an insecure registry
  TMP_OUTPUT=$(mktemp)

  minikube start --insecure-registry="0.0.0.0/0" 2>&1 | tee "$TMP_OUTPUT"
  EXIT_CODE=${PIPESTATUS[0]}

  if [[ $EXIT_CODE -ne 0 ]]; then
      if grep -qE "can't create with that IP, address already in use|apiserver process never appeared" "$TMP_OUTPUT"; then
          echo ""
          echo "🔧  Minikube network may not have been completely cleaned up. You may need to run:"
          echo "    ./minikubeLocal.sh --delete"
      fi

      echo ""
      echo "❌  Minikube failed to start. Check the output above for details."
      rm -f "$TMP_OUTPUT"
      exit "$EXIT_CODE"
  fi

  rm -f "$TMP_OUTPUT"
  echo "✅  Minikube started successfully!"

  minikube mount .:/opensearch-migrations > /dev/null 2>&1 &
  
  # Mount AWS credentials for local development (host ~/.aws → VM /aws-credentials)
  # Kyverno will then mount this into containers at /root/.aws
  if [ -d "$HOME/.aws" ]; then
    minikube mount "$HOME/.aws":/aws-credentials > /dev/null 2>&1 &
    echo "🔑  AWS credentials mounted for local development"
  else
    echo "⚠️  Warning: No ~/.aws directory found. AWS credentials will not be available in containers."
  fi
  
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
