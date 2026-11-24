{{/*
  Strimzi CRD upgrade, run by the dependency installer just before it upgrades the
  strimzi-kafka-operator chart.

  Helm installs the CRDs in a chart's crds/ directory on first install only; it never
  upgrades them. Strimzi 1.x CRDs also serve only kafka.strimzi.io/v1, while the CRDs of
  Strimzi 0.49-0.51 serve v1 but store v1beta2. Kubernetes rejects a CRD update that drops
  a version still listed in status.storedVersions, so an existing install is moved to v1
  first, the same way Strimzi's `v1-api-conversion.sh crd-upgrade` does it:

    1. mark v1 as the storage version (every existing version stays served);
    2. rewrite every existing resource so that the API server stores it as v1;
    3. set status.storedVersions to [v1].

  Then the CRD files shipped in the target chart's crds/ directory are applied, and every
  CRD is checked to serve exactly the chart's versions. The files are taken from `helm pull`,
  not `helm show crds`: Helm 3 joins the files without `---` separators, and kubectl then
  silently applies only the last CRD. A fresh install (no Strimzi CRDs yet) skips all of
  this and lets helm create the CRDs.

  Every step is idempotent, so a failed or repeated installer run can be retried. The
  Migration Assistant always created its Strimzi resources through the v1 API, so they need
  no field conversion; resources created by hand through v1beta2 may need Strimzi's
  conversion tool (convert-resource) before the upgrade.

  Usage: include "migration.strimziCrdUpgrade" (dict "name" $name "chart" $chart)
*/}}
{{- define "migration.strimziCrdUpgrade" -}}
{{- $chartRef := printf "chart-repo-%s/%s" .name .name }}
{{- if hasPrefix "oci://" .chart.repository }}
{{- $chartRef = .chart.repository }}
{{- end }}
STRIMZI_CRDS="kafkas.kafka.strimzi.io kafkanodepools.kafka.strimzi.io kafkatopics.kafka.strimzi.io kafkausers.kafka.strimzi.io kafkaconnects.kafka.strimzi.io kafkaconnectors.kafka.strimzi.io kafkamirrormaker2s.kafka.strimzi.io kafkabridges.kafka.strimzi.io kafkarebalances.kafka.strimzi.io strimzipodsets.core.strimzi.io"
STRIMZI_CHART_DIR=/tmp/strimzi-chart-{{ .chart.version }}
STRIMZI_CRDS_DIR="$STRIMZI_CHART_DIR/{{ .name }}/crds"

strimzi_rewrite_resource() {
  # get + replace carries the resourceVersion, so a concurrent operator write makes this fail
  # with a conflict and the caller retries. A resource deleted meanwhile needs no rewrite.
  _strimzi_obj=$(kubectl get "$1" -n "$2" "$3" -o json --ignore-not-found) || return 1
  [ -n "$_strimzi_obj" ] || return 0
  printf '%s' "$_strimzi_obj" | kubectl replace -f - >/dev/null
}

strimzi_store_crd_as_v1() {
  _strimzi_crd=$1
  _strimzi_stored=$(kubectl get crd "$_strimzi_crd" -o jsonpath='{.status.storedVersions[*]}') || return 1
  if [ "$_strimzi_stored" = "v1" ]; then
    echo "  $_strimzi_crd already stores only v1"
    return 0
  fi
  _strimzi_served=$(kubectl get crd "$_strimzi_crd" -o jsonpath='{.spec.versions[*].name}') || return 1
  case " $_strimzi_served " in
    *" v1 "*) ;;
    *)
      echo "ERROR: $_strimzi_crd does not define the v1 API (versions: $_strimzi_served)." >&2
      echo "  It was installed by Strimzi older than 0.49. Upgrade through a Migration Assistant release" >&2
      echo "  that uses Strimzi 0.49-0.51 first, or remove the Strimzi resources and CRDs and reinstall." >&2
      return 1
      ;;
  esac
  echo "  $_strimzi_crd: stored versions [$_strimzi_stored] -> [v1]"

  _strimzi_patch=$(kubectl get crd "$_strimzi_crd" -o json | jq -c '[.spec.versions | to_entries[] |
      {op: "replace", path: "/spec/versions/\(.key)/served", value: true},
      {op: "replace", path: "/spec/versions/\(.key)/storage", value: (.value.name == "v1")}]') || return 1
  retry 3 5 "make v1 the storage version of $_strimzi_crd" -- \
    kubectl patch crd "$_strimzi_crd" --type=json -p "$_strimzi_patch" || return 1

  # kubectl wants the resource.version.group form to read the objects through v1 explicitly.
  _strimzi_resource="${_strimzi_crd%%.*}.v1.${_strimzi_crd#*.}"
  _strimzi_list="/tmp/strimzi-rewrite-${_strimzi_crd}.txt"
  kubectl get "$_strimzi_resource" -A \
    -o jsonpath='{range .items[*]}{.metadata.namespace}{" "}{.metadata.name}{"\n"}{end}' > "$_strimzi_list" || return 1
  while read -r _strimzi_ns _strimzi_name; do
    [ -n "$_strimzi_name" ] || continue
    retry 5 2 "rewrite $_strimzi_resource $_strimzi_ns/$_strimzi_name as v1" -- \
      strimzi_rewrite_resource "$_strimzi_resource" "$_strimzi_ns" "$_strimzi_name" || return 1
  done < "$_strimzi_list"
  echo "    rewrote $(grep -c . "$_strimzi_list" || true) resource(s)"

  retry 3 5 "drop old stored versions from $_strimzi_crd" -- \
    kubectl patch crd "$_strimzi_crd" --subresource=status --type=merge \
      -p '{"status":{"storedVersions":["v1"]}}' || return 1
}

strimzi_fetch_crds() {
  rm -rf "$STRIMZI_CHART_DIR" && mkdir -p "$STRIMZI_CHART_DIR" || return 1
  helm pull {{ $chartRef }} --version {{ .chart.version }} --untar --untardir "$STRIMZI_CHART_DIR" || return 1
  ls "$STRIMZI_CRDS_DIR"/*.yaml >/dev/null 2>&1
}

strimzi_apply_crds() {
  kubectl apply --server-side --force-conflicts --field-manager=migration-assistant-installer \
    -f "$STRIMZI_CRDS_DIR" >/dev/null
}

strimzi_verify_crds() {
  # Every CRD in the chart must now serve exactly the chart's versions; anything else means the
  # apply did not reach it.
  kubectl create --dry-run=client --validate=false -f "$STRIMZI_CRDS_DIR" \
    -o jsonpath='{.metadata.name} {.spec.versions[*].name}{"\n"}' > "$STRIMZI_CHART_DIR/expected.txt" || return 1
  [ -s "$STRIMZI_CHART_DIR/expected.txt" ] || return 1
  while read -r _strimzi_name _strimzi_expected; do
    [ -n "$_strimzi_name" ] || continue
    _strimzi_actual=$(kubectl get crd "$_strimzi_name" -o jsonpath='{.spec.versions[*].name}') || return 1
    if [ "$_strimzi_actual" != "$_strimzi_expected" ]; then
      echo "ERROR: CRD $_strimzi_name serves [$_strimzi_actual]; the chart defines [$_strimzi_expected]" >&2
      return 1
    fi
  done < "$STRIMZI_CHART_DIR/expected.txt"
  echo "  $(grep -c . "$STRIMZI_CHART_DIR/expected.txt") CRDs match the chart"
}

strimzi_upgrade_crds() {
  if ! kubectl get crd kafkas.kafka.strimzi.io >/dev/null 2>&1; then
    echo "No Strimzi CRDs installed yet; the strimzi-kafka-operator chart will create them."
    return 0
  fi
  echo "Moving existing Strimzi CRDs to the v1 storage version..."
  for _strimzi_crd_name in $STRIMZI_CRDS; do
    if kubectl get crd "$_strimzi_crd_name" >/dev/null 2>&1; then
      strimzi_store_crd_as_v1 "$_strimzi_crd_name" || return 1
    fi
  done
  echo "Applying the strimzi-kafka-operator {{ .chart.version }} CRDs (helm upgrade does not update CRDs)..."
  retry 3 10 "helm pull strimzi-kafka-operator {{ .chart.version }}" -- strimzi_fetch_crds || return 1
  retry 3 10 "apply strimzi-kafka-operator {{ .chart.version }} CRDs" -- strimzi_apply_crds || return 1
  for _strimzi_crd_name in $STRIMZI_CRDS; do
    kubectl wait --for=condition=Established "crd/$_strimzi_crd_name" --timeout=120s || return 1
  done
  strimzi_verify_crds || return 1
  # The new operator rejects Kafka versions it no longer supports; those clusters keep running
  # but are not reconciled until spec.kafka.version moves to a supported release.
  echo "Existing Kafka clusters (namespace/name spec.kafka.version):"
  kubectl get kafkas.v1.kafka.strimzi.io -A \
    -o jsonpath='{range .items[*]}{"  "}{.metadata.namespace}{"/"}{.metadata.name}{" "}{.spec.kafka.version}{"\n"}{end}' || true
}

strimzi_upgrade_crds || exit 1
{{- end -}}
