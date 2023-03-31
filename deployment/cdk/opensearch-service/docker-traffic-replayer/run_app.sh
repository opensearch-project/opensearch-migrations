#!/bin/bash

target_endpoint="$1"

# Receive netcap input over port and pipe to STDOUT(debugging) and replayer jar
nc localhost 9210 | tee >(java -jar TrafficReplayer-uber-0.1.0.jar  "$target_endpoint")

#while :
#do
#	echo "Press [CTRL+C] to stop.."
#	sleep 1
#done