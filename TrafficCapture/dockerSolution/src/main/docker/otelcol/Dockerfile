FROM public.ecr.aws/a0w2c5q7/otelcol-with-opensearch:amd-latest

COPY ./otel-config-cdk.yml /etc/otel-config.yml
ENTRYPOINT ["./otelcontribcol", "--config", "/etc/otel-config.yml"]