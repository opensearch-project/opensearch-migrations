FROM public.ecr.aws/a0w2c5q7/otelcol-with-opensearch:amd-latest
#FROM public.ecr.aws/aws-observability/aws-otel-collector:v0.37.0

COPY otel-config*.yaml /etc/
COPY exportCredsAndRun.sh /
RUN chmod ugo+x exportCredsAndRun.sh

# Make this image consistent with the AWS Distro for OpenTelemetry.  That
# is a leaner (and official) image that still has what we need, except for
# the OpenSearch logs exporter.
RUN useradd -m aoc
USER aoc
ENTRYPOINT ["./exportCredsAndRun.sh", "./otelcontribcol"]
CMD ["--config", "/etc/otel-config-debug-only.yaml"]
