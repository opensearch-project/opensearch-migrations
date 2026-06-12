### Minikube Test Setup

First make sure your docker runtime is already up. See notes below in case youre using colima.


In `buildImages/scripts` you will find a bunch of scripts helping with local minikube setup / commands.
- `fillLocalRegistry.sh`: setup of local registry and building of needed images and pushing to that local registry
  - for faster update times comment out all unneeded images in `build.gradle` testBuildKitProjects array. 
    e.g if you work only on solr source tests, you can comment all other images out.
- `startMinikube.sh`: script to startup local minikube
- `startMinikubeAndDeployCharts.sh`: combines `startMinikube.sh` with chart deployments
- `updateArgoWorkflowTempate.sh`: simply updates `clusterWorkflows.yaml` in case changes were made to it and need update  
  in running argo
- `redeployMigrationConsole.sh`: removal of possible image caching in minikube and `helm upgrade` call

So to start fresh, u would do (from buildImages folder):
1. `./scripts/fillLocalRegistry.sh`
2. `./scripts/startMinikubeAndDeployCharts.sh`

Then you would run tests via:
- ssh into migrationConsole pod: `kubectl -n ma exec --stdin --tty $(kubectl get pods -n ma -l app=migration-console --sort-by=.metadata.creationTimestamp -o jsonpath="{.items[-1].metadata.name}") -- /bin/bash` 
- within the pod, kick off the test (HOST_IP_FROM_MINIKUBE stands for the IP under which minikube has access to the host): 
  - find the ip by which minikube has access to the host to allow pulling images from the locally deployed registry: 
    - either from host via: `HOST_IP_FROM_MINIKUBE=$(minikube ssh -- ip route 2>/dev/null | awk '/default/ {print $3}' | tr -d '\r')`
    - or in minikube: `export HOST_IP_FROM_MINIKUBE=$(ip route 2>/dev/null | awk '/default/ {print $3}' | tr -d '\r')`
  - in the migration console shell (if you determined HOST_IP_FROM_MINIKUBE on host, replace below placeholder withh respective ip or set env var in minikube after ssh into it)
    ```
    pipenv run pytest /root/lib/integ_test/integ_test/ma_workflow_test.py --unique_id 12345 --config_file_path "/config/migration_services.yaml" --test_ids "0001" --source_version "SOLR_6.6" --target_version "OS_2.19" --image_registry_prefix "$HOST_IP_FROM_MINIKUBE:5001/"
    ```
  - workflow reset:
    - ```workflow reset --all --include-proxies --delete-storage --namespace ma```
    - ```console clusters clear-indices --cluster target```

Alternatively you can start tests without ssh into the micrationConsole via:
- from libraries/testAutomation/testAutomation folder (see test_runner.py):  `pipenv run app --test-ids=0001 --source-version=ES_7.10 --target-version=OS_2.19 --registry-prefix [your reachable docker registry ip]:[docker registry port]/`
  - append `--delete-only` for deletion of test related resources

For updates after changes:
- if all is running and you make changes to `clusterWorkflows.yaml` or migrationConsole in general:
  - `fillLocalRegistry.sh`
  - `redeployMigrationConsole.sh` 
    - try `deleteMigrationConsolePod.sh` first 
    - (just deleting the pod such that it restarts should be enough if ImagePullPolicy = 'Always', yet if minikube cache provides old image, might need to run `redeployMigrationConsole.sh` )
- run tests as described above 


For changes made to the cluster templates, note a few points about the setup:
The argo workflows are installed via `installWorkflows.yaml`, which is run in two scenarios:
1. `helm install` — when the migrationAssistantWithArgo chart is installed for the first time
2. `helm upgrade` — every time the chart is upgraded

Removal of templates: `helm uninstall [release-name]` deletes workflow templates by pre-delete hook `remove-argo-migration-templates`
that deletes all WorkflowTemplates labelled with the release name passed to helm uninstall cmd.

`fullMigrationWithClusters.yaml` calls sub-templates from `clusterWorkflows.yaml`, e.g `cluster-templates/elasticsearch-7-10-single-node`,
thus `clusterWorkflows.yaml` are lib of reusable steps, not a runnable workflow by itself.

If you make changes to clusterWorkflow (such as for adjustment or addition of source / target systems), you can simply 
update them without touching the other setup with: `kubectl apply -f ../migrationConsole/lib/integ_test/testWorkflows/clusterWorkflows.yaml -n ma`


### NOTES
- if you touch the regexes that do version checks in javascript, you might need to update the jest snapshot:
  - `cd orchestrationSpecs/packages/[relevant-subfolder]` (where relevant-subfolder relates to folder where changes were made, such as `schemas` or others)
  - `npm test -- --updateSnapshot`
- port forward for solr access: `kubectl port-forward [solr-pod-name] 8983:8983 -n ma`
  - ClusterVersionDetector detectSolrVersion call in workflow uses `curl -X get http://localhost:8983/solr/admin/info/system?wt=json`
    and tries to parse `lucene.solr-spec-version` to determine solr version to use (NOTE: Solr version 7.x and higher default to json output, Solr 6.x needs the wt=json parameter)
- in case you use colima and are getting connection / dns issues: `colima start --dns 1.1.1.1 --dns 8.8.8.8`
- in case you use colima, start colima with `colima start --edit` and make sure the resources in the `startMinikubeAndDeployCharts.sh`.
  script are less or equal the resources configured for colima.
- redeploy migrationConsole only:
```
helm template ma ../deployment/k8s/charts/aggregates/migrationAssistantWithArgo \                                                                    
    -n ma \       
    --show-only templates/resources/migrationConsole.yaml \                                                                                            
    -f <(envsubst < ../deployment/k8s/charts/aggregates/migrationAssistantWithArgo/valuesForLocalK8sWithEnvSubst.yaml) \
    | kubectl apply -n ma -f -
```


#### Port Forwarding
- argo server only: `kubectl -n ma  port-forward service/argo-server 8001:2746`
- all services: `../deployment/k8s/forwardAllServicePorts.sh`


#### Commands
- see complete installation notes from all subcharts: `kubectl get configmap ma-installation-notes -n ma -o jsonpath='{.data.all-notes\.txt}' | less`
- ssh into minikube runtime: `minikube ssh`
- get migration assistant related services with labels: `kubectl get services -n ma --show-labels`
- describe specific workload pod: `kubectl -n ma describe pod/migration-workflow-3847500648-rfs-64465d8588-k5zd2`
- get logs for multiple pods with specific label: `kubectl logs -l workflows.argoproj.io/workflow=migration-workflow --all-pods=true -n ma`
- checking disk usage within minikube: `minikube ssh -- df -h`
- delete namespace: `kubectl delete namespace [your-namespace]`


#### Troubleshoot
There is one step of the workflow that claims 200gb of storage,
thus if your minikube setup doesnt have that configured (--disk-size param)
or if your docker runtime is not configured to allow the required
amount of space, then your workflow wont run through. Either reduce the ephemeral storage ask or provide the correct
amount of space.
Example workflow for high disc requirement if unchanged:
```
Name:             migration-workflow-4035117585-rfs-ddc47b868-hzlvr
Namespace:        ma
Priority:         0
Service Account:  argo-workflow-executor
```
- when running tests, and you see Solr 6, Solr 7 fail on backup creation, few things to note:
  the test workflow composes the requested backup location via:
  - suffix from the workflow.uid:`SUFFIX=$(echo "{{workflow.uid}}" | cut -c1-8)`
  - a fixed term `testsnapshot`
  - an id that is appended in `scripts/createMigrationWorkflowFromUserConfiguration.sh` that is created as uuid (unique per run)
  - those are composed as `/[suffix]/source1_testsnapshot_[uuid-snippet from above script]`
This conflicts with th fact that solr local backup in 6 / 7 need to create the folders before the backup command 
is handled, otherwise this will fail due to folder not existing. Right now the folder created on solr startup assumes
the extracted uuid to be 1, which is not the case in normal runs. To fix either set 
```yaml
- name: uniqueRunNonce
  value: "1"
```
in above script for local runs. Fix to handle this dynamically to be filed shortly.