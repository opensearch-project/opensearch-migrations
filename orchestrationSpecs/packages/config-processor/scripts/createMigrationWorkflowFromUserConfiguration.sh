#!/bin/bash

set -euo pipefail
if [[ "${WORKFLOW_SUBMIT_TRACE:-}" == "1" ]]; then
    set -x
fi

# Check if config filename argument is provided
if [ $# -eq 0 ]; then
    echo "Error: CONFIG_FILENAME argument is required"
    echo "Usage: $0 <config-filename> [additional-args...]"
    exit 1
fi

CONFIG_FILENAME=$1
shift  # Remove first argument, leaving any additional args in $@

# unique-run-nonce is only used as the suffix for auto-created snapshot names.
# It is not part of INITIALIZE_CMD input, so filter it out while preserving all
# other arguments for the config processor.
RUN_NONCE=""
QUIET=0
WORKFLOW_NAME="migration-workflow"
WORKFLOW_NAMESPACE=""
DRY_RUN=0
OUTPUT_FORMAT="text"
PREPARE_ONLY_DIR=""
COMMIT_PREPARED_DIR=""
ALL_ARGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --quiet)
            QUIET=1
            shift
            ;;
        --verbose)
            QUIET=0
            shift
            ;;
        --unique-run-nonce)
            if [[ $# -lt 2 ]]; then
                echo "Error: --unique-run-nonce requires a value" >&2
                exit 1
            fi
            RUN_NONCE="$2"
            shift 2
            ;;
        --workflow-name)
            if [[ $# -lt 2 || -z "$2" ]]; then
                echo "Error: --workflow-name requires a value" >&2
                exit 1
            fi
            WORKFLOW_NAME="$2"
            shift 2
            ;;
        --namespace)
            if [[ $# -lt 2 || -z "$2" ]]; then
                echo "Error: --namespace requires a value" >&2
                exit 1
            fi
            WORKFLOW_NAMESPACE="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        --output)
            if [[ $# -lt 2 || ( "$2" != "text" && "$2" != "json" ) ]]; then
                echo "Error: --output requires text or json" >&2
                exit 1
            fi
            OUTPUT_FORMAT="$2"
            shift 2
            ;;
        --prepare-only)
            if [[ $# -lt 2 || -z "$2" ]]; then
                echo "Error: --prepare-only requires a directory" >&2
                exit 1
            fi
            PREPARE_ONLY_DIR="$2"
            shift 2
            ;;
        --commit-prepared)
            if [[ $# -lt 2 || -z "$2" ]]; then
                echo "Error: --commit-prepared requires a directory" >&2
                exit 1
            fi
            COMMIT_PREPARED_DIR="$2"
            shift 2
            ;;
        *)
            ALL_ARGS+=("$1")
            shift
            ;;
    esac
done

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${NODEJS:=node}"

# Default command, can be overridden by setting INITIALIZE_CMD environment variable
: ${INITIALIZE_CMD:="$NODEJS $SCRIPT_DIR/index.js initialize"}
: ${PREFLIGHT_CMD:="$NODEJS $SCRIPT_DIR/index.js preflightSubmission"}

if [[ -n "$PREPARE_ONLY_DIR" && -n "$COMMIT_PREPARED_DIR" ]]; then
    echo "Error: --prepare-only and --commit-prepared cannot be combined" >&2
    exit 1
fi
if [[ "$DRY_RUN" == "1" && -n "$COMMIT_PREPARED_DIR" ]]; then
    echo "Error: --dry-run and --commit-prepared cannot be combined" >&2
    exit 1
fi

if [[ -n "$COMMIT_PREPARED_DIR" ]]; then
    TEMP_DIR="$COMMIT_PREPARED_DIR"
    for required_file in .workflow-name .namespace .run-number .run-nonce submissionPreflight.json; do
        if [[ ! -f "$TEMP_DIR/$required_file" ]]; then
            echo "Error: prepared submission is missing $required_file" >&2
            exit 1
        fi
    done
    WORKFLOW_NAME="$(<"$TEMP_DIR/.workflow-name")"
    WORKFLOW_NAMESPACE="$(<"$TEMP_DIR/.namespace")"
    RUN_NUMBER="$(<"$TEMP_DIR/.run-number")"
    RUN_NONCE="$(<"$TEMP_DIR/.run-nonce")"
else
    if [[ -n "$PREPARE_ONLY_DIR" ]]; then
        TEMP_DIR="$PREPARE_ONLY_DIR"
        mkdir -p "$TEMP_DIR"
    else
        TEMP_DIR=$(mktemp -d)
        trap 'rm -rf "$TEMP_DIR"' EXIT
    fi

    if [ -n "${EPOCHREALTIME:-}" ]; then
        RUN_SECONDS="${EPOCHREALTIME%.*}"
        RUN_MICROS="${EPOCHREALTIME#*.}"
        RUN_NUMBER="${RUN_SECONDS}${RUN_MICROS:0:3}"
    else
        RUN_NUMBER="$(date +%s%3N 2>/dev/null || true)"
        case "$RUN_NUMBER" in
            ''|*[!0-9]*) RUN_NUMBER="$(date +%s)000" ;;
        esac
    fi

    : "${RUN_NONCE:=$RUN_NUMBER}"

    echo "Using migration run number: $RUN_NUMBER"
    echo "Using snapshot nonce: $RUN_NONCE"

    # Read deployment-provisioned S3 defaults from the Helm-managed ConfigMap and pass them to the config
    # processor as explicit input. This lets the processor resolve the effective failed-document-stream
    # bucket/region/endpoint into MigrationRun.spec.resolvedConfig before submission, instead of RFS
    # discovering them from pod env at runtime. Missing keys are omitted (kubectl failure is non-fatal so
    # non-k8s/local runs that pass --deployment-defaults themselves still work).
    DEPLOYMENT_DEFAULTS_FILE="$TEMP_DIR/deployment-defaults.json"
    DEF_BUCKET="$(kubectl get cm migrations-default-s3-config -o jsonpath='{.data.BUCKET_NAME}' 2>/dev/null || true)" \
    DEF_REGION="$(kubectl get cm migrations-default-s3-config -o jsonpath='{.data.AWS_REGION}' 2>/dev/null || true)" \
    DEF_ENDPOINT="$(kubectl get cm migrations-default-s3-config -o jsonpath='{.data.ENDPOINT_HTTP}' 2>/dev/null || true)" \
    "$NODEJS" -e '
      const o = {};
      if (process.env.DEF_BUCKET)   o.defaultS3Bucket   = process.env.DEF_BUCKET;
      if (process.env.DEF_REGION)   o.defaultS3Region   = process.env.DEF_REGION;
      if (process.env.DEF_ENDPOINT) o.defaultS3Endpoint = process.env.DEF_ENDPOINT;
      require("fs").writeFileSync(process.argv[1], JSON.stringify(o));
    ' "$DEPLOYMENT_DEFAULTS_FILE"
    echo "Resolved deployment S3 defaults: $(cat "$DEPLOYMENT_DEFAULTS_FILE")"

    echo "Running configuration conversion..."
    INITIALIZE_ARGS=(
        --user-config "$CONFIG_FILENAME"
        --output-dir "$TEMP_DIR"
        --workflow-name "$WORKFLOW_NAME"
        --run-number "$RUN_NUMBER"
        --deployment-defaults "$DEPLOYMENT_DEFAULTS_FILE"
    )
    if [[ ${#ALL_ARGS[@]} -gt 0 ]]; then
        INITIALIZE_ARGS+=("${ALL_ARGS[@]}")
    fi
    $INITIALIZE_CMD "${INITIALIZE_ARGS[@]}"

    printf '%s\n' "$WORKFLOW_NAME" > "$TEMP_DIR/.workflow-name"
    printf '%s\n' "$WORKFLOW_NAMESPACE" > "$TEMP_DIR/.namespace"
    printf '%s\n' "$RUN_NUMBER" > "$TEMP_DIR/.run-number"
    printf '%s\n' "$RUN_NONCE" > "$TEMP_DIR/.run-nonce"

    echo "Checking Kubernetes admission..."
    PREFLIGHT_ARGS=(
        --bundle-dir "$TEMP_DIR"
        --output "$TEMP_DIR/submissionPreflight.json"
    )
    if [[ -n "$WORKFLOW_NAMESPACE" ]]; then
        PREFLIGHT_ARGS+=(--namespace "$WORKFLOW_NAMESPACE")
    fi
    $PREFLIGHT_CMD "${PREFLIGHT_ARGS[@]}"

    if [[ "$OUTPUT_FORMAT" == "json" ]]; then
        cat "$TEMP_DIR/submissionPreflight.json"
    fi
    if ! "$NODEJS" -e \
        "process.exit(JSON.parse(require('fs').readFileSync(process.argv[1], 'utf8')).allowed ? 0 : 1)" \
        "$TEMP_DIR/submissionPreflight.json"; then
        if [[ "$OUTPUT_FORMAT" != "json" ]]; then
            "$NODEJS" -e \
                "const r=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8')); for(const i of r.issues.filter(i=>i.blocking)){console.error('PREFLIGHT_BLOCKED: '+i.kind+' '+i.name+': '+i.message)}" \
                "$TEMP_DIR/submissionPreflight.json"
        fi
        exit 2
    fi
    if [[ "$OUTPUT_FORMAT" != "json" ]]; then
        "$NODEJS" -e \
            "const r=JSON.parse(require('fs').readFileSync(process.argv[1],'utf8')); for(const i of r.issues.filter(i=>!i.blocking)){console.error('PREFLIGHT_WARNING: '+i.kind+' '+i.name+': '+i.message)}" \
            "$TEMP_DIR/submissionPreflight.json"
    fi

    if [[ "$DRY_RUN" == "1" || -n "$PREPARE_ONLY_DIR" ]]; then
        if [[ "$OUTPUT_FORMAT" != "json" ]]; then
            echo "Workflow submission prepared and admission preflight completed."
        fi
        exit 0
    fi
fi

if ! "$NODEJS" -e \
    "process.exit(JSON.parse(require('fs').readFileSync(process.argv[1], 'utf8')).allowed ? 0 : 1)" \
    "$TEMP_DIR/submissionPreflight.json"; then
    echo "Error: prepared submission is blocked by admission preflight" >&2
    exit 2
fi

echo "Applying Kubernetes resources..."
export WORKFLOW_NAMESPACE
if [ -x "$TEMP_DIR/handleK8sResources.sh" ]; then
    if [[ "$QUIET" == "1" ]]; then
        if ! "$TEMP_DIR/handleK8sResources.sh" > "$TEMP_DIR/handleK8sResources.log"; then
            echo "Resource creation failed. Re-run submit with verbose output for full resource details." >&2
            exit 1
        fi
    else
        "$TEMP_DIR/handleK8sResources.sh"
    fi
fi

if [ -x "$TEMP_DIR/enrichWorkflowConfigWithUids.sh" ]; then
    echo "Enriching workflow config with CR UIDs..."
    "$TEMP_DIR/enrichWorkflowConfigWithUids.sh" "$TEMP_DIR/workflowMigration.config.yaml"
fi

echo "Applying workflow to Kubernetes..."

# Display any initialization warnings
if [ -f "$TEMP_DIR/warnings.json" ]; then
    "$NODEJS" -e "JSON.parse(require('fs').readFileSync('$TEMP_DIR/warnings.json','utf8')).forEach(w=>console.log('INIT_WARNING: '+w))" >&2
fi

run_kubectl() {
    if [[ -n "$WORKFLOW_NAMESPACE" ]]; then
        kubectl --namespace "$WORKFLOW_NAMESPACE" "$@"
    else
        kubectl "$@"
    fi
}

run_kubectl create -f - <<EOF
apiVersion: argoproj.io/v1alpha1
kind: Workflow
metadata:
  name: $WORKFLOW_NAME
  labels:
    migrations.opensearch.org/workflow-name: "$WORKFLOW_NAME"
    migrations.opensearch.org/run-number: "$RUN_NUMBER"
  annotations:
    migrations.opensearch.org/migration-run: "$WORKFLOW_NAME-run-$RUN_NUMBER"
spec:
  workflowTemplateRef:
    name: full-migration
  entrypoint: main
  arguments:
    parameters:
      - name: uniqueRunNonce
        value: "$RUN_NONCE"
      - name: migrationRunNumber
        value: "$RUN_NUMBER"
      - name: config
        value: |
$(sed 's/^/          /' "$TEMP_DIR/workflowMigration.config.yaml")
EOF

echo "Done! Workflow submitted successfully."
