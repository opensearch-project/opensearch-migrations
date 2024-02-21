FROM public.ecr.aws/aws-observability/aws-otel-collector:v0.37.0

COPY otel-config*.yaml /etc/
CMD ["--config", "/etc/otel-config-debug-only.yaml"]
