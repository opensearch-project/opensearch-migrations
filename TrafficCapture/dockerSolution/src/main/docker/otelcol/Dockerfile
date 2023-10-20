FROM openjdk:11-jre

COPY ./otelcontribcol_linux_arm64 otelcontribcol
COPY ./requirements.txt requirements.txt
RUN apt-get update && apt-get install -y vim python3 python3-pip && pip install -r requirements.txt
RUN chmod +x otelcontribcol
COPY ./logging_test.py logging_test.py
COPY ./otel-config.yml /etc/otel-config.yml
CMD ./otelcontribcol --config /etc/otel-config.yml; sleep 30 && python3 logging_test.py
