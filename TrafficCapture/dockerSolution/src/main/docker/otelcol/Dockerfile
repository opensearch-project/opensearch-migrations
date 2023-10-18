FROM openjdk:11-jre

COPY ./otel-config.yml /etc/otel-config.yml
COPY ./otelcontribcol_linux_arm64 otelcontribcol
RUN chmod +x otelcontribcol
CMD ./otelcontribcol --config /etc/otel-config.yml
