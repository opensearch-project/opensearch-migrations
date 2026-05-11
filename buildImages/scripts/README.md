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


#### Other
- images are defined in `clusterWorkflows.yaml` (here custom elastic image is referenced with tag)
  - Retrieve available version tags: `curl http://localhost:5001/v2/migrations/custom-elasticsearch/tags/list`
    - should find all / some of: `"2.4.6","6.8.23","cache_arm64","1.5.2","7.10.2","5.6.16"`