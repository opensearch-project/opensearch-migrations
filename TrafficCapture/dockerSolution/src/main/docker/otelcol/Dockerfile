FROM openjdk:11-jre

COPY ./otelcontribcol_linux_arm64 otelcontribcol
RUN apt-get update && apt-get install -y vim
RUN chmod +x otelcontribcol
COPY ./otel-config.yml /etc/otel-config.yml
CMD ./otelcontribcol --config /etc/otel-config.yml
