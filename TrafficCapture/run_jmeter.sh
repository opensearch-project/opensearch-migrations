#!/bin/bash

domain_name="$1"
bind_port="$2"

jars=$(ls extracted/*/*/*.jar | tr \\n :)

cd trafficCaptureProxyServerTest
#while true
#do
java -cp build/libs/trafficCaptureProxyServerTest.jar:"$jars" org.opensearch.migrations.trafficcapture.JMeterLoadTest -p $bind_port -d $domain_name
#done

