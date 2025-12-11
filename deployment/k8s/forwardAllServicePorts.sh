#!/usr/bin/env bash

set -euo pipefail

nohup kubectl port-forward services/elasticsearch-master 19200:9200 --address 0.0.0.0 > /tmp/elasticsearch-forward.log 2>&1 &
nohup kubectl port-forward services/opensearch-cluster-master 29200:9200 --address 0.0.0.0 > /tmp/opensearch-forward.log 2>&1 &

nohup kubectl port-forward svc/argo-server 2746:2746 --address 0.0.0.0 > /tmp/argo-forward.log 2>&1 &
nohup kubectl port-forward svc/etcd 2379:2379 --address 0.0.0.0 > /tmp/etcd-forward.log 2>&1 &
nohup kubectl port-forward svc/localstack 4566:4566 --address 0.0.0.0 > /tmp/localstack-forward.log 2>&1 &
nohup kubectl port-forward svc/kube-prometheus-stack-prometheus 9090:9090 --address 0.0.0.0 > /tmp/prometheus-forward.log 2>&1 &
nohup kubectl port-forward svc/jaeger-query 16686:16686 --address 0.0.0.0 > /tmp/jaeger-forward.log 2>&1 &
nohup kubectl port-forward svc/kube-prometheus-stack-grafana 9000:80 --address 0.0.0.0 > /tmp/grafana-forward.log 2>&1 &