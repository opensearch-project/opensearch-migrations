minikube start
eval $(minikube docker-env)

helm dependency build charts/aggregates/testClusters
./linkSubChartsToDependencies.sh charts/aggregates/testClusters
helm install tc -n tc charts/aggregates/testClusters --create-namespace


helm dependency build charts/aggregates/migrationAssistant
./linkSubChartsToDependencies.sh charts/aggregates/migrationAssistant
helm install ma -n ma charts/aggregates/migrationAssistant --create-namespace

# Test with
# kc exec -n ma  -it migration-console-7c846764b8-zvf6w --  curl https://opensearch-cluster-master.tc:9200/   -u admin:myStrongPassword123!  --insecure

kubectl port-forward service/capture-proxy 9200:9200 &
kubectl port-forward service/elasticsearch 19200:9200 &
kubectl port-forward service/opensearch 29200:9200 &

# kubectl get secret observability-grafana -o jsonpath="{.data.admin-password}" | base64 --decode ; echo
kc port-forward service/observability-grafana 3000:80

# this hasn't been tested recently and will be folded into packages

# just the operator, not any clusters
helm install strimzi-cluster-operator --set replicas=1 --version 0.43.0 oci://quay.io/strimzi-helm/strimzi-kafka-operator
helm install capture-traffic-kafka-cluster ./capturedTrafficKafkaCluster --set environment=test
helm install replayer ./replayer

helm install target opensearch/opensearch --version 2.21.0 --values ChartValues/localtesting/opensearchTarget.yaml
