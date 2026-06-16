from .basic_tests import Test0001SingleDocumentBackfill as _Test0001SingleDocumentBackfill
from .cdc_simple_bulk_e2e_tests import Test0040CdcFullE2eSimpleBulk as _Test0040CdcFullE2eSimpleBulk
from .cdc_tests import Test0031CdcOnlyLiveTraffic as _Test0031CdcOnlyLiveTraffic
from .ma_argo_test_base import MATestUserArguments
from ..tracing_operations import assert_jaeger_received_spans, assert_xray_received_spans

TRACE_COLLECTOR_ENDPOINT = "http://otel-trace-collector:4317"
BACKFILL_TRACE_SERVICE_NAMES = ("metadata", "documentMigration")
CDC_TRACE_SERVICE_NAMES = ("capture", "replay")
__all__ = [
    "Test0051SingleDocumentBackfillWithJaegerTracing",
    "Test0052SingleDocumentBackfillWithXRayTracing",
    "Test0053CdcFullE2eWithJaegerTracing",
    "Test0054CdcOnlyWithXRayTracing",
]


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
            service_names=BACKFILL_TRACE_SERVICE_NAMES,
        )


class Test0052SingleDocumentBackfillWithXRayTracing(TracingSingleDocumentBackfillBase):
    def assert_observability(self):
        assert_xray_received_spans(
            namespace=self.argo_service.namespace,
            service_names=BACKFILL_TRACE_SERVICE_NAMES,
        )


class Test0053CdcFullE2eWithJaegerTracing(_Test0040CdcFullE2eSimpleBulk):
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args)
        self.description = "Full E2E CDC with opt-in Jaeger trace export enabled."

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.parameters["otel-trace-collector-endpoint"] = TRACE_COLLECTOR_ENDPOINT

    def assert_observability(self):
        assert_jaeger_received_spans(
            namespace=self.argo_service.namespace,
            service_names=CDC_TRACE_SERVICE_NAMES,
        )


class Test0054CdcOnlyWithXRayTracing(_Test0031CdcOnlyLiveTraffic):
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args)
        self.description = "CDC-only live traffic capture and replay with opt-in X-Ray trace export enabled."

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.parameters["otel-trace-collector-endpoint"] = TRACE_COLLECTOR_ENDPOINT

    def assert_observability(self):
        assert_xray_received_spans(
            namespace=self.argo_service.namespace,
            service_names=CDC_TRACE_SERVICE_NAMES,
        )
