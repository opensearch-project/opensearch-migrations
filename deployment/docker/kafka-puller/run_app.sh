#!/bin/bash

bootstrap_server="$1"
topic_name="$2"
./gradlew build
# The below variable will contain the classpath for all the jar dependencies.
jars=$(ls extracted/*/*/*.jar | tr \\n :)

while true
do
  nc -v -l -p 9220 |  java -cp build/libs/KafkaPrinter.jar:"$jars" org.opensearch.KafkaPrinter -o "$target_endpoint" "$topic_name"
  >&2 echo "Command has encountered error. Restarting now ..."
  sleep 1
done