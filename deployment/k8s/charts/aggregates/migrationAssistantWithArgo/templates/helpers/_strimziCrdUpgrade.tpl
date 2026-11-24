{{/*
  Strimzi CRD upgrade, run by the dependency installer just before it upgrades strimzi-kafka-operator.

  Helm never upgrades CRDs, and the Strimzi 1.x CRDs serve only v1. CRDs that still store v1beta2
  (Strimzi 0.49-1.0) are first migrated with Strimzi's own v1 API conversion tool, which ships in
  those operator images: https://strimzi.io/docs/operators/1.0.0/deploying.html#assembly-api-conversion-str
  Then the chart's CRDs are applied. They are taken from `helm pull` because Helm 3's `helm show crds`
  joins the files without `---` separators.

  Usage: include "migration.strimziCrdUpgrade" (dict "name" $name "chart" $chart "namespace" <ns>
           "operatorNamespace" <ns> "serviceAccount" <installer service account>)
*/}}
{{- define "migration.strimziCrdUpgrade" -}}
{{- $chartRef := printf "chart-repo-%s/%s" .name .name }}
{{- if hasPrefix "oci://" .chart.repository }}
{{- $chartRef = .chart.repository }}
{{- end }}
strimzi_upgrade_crds() {
  if ! stored=$(kubectl get crd kafkas.kafka.strimzi.io -o jsonpath='{.status.storedVersions}' 2>/dev/null); then
    echo "No Strimzi CRDs installed yet; the strimzi-kafka-operator chart will create them."
    return 0
  fi
  case "$stored" in
    *v1beta2*)
      image=$(kubectl -n {{ .operatorNamespace }} get deploy strimzi-cluster-operator \
        -o jsonpath='{.spec.template.spec.containers[0].image}') || return 1
      echo "Converting Strimzi resources and CRDs to v1 with the conversion tool in $image"
      kubectl -n {{ .namespace }} delete pod strimzi-v1-api-conversion --ignore-not-found || return 1
      kubectl -n {{ .namespace }} run strimzi-v1-api-conversion --image="$image" --restart=Never --rm --attach \
        --overrides='{"apiVersion":"v1","spec":{"serviceAccountName":"{{ .serviceAccount }}"}}' --command -- /bin/sh -c \
        '/opt/v1-api-conversion/bin/v1-api-conversion.sh convert-resource --all-namespaces && /opt/v1-api-conversion/bin/v1-api-conversion.sh crd-upgrade' \
        || return 1
      ;;
  esac
  rm -rf /tmp/strimzi-chart || return 1
  retry 3 10 "helm pull {{ .name }} {{ .chart.version }}" -- \
    helm pull {{ $chartRef }} --version {{ .chart.version }} --untar --untardir /tmp/strimzi-chart || return 1
  kubectl apply --server-side --force-conflicts -f /tmp/strimzi-chart/{{ .name }}/crds/ || return 1
  kubectl wait --for=condition=Established -f /tmp/strimzi-chart/{{ .name }}/crds/ --timeout=120s
}

strimzi_upgrade_crds || exit 1
{{- end -}}
