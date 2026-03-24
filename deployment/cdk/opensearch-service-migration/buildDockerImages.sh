#!/bin/bash

# Allow executing this script from any dir
script_abs_path=$(readlink -f "$0")
script_dir_abs_path=$(dirname "$script_abs_path")
cd "$script_dir_abs_path" || exit

cd ../../.. || exit

# Pass ECR pull-through cache endpoint to Gradle if available
PTC_ARG=""
if [ -n "$ECR_PULL_THROUGH_ENDPOINT" ]; then
  PTC_ARG="-PpullThroughCacheEndpoint=$ECR_PULL_THROUGH_ENDPOINT"
fi

./gradlew :buildDockerImages -x test $PTC_ARG "$@"
