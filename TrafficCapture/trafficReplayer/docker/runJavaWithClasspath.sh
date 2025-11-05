#!/bin/sh
# By default enable transparent proxy with older clients for maximum compatibility
java \
  -XX:MaxRAMPercentage=80.0 \
  -XX:+ExitOnOutOfMemoryError \
  -XshowSettings:vm \
  -Dio.netty.handler.codec.http.defaultStrictLineParsing=false \
  -cp "@/app/jib-classpath-file" \
  org.opensearch.migrations.replay.TrafficReplayer \
  "$@"
