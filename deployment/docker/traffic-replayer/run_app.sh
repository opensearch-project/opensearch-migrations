#!/bin/bash

# Script is in a temporary state to allow some retryability and attempt to avoid race conditions with
# Traffic Comparator (TC) as connections get setup and restarted

target_endpoint="$1"
jars=$(ls extracted/*/*/*.jar | tr \\n :)

while true
do
  # Verify connection with TC can be made before disconnecting via 1 second timeout and moving forward
  while ! nc -v -w 1 localhost 9220
  do
    >&2 echo 'Attempting to connect to Traffic Comparator...'
    sleep 2
  done

  # Allow time for TC to begin listening after terminated connection
  sleep 7

  touch triples.log

  # These commands look a bit backwards but ultimately will receive input from CW-Puller via netcat and then
  # process with the Replayer jar before ultimately printing the produced triples to stdout via tee and sending to TC via netcat
  # Note: Unbuffer has not been verified to actually be effective here. stdbuf was ruled out for its caveat with tee (https://linux.die.net/man/1/stdbuf)
  unbuffer tail -f triples.log | tee /dev/stderr | nc -v localhost 9220 &
  nc -v -l -p 9210 | java -cp build/libs/trafficReplayer.jar:"$jars" org.opensearch.migrations.replay.TrafficReplayer "$target_endpoint" -o triples.log --insecure

  rm triple.log
  >&2 echo "Command has encountered error. Restarting now ..."
  sleep 2
done