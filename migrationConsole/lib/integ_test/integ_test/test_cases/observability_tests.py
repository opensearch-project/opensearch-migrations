from .basic_tests import Test0001SingleDocumentBackfill as _Test0001SingleDocumentBackfill
from .ma_argo_test_base import MATestUserArguments
from ..tracing_operations import assert_jaeger_received_spans, assert_xray_received_spans

TRACE_COLLECTOR_ENDPOINT = "http://otel-trace-collector:4317"
TRACE_SERVICE_NAMES = ("documentMigration",)
__all__ = ["Test0051SingleDocumentBackfillWithJaegerTracing", "Test0052SingleDocumentBackfillWithXRayTracing"]


class TracingSingleDocumentBackfillBase(_Test0001SingleDocumentBackfill):
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args)
        self.description = "Performs single-document backfill with opt-in trace export enabled."

    def prepare_workflow_snapshot_and_migration_config(self):
        super().prepare_workflow_snapshot_and_migration_config()
        for snapshot_config in self.workflow_snapshot_and_migration_config:
            for migration in snapshot_config.get("migrations", []):
                for config_name in ("metadataMigrationConfig", "documentBackfillConfig"):
                    migration.setdefault(config_name, {})["otelTraceCollectorEndpoint"] = TRACE_COLLECTOR_ENDPOINT


class Test0051SingleDocumentBackfillWithJaegerTracing(TracingSingleDocumentBackfillBase):
    def assert_observability(self):
        assert_jaeger_received_spans(
            namespace=self.argo_service.namespace,
            service_names=TRACE_SERVICE_NAMES,
        )


class Test0052SingleDocumentBackfillWithXRayTracing(TracingSingleDocumentBackfillBase):
    def assert_observability(self):
        assert_xray_received_spans(
            namespace=self.argo_service.namespace,
            service_names=TRACE_SERVICE_NAMES,
        )
