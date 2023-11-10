FROM public.ecr.aws/a0w2c5q7/otelcol-with-opensearch:latest

COPY ./otel-config.yml /etc/otel-config.yml
RUN apt-get install file -y
RUN file otelcontribcol
RUN echo "hello world"
# CMD ./otelcontribcol --config /etc/otel-config.yml
ENTRYPOINT ["tail", "-f", "/dev/null"]