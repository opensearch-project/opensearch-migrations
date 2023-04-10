# syntax=docker/dockerfile:1
FROM opensearchproject/logstash-oss-with-opensearch-output-plugin:8.4.0

COPY ./run_app.sh .

#ARG LOGSTASH_CONFIG
#ENV LOGSTASH_CONFIG ${LOGSTASH_CONFIG}

CMD ./run_app.sh