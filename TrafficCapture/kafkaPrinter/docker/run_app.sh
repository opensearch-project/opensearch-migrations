#!/bin/bash

bootstrap_servers="$1"
topic_name="$2"

# The below variable will contain the classpath for all the jar dependencies.
jars=$(ls extracted/*/*/*.jar | tr \\n :)

while true
do

  # Verify netcat can connect to Replayer before process begins
  while ! nc -v -w 1 traffic-replayer 9210
  do
    >&2 echo "Attempting to connect to Traffic Replayer..."
    sleep 2
  done
  sleep 30

  java -cp kafkaPrinter.jar:"$jars" org.opensearch.migrations.KafkaPrinter -b "$bootstrap_servers" -t "$topic_name" -g "default-logging-group" | tee /dev/stderr | nc -v traffic-replayer 9210
  >&2 echo "Command has encountered error. Restarting now ..."
  sleep 1
done