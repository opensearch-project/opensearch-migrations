#!/bin/bash

target_endpoint="$1"

# Receive netcat input over port and pipe to STDOUT(debugging) and replayer jar
#nc -l localhost 9210 | java -jar TrafficReplayer-uber-0.1.0.jar  "$target_endpoint"
nc localhost 9210 | tee >(java -jar TrafficReplayer-uber-0.1.0.jar  "$target_endpoint")
