java \
  -XX:MaxRAMPercentage=80.0 \
  -XX:+ExitOnOutOfMemoryError \
  -XshowSettings:vm \
  -cp "@/app/jib-classpath-file" \
  org.opensearch.migrations.trafficcapture.proxyserver.CaptureProxy \
  "$@"
