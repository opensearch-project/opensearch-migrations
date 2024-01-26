#!/bin/bash

# Allow executing this script from any dir
script_abs_path=$(readlink -f "$0")
script_dir_abs_path=$(dirname "$script_abs_path")
cd $script_dir_abs_path || exit

cd ../../../FetchMigration || exit
docker build -t migrations/fetch-migration .

cd ../TrafficCapture || exit
./gradlew :dockerSolution:buildDockerImages -x test