ARG BASE_IMAGE=docker.io/grafana/grafana:11.6.1
FROM ${BASE_IMAGE} # NOSONAR - version tag is in the ARG default above

COPY datasources.yaml /usr/share/grafana/conf/provisioning/datasources/
