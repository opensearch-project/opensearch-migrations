ARG BASE_IMAGE=public.ecr.aws/aws-observability/aws-otel-collector:v0.43.3
FROM ${BASE_IMAGE} # NOSONAR - version tag is in the ARG default above

COPY otel-config*.yaml /etc/
CMD ["--config", "/etc/otel-config-debug-only.yaml"]
HEALTHCHECK --interval=5s --timeout=3s --start-period=5s --retries=2 CMD [ "/healthcheck" ]
