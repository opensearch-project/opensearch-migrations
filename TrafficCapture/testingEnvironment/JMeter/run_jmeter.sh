#!/bin/bash

domain_name="$1"
bind_port="$2"

cd trafficCaptureProxyServerTest
jars=$(ls extracted/*/*/*.jar | tr \\n :)

while true
do
  java -cp build/libs/trafficCaptureProxyServerTest.jar:"$jars" org.opensearch.migrations.trafficcapture.JMeterLoadTest -p $bind_port -d $domain_name
done

