FROM public.ecr.aws/aws-observability/aws-otel-collector:v0.38.0

COPY otel-config*.yaml /etc/
CMD ["--config", "/etc/otel-config-debug-only.yaml"]
HEALTHCHECK --interval=5s --timeout=3s --start-period=5s --retries=2 CMD [ "/healthcheck" ]
