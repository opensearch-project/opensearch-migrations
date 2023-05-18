#!/bin/bash

backside_host="$1"
backside_port="$2"
bind_port="$3"
jars=$(ls extracted/*/*/*.jar | tr \\n :)

cd trafficCaptureProxyServer
java -Xmx2g -cp build/libs/trafficCaptureProxyServer.jar:"$jars" org.opensearch.migrations.trafficcapture.proxyserver.Main -h $backside_host -p $backside_port -b $bind_port #backside-port $(BACKSIDE_PORT) same for backsidehost and port to bind
