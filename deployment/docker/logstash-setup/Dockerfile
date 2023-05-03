# syntax=docker/dockerfile:1
FROM opensearchproject/logstash-oss-with-opensearch-output-plugin:7.13.4

COPY ./run_app.sh .

#ARG LOGSTASH_CONFIG
#ENV LOGSTASH_CONFIG ${LOGSTASH_CONFIG}

CMD ./run_app.sh