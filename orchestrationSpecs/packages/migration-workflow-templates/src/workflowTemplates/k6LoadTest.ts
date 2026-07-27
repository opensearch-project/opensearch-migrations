import {
    configMapKey,
    defineParam,
    expr,
    IMAGE_PULL_POLICY,
    typeToken,
    WorkflowBuilder,
} from "@opensearch-migrations/argo-workflow-builders";

// Standalone, directly-submittable k6 load-test workflow (see TrafficCapture/trafficLoadTest).
//
// A run is specified by two names baked into the migrations/k6 image: `scenario` (the script) and
// `configName` (a k6-config/*.env preset). Every preset value is overridable per run — named
// params (rate/duration/vus) plus a newline-separated KEY=VALUE `overrides` bag applied last. It is
// submitted independently (argo submit --from / the `workflow k6` CLI + TUI), NOT as a step of a
// migration workflow, so it never affects a migration.
//
// The image is resolved from the migration-image-config ConfigMap on the TEMPLATE INPUT — Argo
// honors valueFrom.configMapKeyRef on template inputs (unlike a Workflow's global spec.arguments,
// which are not evaluated under workflowTemplateRef; see commonUtils/workflowParameters.ts). The
// k6Image/k6PullPolicy keys and images.k6 chart values already exist, so no chart change is needed.
// (We intentionally do NOT add "K6" to LogicalOciImages: defaultImagesMap iterates every key and
// would add unused image inputs to fullMigration's templates and churn their snapshots.)
export const K6LoadTest = WorkflowBuilder.create({
    k8sResourceName: "k6-load-test",
    parallelism: 100,
    serviceAccountName: "argo-workflow-executor",
})
    .addParams({
        // Run selection + target
        scenario: defineParam({expression: "ingest"}),            // ingest | search | mixed
        configName: defineParam({expression: "ingest-steady"}),   // any k6-config/*.env preset
        targetUrl: defineParam({expression: ""}),                 // Capture Proxy URL; empty = preset default
        // Named convenience overrides (empty = keep the preset)
        rate: defineParam({expression: ""}),
        duration: defineParam({expression: ""}),
        vus: defineParam({expression: ""}),
        // Opt-in features (empty = keep the preset)
        registryEnabled: defineParam({expression: ""}),
        controlEnabled: defineParam({expression: ""}),
        webdisUrl: defineParam({expression: ""}),
        // Generic override bag (one KEY=VALUE per line) + extra `k6 run` flags
        overrides: defineParam({expression: ""}),
        extraArgs: defineParam({expression: ""}),
        // ConfigMap holding k6Image / k6PullPolicy
        imageConfigMapName: defineParam({expression: "migration-image-config"}),
    })
    .addTemplate("run", t => t
        // Resolved from migration-image-config at run time (Argo honors configMapKeyRef on template
        // inputs). No static fallback, matching defaultImagesMap — the chart always renders the keys.
        .addInputsFromRecord({
            imageK6Location: defineParam({
                type: typeToken<string>(),
                from: configMapKey(t.inputs.workflowParameters.imageConfigMapName, "k6Image"),
            }),
            imageK6PullPolicy: defineParam({
                type: typeToken<IMAGE_PULL_POLICY>(),
                from: configMapKey(t.inputs.workflowParameters.imageConfigMapName, "k6PullPolicy"),
            }),
        })
        .addContainer(b => b
            .addImageInfo(b.inputs.imageK6Location, b.inputs.imageK6PullPolicy)
            .addResources({
                requests: {cpu: "500m", memory: "512Mi"},
                limits: {cpu: "2", memory: "1Gi"},
            })
            // grafana/k6's default ENTRYPOINT is ["k6"]; override with a POSIX-sh wrapper that
            // sources the preset, applies overrides in precedence order, then execs k6.
            .addCommand(["/bin/sh", "-c"])
            .addEnvVarsFromRecord({
                // Metrics: OTLP gRPC to the existing otel-collector DaemonSet Service.
                K6_OTEL_GRPC_EXPORTER_ENDPOINT: expr.literal("otel-collector:4317"),
                K6_OTEL_GRPC_EXPORTER_INSECURE: expr.literal("true"),
                // SCRIPT_NAME, not SCENARIO — the scripts read __ENV.SCENARIO for the document type.
                SCRIPT_NAME: t.inputs.workflowParameters.scenario,
                CONFIG_NAME: t.inputs.workflowParameters.configName,
                // Dedicated params consumed post-source under their real names (see wrapper).
                TARGET_URL: t.inputs.workflowParameters.targetUrl,
                RATE_OVERRIDE: t.inputs.workflowParameters.rate,
                DURATION_OVERRIDE: t.inputs.workflowParameters.duration,
                VUS_OVERRIDE: t.inputs.workflowParameters.vus,
                OVERRIDES: t.inputs.workflowParameters.overrides,
                EXTRA_ARGS: t.inputs.workflowParameters.extraArgs,
                REGISTRY_ENABLED_PARAM: t.inputs.workflowParameters.registryEnabled,
                CONTROL_ENABLED_PARAM: t.inputs.workflowParameters.controlEnabled,
                WEBDIS_URL_PARAM: t.inputs.workflowParameters.webdisUrl,
            })
            .addArgs([`
set -eu
CFG="/scripts/k6-config/\${CONFIG_NAME}.env"
set -a
# 1. baked-in preset = defaults (the preset files set CAPTURE_PROXY_URL / WEBDIS_URL / etc.)
if [ -f "$CFG" ]; then . "$CFG"; else echo "no such config: $CFG" >&2; exit 2; fi
# 2. named convenience overrides (empty = keep the preset)
[ -n "\${DURATION_OVERRIDE:-}" ] && DURATION="$DURATION_OVERRIDE"
[ -n "\${RATE_OVERRIDE:-}" ]     && { INGEST_RATE="$RATE_OVERRIDE"; SEARCH_RATE="$RATE_OVERRIDE"; }
[ -n "\${VUS_OVERRIDE:-}" ]      && { INGEST_VUS="$VUS_OVERRIDE"; SEARCH_VUS="$VUS_OVERRIDE"; }
# 3. dedicated params (empty = keep the preset). Applied AFTER sourcing so they win over it.
[ -n "\${TARGET_URL:-}" ]             && CAPTURE_PROXY_URL="$TARGET_URL"
[ -n "\${WEBDIS_URL_PARAM:-}" ]       && WEBDIS_URL="$WEBDIS_URL_PARAM"
[ -n "\${REGISTRY_ENABLED_PARAM:-}" ] && REGISTRY_ENABLED="$REGISTRY_ENABLED_PARAM"
[ -n "\${CONTROL_ENABLED_PARAM:-}" ]  && CONTROL_ENABLED="$CONTROL_ENABLED_PARAM"
# 4. generic override bag (one KEY=VALUE per line) — applied last, so it wins over all.
#    Here-doc (not a pipe) keeps the loop in this shell so exports persist to exec.
while IFS= read -r kv; do
  [ -n "$kv" ] && export "$kv"
done <<OVR
\${OVERRIDES}
OVR
set +a
echo "k6: script=\${SCRIPT_NAME} config=\${CONFIG_NAME} target=\${CAPTURE_PROXY_URL:-<unset>}"
exec k6 run --out=opentelemetry \${EXTRA_ARGS} "/scripts/scenarios/\${SCRIPT_NAME}.js"
`])
        )
    )
    .setEntrypoint("run")
    .getFullScope();
