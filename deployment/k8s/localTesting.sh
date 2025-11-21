#./minikubeLocal.sh --start # New code...  Not sure if this breaks some prometheus cAdvisor scraping.  Just want to know first.
minikube config set cpus 8
minikube config set memory 18000
minikube start \
  --extra-config=kubelet.authentication-token-webhook=true \
  --extra-config=kubelet.authorization-mode=Webhook \
  --extra-config=scheduler.bind-address=0.0.0.0 \
  --extra-config=controller-manager.bind-address=0.0.0.0
eval $(minikube docker-env)
kubectl config set-context --current --namespace=ma

#nice to haves
minikube addons enable metrics-server
minikube dashboard &

helm dependency build charts/aggregates/testClusters
helm install --create-namespace -n ma tc charts/aggregates/testClusters

helm dependency build charts/aggregates/migrationAssistantWithArgo
# Use valuesDev.yaml for local development to enable AWS credentials injection
helm install --create-namespace -n ma ma charts/aggregates/migrationAssistantWithArgo \
  -f charts/aggregates/migrationAssistantWithArgo/valuesDev.yaml

# Notice that this doesn't include the capture proxy yet
kubectl port-forward services/elasticsearch-master 19200:9200 &
kubectl port-forward services/opensearch-cluster-master 29200:9200 &

kubectl port-forward svc/argo-server 2746:2746 &
kubectl port-forward svc/etcd 2379:2379 &
kubectl port-forward svc/localstack 4566:4566 &
kubectl port-forward svc/kube-prometheus-stack-prometheus 9090:9090 &
kubectl port-forward svc/jaeger-query 16686:16686 &
kubectl port-forward svc/kube-prometheus-stack-grafana  9000:80 &

# Grafana password...
#  kubectl --namespace ma get secrets kube-prometheus-stack-grafana -o jsonpath="{.data.admin-password}" | base64 -d ; echo

kubectl -n ma exec --stdin --tty migration-console-0 -- /bin/bash

## To test a new migration console container with kubectl wired up to your minikube instance
kubectl config view --minify --flatten | sed "s/127.0.0.1:.*/$(minikube ip):8443/g" > /tmp/kubeconfig-docker
docker run -v /tmp/kubeconfig-docker:/root/.kube/config:ro --network container:minikube \
  -e KUBECONFIG=/root/.kube/config \
  -it \
  migrations/migration_console:latest \
  /bin/bash
