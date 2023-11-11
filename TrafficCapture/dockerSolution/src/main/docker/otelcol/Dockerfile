FROM public.ecr.aws/a0w2c5q7/otelcol-with-opensearch:latest

COPY ./otel-config.yml /etc/otel-config.yml
# CMD ./otelcontribcol --config /etc/otel-config.yml
ENTRYPOINT ["tail", "-f", "/dev/null"]