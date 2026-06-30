#!/usr/bin/env bash

set -euo pipefail

: "${NAMESPACE:?}"
: "${PROXY_NAME:?}"
: "${LISTEN_PORT:?}"
: "${SERVICE_TYPE:?}"
: "${TIMEOUT_SECONDS:?}"

case "$SERVICE_TYPE" in
  LoadBalancer|ClusterIP) ;;
  *)
    echo "Unsupported proxy service type: $SERVICE_TYPE"
    exit 1
    ;;
esac

deadline=$((SECONDS + TIMEOUT_SECONDS))
last_state=""

while (( SECONDS < deadline )); do
  service_type="$(kubectl -n "$NAMESPACE" get service "$PROXY_NAME" -o jsonpath='{.spec.type}' 2>/dev/null || true)"
  cluster_ip="$(kubectl -n "$NAMESPACE" get service "$PROXY_NAME" -o jsonpath='{.spec.clusterIP}' 2>/dev/null || true)"
  endpoints="$(kubectl -n "$NAMESPACE" get endpoints "$PROXY_NAME" -o jsonpath='{range .subsets[*].addresses[*]}{.ip}{" "}{end}' 2>/dev/null || true)"
  load_balancer_ingress=""
  load_balancer_state="not-required"
  if [[ "$SERVICE_TYPE" == "LoadBalancer" ]]; then
    load_balancer_ingress="$(kubectl -n "$NAMESPACE" get service "$PROXY_NAME" -o jsonpath='{range .status.loadBalancer.ingress[*]}{.hostname}{.ip}{" "}{end}' 2>/dev/null || true)"
    load_balancer_state="${load_balancer_ingress:-none}"
  fi

  if [[ "$service_type" == "$SERVICE_TYPE" && -n "$cluster_ip" && "$cluster_ip" != "<none>" && "$cluster_ip" != "None" && -n "$endpoints" ]] &&
     [[ "$SERVICE_TYPE" != "LoadBalancer" || -n "$load_balancer_ingress" ]]; then
    service_endpoint="$PROXY_NAME.$NAMESPACE.svc.cluster.local:$LISTEN_PORT"
    echo "$service_endpoint" > /tmp/service-endpoint
    printf '%s' "$load_balancer_ingress" > /tmp/load-balancer-endpoint
    echo "Proxy endpoint is ready: serviceType=$SERVICE_TYPE service=$service_endpoint endpoints=$endpoints loadBalancer=${load_balancer_ingress:-none}"
    exit 0
  fi

  state="desiredServiceType=$SERVICE_TYPE serviceType=${service_type:-missing} clusterIP=${cluster_ip:-missing} endpoints=${endpoints:-none} loadBalancer=$load_balancer_state"
  if [[ "$state" != "$last_state" ]]; then
    echo "Waiting for proxy endpoint readiness: $state"
    last_state="$state"
  fi
  sleep 5
done

echo "Timed out waiting for proxy endpoint readiness for service/$PROXY_NAME"
kubectl -n "$NAMESPACE" get service "$PROXY_NAME" -o wide || true
kubectl -n "$NAMESPACE" describe service "$PROXY_NAME" || true
kubectl -n "$NAMESPACE" get endpoints "$PROXY_NAME" -o yaml || true
kubectl -n "$NAMESPACE" get endpointslices -l kubernetes.io/service-name="$PROXY_NAME" -o yaml || true
exit 1
