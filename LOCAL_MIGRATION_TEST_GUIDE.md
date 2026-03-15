# Local Migration Test Guide

This is a practical step-by-step guide for reproducing the local Migration Assistant test flow on a developer machine.

Repo location:
- `/Users/littlebjp/.openclaw/workspace/opensearch-migrations`

## Goal

Stand up the local Kubernetes test environment, access the Migration Assistant console, and run a reindex-from-snapshot migration workflow from the source test cluster to the target OpenSearch cluster.

## Current assumptions

This guide reflects the environment used during local testing on Brian's Mac:
- macOS
- Docker Desktop running
- minikube installed and using the docker driver
- kubectl installed
- helm installed
- Java 17 available in shell environment
- repo cloned locally

## 1. Open the repo

```bash
cd /Users/littlebjp/.openclaw/workspace/opensearch-migrations
source ~/.zshrc
```

Confirm Java 17 is active:

```bash
java -version
./gradlew -version
```

You want the Gradle launcher JVM to be Java 17.

## 2. Verify the local build baseline

Run:

```bash
./gradlew clean test --stacktrace
./gradlew clean build --stacktrace
```

If these fail under Java 11, stop and switch to Java 17 first.

## 3. Start local k8s deployment

The current local path is:

```bash
cd deployment/k8s
bash localTesting.sh
```

What this does at a high level:
- starts/reuses minikube
- enables metrics-server
- sets up buildkit and local registry
- builds/pushes local images
- preloads required images into minikube
- installs the `testClusters` Helm chart
- installs the `migrationAssistantWithArgo` Helm chart

## 4. Watch cluster status during deployment

In another terminal:

```bash
kubectl get pods -n ma -w
```

Useful checks:

```bash
kubectl get pods -n ma
kubectl get events -n ma --sort-by=.lastTimestamp | tail -80
helm list -n ma
```

You want to see these components become healthy:
- source Elasticsearch pod
- target OpenSearch pod
- migration assistant components
- migration console pod
- argo components

## 5. If image pull problems happen

Most of the local failures during testing were image-reference issues.

Useful diagnostics:

```bash
kubectl get events -n ma --sort-by=.lastTimestamp | tail -100
minikube ssh -- "sudo ctr -n k8s.io images ls | grep migrations"
```

If needed, inspect the local registry catalog:

```bash
curl -s http://localhost:5001/v2/_catalog
curl -s http://localhost:5001/v2/migrations/migration_console/tags/list
curl -s http://localhost:5001/v2/migrations/elasticsearch_searchguard/tags/list
```

## 6. Log into the migration console

Once the console pod exists, find it:

```bash
kubectl -n ma get pods | grep console
```

Then exec into it:

```bash
kubectl -n ma exec -it migration-console-0 -- bash
```

If the pod name differs, use the actual pod name returned by `kubectl get pods`.

## 7. Inspect workflow CLI availability

Inside the console:

```bash
workflow --help
workflow configure --help
workflow submit --help
workflow status --help
workflow manage --help
```

Also useful:

```bash
console --help
pwd
ls /root
ls /root/workflows
```

## 8. Inspect integration-test workflow examples

Inside the repo, the most relevant files are:

- `migrationConsole/lib/integ_test/README.md`
- `migrationConsole/lib/integ_test/integ_test/ma_workflow_test.py`
- `migrationConsole/lib/integ_test/integ_test/test_cases/backfill_tests.py`
- `migrationConsole/lib/integ_test/testWorkflows/fullMigrationWithClusters.yaml`
- `migrationConsole/build/dockerContext/nodeStaging/workflowTemplates/`

From the repo root, you can inspect them with:

```bash
sed -n '1,220p' migrationConsole/lib/integ_test/README.md
sed -n '1,260p' migrationConsole/lib/integ_test/testWorkflows/fullMigrationWithClusters.yaml
```

## 9. Prepare a workflow config for reindex-from-snapshot

Inside the console, the intended path is to use the workflow CLI to configure or submit a migration YAML.

Start by generating or reviewing sample config:

```bash
workflow configure
```

If supported in your console image, also check for sample/schema output:

```bash
ls /root/configProcessor
cat /root/configProcessor/sample.yaml
```

The migration should target:
- source cluster: local Elasticsearch test cluster
- target cluster: local OpenSearch test cluster
- migration mode: reindex-from-snapshot / backfill

## 10. Submit the workflow

Once config is ready:

```bash
workflow submit <your-config.yaml>
```

If the CLI instead uses a different submit form, check:

```bash
workflow submit --help
```

Capture the workflow name from the submission output.

## 11. Monitor workflow progress

Inside the console or from your local shell:

```bash
workflow status <workflow-name>
workflow manage <workflow-name>
```

And from Kubernetes:

```bash
kubectl get workflows -n ma
kubectl get pods -n ma
kubectl get events -n ma --sort-by=.lastTimestamp | tail -100
```

If Argo CRDs are installed, also useful:

```bash
kubectl get wf -n ma
```

## 12. Verify migrated documents

After workflow completion, verify document counts on source and target.

For example, port-forward source/target services as needed and query counts:

```bash
kubectl -n ma port-forward svc/elasticsearch-master 9200:9200
kubectl -n ma port-forward svc/opensearch-cluster-master 9201:9200
```

Then in another shell:

```bash
curl -k -u admin:admin https://localhost:9200/_cat/indices?v
curl -k -u admin:admin https://localhost:9200/<index>/_count

curl -k -u admin:myStrongPassword123! https://localhost:9201/_cat/indices?v
curl -k -u admin:myStrongPassword123! https://localhost:9201/<index>/_count
```

## 13. Useful cleanup

```bash
helm uninstall -n ma ma tc
kubectl delete namespace ma
```

If namespace deletion hangs:

```bash
kubectl get ns ma
kubectl get all -n ma
```

## Notes from local debugging

A few things mattered a lot during testing:
- Java 17 was required for a reliable local Gradle baseline
- local image references had to match what kubelet could actually use
- preloaded images inside minikube were more reliable than relying on kubelet to pull from the local HTTP registry
- `helm upgrade --install` is safer than `helm install` for reruns

## Recommended working loop

```bash
cd /Users/littlebjp/.openclaw/workspace/opensearch-migrations
source ~/.zshrc
./gradlew clean test --stacktrace
./gradlew clean build --stacktrace
cd deployment/k8s
bash localTesting.sh
kubectl -n ma get pods
kubectl -n ma exec -it migration-console-0 -- bash
```

Then continue with `workflow` commands inside the console.
