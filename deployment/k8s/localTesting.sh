
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo add jaegertracing https://jaegertracing.github.io/helm-charts
helm repo add strimzi https://strimzi.io/charts/

helm repo update

helm install prometheus prometheus-community/prometheus
helm install grafana grafana/grafana
helm install jaeger jaegertracing/jaeger

helm install strimzi-cluster-operator --set replicas=1 --version 0.43.0 oci://quay.io/strimzi-helm/strimzi-kafka-operator

kubectl apply -f captureproxy.yaml
kubectl apply -f elasticsearch.yaml
kubectl apply -f opensearch.yaml
kubectl port-forward service/capture-proxy 9200:9200 &
kubectl port-forward service/elasticsearch 19200:9200 &
kubectl port-forward service/opensearch 29200:9200 &
tail -f /dev/null