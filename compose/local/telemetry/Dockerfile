FROM public.ecr.aws/a0w2c5q7/otelcol-with-opensearch:amd-latest
FROM public.ecr.aws/a0w2c5q7/otelcol-with-opensearch:latest

#COPY ./otel-config-cdk.yml /etc/otel-config.yml
COPY ./TrafficCapture/dockerSolution/src/main/docker/otelcol/otel-config-cdk.yml /etc/otel-config.yml
ENTRYPOINT ["./otelcontribcol", "--config", "/etc/otel-config.yml"]