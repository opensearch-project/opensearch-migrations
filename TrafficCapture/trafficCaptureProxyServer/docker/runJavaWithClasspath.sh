#!/bin/sh
# Application defaults — overridable via JDK_JAVA_OPTIONS
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-XX:MaxRAMPercentage=80.0 -XX:+ExitOnOutOfMemoryError -XshowSettings:vm -Dio.netty.handler.codec.http.defaultStrictLineParsing=false"

java \
  -cp "@/app/jib-classpath-file" \
  org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy \
  "$@"
