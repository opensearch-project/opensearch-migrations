minikube start
eval $(minikube docker-env)

helm repo add strimzi https://strimzi.io/charts/
helm repo update

helm dependency build  mockCustomerClusters # not sure if this is required
helm upgrade mock-context  mockCustomerClusters --values ../helmValues/localTesting/sourceElasticsearchCluster.yaml --values ../helmValues/localTesting/targetOpenSearchCluster.yaml --values ../helmValues/localTesting/captureProxy.yaml
kubectl port-forward service/capture-proxy 9200:9200 &
kubectl port-forward service/elasticsearch 19200:9200 &
kubectl port-forward service/opensearch 29200:9200 &

helm dependency build  testObervability # not sure if this is required
helm install observability ./testObervability --values ../helmValues/localTesting/grafana.yaml --values ../helmValues/localTesting/jaeger.yaml
# kubectl get secret observability-grafana -o jsonpath="{.data.admin-password}" | base64 --decode ; echo
kc port-forward service/observability-grafana 3000:80

# this hasn't been tested recently and will be folded into packages

# just the operator, not any clusters
helm install strimzi-cluster-operator --set replicas=1 --version 0.43.0 oci://quay.io/strimzi-helm/strimzi-kafka-operator
helm install capture-traffic-kafka-cluster ./capturedTrafficKafkaCluster --set environment=test
helm install replayer ./replayer

helm install target opensearch/opensearch --version 2.21.0 --values ChartValues/localtesting/opensearchTarget.yaml
