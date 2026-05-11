### Minikube Test Setup

#### Getting started
Within buildImages folder:
- `./scripts/startMinikubeAndFillWithImages.sh`: starts up minikube, image registry, builds and uploads images to registry
  and installs helm charts needed for migration assistant. At the end of it, you will have argo (and related tooling)
  running in your minkube setup.


#### Running Tests
The tests, for the specified source and target versions, pull the needed charts and deploys them.
Tests be started by:
- from project folder (see test_runner.py):  `pipenv run app --test-ids=0001 --source-version=ES_7.10 --target-version=OS_2.19 --registry-prefix localhost:30500/`
  - append `--delete-only` for deletion of test related resources
- by from within migration-console pod: 
  - ssh into pod: `kubectl -n ma exec --stdin --tty $(kubectl get pods -n ma -l app=migration-console --sort-by=.metadata.creationTimestamp -o jsonpath="{.items[-1].metadata.name}") -- /bin/bash` 
  - run test: `pipenv run pytest /root/lib/integ_test/integ_test/ma_workflow_test.py --unique_id 12345 --config_file_path "/config/migration_services.yaml" --test_ids "0001" --source_version "ES_7.10" --target_version "OS_2.19" --image_registry_prefix "localhost:30500/"` 


#### Port Forwarding
- argo server only: `kubectl -n ma  port-forward service/argo-server 8001:2746`
- all services: `../deployment/k8s/forwardAllServicePorts.sh`


#### Commands
- see complete installation notes from all subcharts: `kubectl get configmap ma-installation-notes -n ma -o jsonpath='{.data.all-notes\.txt}' | less`
- open bash in migration console: `kubectl -n ma exec --stdin --tty $(kubectl get pods -n ma -l app=migration-console --sort-by=.metadata.creationTimestamp -o jsonpath="{.items[-1].metadata.name}") -- /bin/bash`
  - can run tests directly from here via test_runner.py: `pipenv run pytest /root/lib/integ_test/integ_test/ma_workflow_test.py --unique_id 12345 --config_file_path "/config/migration_services.yaml" --test_ids "0001" --source_version "ES_7.10" --target_version "OS_2.19" --image_registry_prefix "localhost:30500/"`
- from libraries/testAutomation/testAutomation/test_runner.py: `pipenv run app --test-ids=0001 --source-version=ES_7.10 --target-version=OS_2.19 --registry-prefix localhost:30500/`
- to delete the deployment corresponding to above tests use `--delete-only` flag:
  - `pipenv run app --test-ids=0001 --source-version=ES_7.10 --target-version=OS_2.19 --registry-prefix localhost:30500/ --delete-only`
- ssh into minikube runtime: `minikube ssh`
- get migration assistant related services with labels: `kubectl get services -n ma --show-labels`
- describe specific workload pod (e.g to identify issues): `kubectl -n ma describe pod/migration-workflow-3847500648-rfs-64465d8588-k5zd2`
- get logs for multiple pods with specific label: `kubectl logs -l workflows.argoproj.io/workflow=migration-workflow --all-pods=true -n ma`
- checking disk usage within minikube: `minikube ssh -- df -h`
- delete namespace: `kubectl delete namespace [your-namespace]`


#### Other
- images are defined in `clusterWorkflows.yaml` (here custom elastic image is referenced with tag)
  - Retrieve available version tags: `curl http://localhost:5001/v2/migrations/custom-elasticsearch/tags/list`
    - should find all / some of: `"2.4.6","6.8.23","cache_arm64","1.5.2","7.10.2","5.6.16"`


#### Docker Runtime
- in case you use colima, start colima with `colima start --edit` and make sure the resources in the startMinikubeAndFillWithImages.sh
  script are less or equal the resources configured for colima.

#### Troubleshoot
There is one step of the workflow that claims 200gb of storage,
thus if your minikube setup doesnt have that configured (--disk-size param)
or if your docker runtime is not configured to allow the required
amount of space, then your workflow wont run through.
Example describe output:
```
Name:             migration-workflow-4035117585-rfs-ddc47b868-hzlvr
Namespace:        ma
Priority:         0
Service Account:  argo-workflow-executor
Node:             <none>
Labels:           app=bulk-loader
                  deployment-name=migration-workflow-4035117585-rfs
                  migrations.opensearch.org/from-snapshot-migration=migration-0
                  migrations.opensearch.org/snapshot=testsnapshot
                  migrations.opensearch.org/source=source1
                  migrations.opensearch.org/target=target1
                  migrations.opensearch.org/task=reindexFromSnapshot
                  pod-template-hash=ddc47b868
                  workflows.argoproj.io/workflow=migration-workflow
Annotations:      kyverno.io/mutated-by: zero-resource-requests
Status:           Pending
IP:               
IPs:              <none>
Controlled By:    ReplicaSet/migration-workflow-4035117585-rfs-ddc47b868
Containers:
  bulk-loader:
    Image:      localhost:30500/migrations/reindex_from_snapshot:latest
    Port:       <none>
    Host Port:  <none>
    Command:
      /rfs-app/runJavaWithClasspathWithRepeat.sh
    Args:
      org.opensearch.migrations.RfsMigrateDocuments
      ---INLINE-JSON
      ...
    Limits:
      cpu:                3300m
      ephemeral-storage:  200Gi
      memory:             7000Mi
    Requests:
      cpu:                0
      ephemeral-storage:  200Gi
      memory:             0
    Environment:
      TARGET_USERNAME:               <set to the key 'username' in secret 'target-opensearch-2-19-e173d262-creds'>  Optional: true
      TARGET_PASSWORD:               <set to the key 'password' in secret 'target-opensearch-2-19-e173d262-creds'>  Optional: true
      COORDINATOR_USERNAME:          <set to the key 'username' in secret 'target-opensearch-2-19-e173d262-creds'>  Optional: true
      COORDINATOR_PASSWORD:          <set to the key 'password' in secret 'target-opensearch-2-19-e173d262-creds'>  Optional: true
      FAILED_REQUESTS_LOGGER_LEVEL:  OFF
      CONSOLE_LOG_FORMAT:            json
      JDK_JAVA_OPTIONS:               
      AWS_SHARED_CREDENTIALS_FILE:   /config/credentials/configuration
    Mounts:
      /config/credentials from localstack-test-creds (ro)
      /config/logConfiguration from log4j-configuration (ro)
      /var/run/secrets/kubernetes.io/serviceaccount from kube-api-access-m2mff (ro)
Conditions:
  Type           Status
  PodScheduled   False 
Volumes:
  log4j-configuration:
    Type:      ConfigMap (a volume populated by a ConfigMap)
    Name:      default-log4j-config
    Optional:  true
  localstack-test-creds:
    Type:      ConfigMap (a volume populated by a ConfigMap)
    Name:      localstack-test-creds
    Optional:  false
  kube-api-access-m2mff:
    Type:                    Projected (a volume that contains injected data from multiple sources)
    TokenExpirationSeconds:  3607
    ConfigMapName:           kube-root-ca.crt
    Optional:                false
    DownwardAPI:             true
QoS Class:                   Burstable
Node-Selectors:              <none>
Tolerations:                 node.kubernetes.io/not-ready:NoExecute op=Exists for 300s
                             node.kubernetes.io/unreachable:NoExecute op=Exists for 300s
Events:
  Type     Reason            Age               From               Message
  ----     ------            ----              ----               -------
  Warning  FailedScheduling  4s (x7 over 95s)  default-scheduler  0/1 nodes are available: 1 Insufficient ephemeral-storage. no new claims to deallocate, preemption: 0/1 nodes are available: 1 Preemption is not helpful for scheduling.
```