### Copilot Deployment
Copilot is a tool for deploying containerized applications on AWS ECS. Official documentation can be found [here](https://aws.github.io/copilot-cli/docs/overview/).

### Initial Setup

#### Install Prerequisites

###### Docker
Docker is used by Copilot to build container images. If not installed, follow the steps [here](https://docs.docker.com/engine/install/) to set up. Later versions are recommended.
###### Git
Git is used by the opensearch-migrations repo to fetch associated repositories (such as the traffic-comparator repo) for constructing their respective Dockerfiles. Steps to set up can be found [here](https://github.com/git-guides/install-git).
###### Java 11
Java is used by the opensearch-migrations repo and Gradle, its associated build tool. The current required version is Java 11.

#### Creating Dockerfiles
This project needs to build the required Dockerfiles that Copilot will use in its services. From the `TrafficCapture` directory the following command can be ran to build these files
```
./gradlew :dockerSolution:buildDockerImages
```
More details can be found [here](../../TrafficCapture/dockerSolution/README.md)

#### Setting up Copilot CLI
If you are on Mac the following Homebrew command can be run to set up the Copilot CLI:
```
brew install aws/tap/copilot-cli
```
Otherwise, please follow the manual instructions [here](https://aws.github.io/copilot-cli/docs/getting-started/install/)

### Deploy with an automated script

The following script command can be executed to deploy both the CDK infrastructure and Copilot services for a development environment
```
./devDeploy.sh
```
Options:
```
./devDeploy.sh -h

Deploy migration solution infrastructure composed of resources deployed by CDK and Copilot

Options:
  --skip-bootstrap        Skip one-time setup of installing npm package, bootstrapping CDK, and building Docker images.
  --skip-copilot-init     Skip one-time Copilot initialization of app, environments, and services
  --copilot-app-name      [string, default: migration-copilot] Specify the Copilot application name to use for deployment
  --destroy-region        Destroy all CDK and Copilot CloudFormation stacks deployed, excluding the Copilot app level stack, for the given region and return to a clean state.
  --destroy-all-copilot   Destroy Copilot app and all Copilot CloudFormation stacks deployed for the given app across all regions.
  -r, --region            [string, default: us-east-1] Specify the AWS region to deploy the CloudFormation stacks and resources.
  -s, --stage             [string, default: dev] Specify the stage name to associate with the deployed resources

```

Requirements:
* AWS credentials have been configured
* CDK and Copilot CLIs have been installed

### Deploy commands one at a time

The following sections list out commands line-by-line for deploying this solution

#### Importing values from CDK
The typical use case for this Copilot app is to initially use the `opensearch-service-migration` CDK to deploy the surrounding infrastructure (VPC, OpenSearch Domain, Managed Kafka (MSK)) that Copilot requires, and then deploy the desired Copilot services. Documentation for setting up and deploying these resources can be found in the CDK [README](../cdk/opensearch-service-migration/README.md).

The provided CDK will output export commands once deployed that can be ran on a given deployment machine to meet the required environment variables this Copilot app uses:
```
export MIGRATION_VPC_ID=vpc-123;
export MIGRATION_DOMAIN_ENDPOINT=vpc-aos-domain-123.us-east-1.es.amazonaws.com;
export MIGRATION_CAPTURE_MSK_SG_ID=sg-123;
export MIGRATION_COMPARATOR_EFS_ID=fs-123;
export MIGRATION_COMPARATOR_EFS_SG_ID=sg-123;
export MIGRATION_PUBLIC_SUBNETS=subnet-123,subnet-124;
export MIGRATION_PRIVATE_SUBNETS=subnet-125,subnet-126;
export MIGRATION_KAFKA_BROKER_ENDPOINTS=b-1-public.loggingmskcluster.123.45.kafka.us-east-1.amazonaws.com:9198,b-2-public.loggingmskcluster.123.46.kafka.us-east-1.amazonaws.com:9198
```

#### Setting up existing Copilot infrastructure

It is **important** to run any `copilot` commands from within this directory (`deployment/copilot`). When components are initialized the name given will be searched for in the immediate directory structure to look for an existing `manifest.yml` for that component. If found it will use the existing manifest and not create its own. This Copilot app already has existing manifests for each of its services and a dev environment, which should be used for proper operation.

When initially setting up Copilot, each component (apps, services, and environments) need to be initialized. Beware when initializing an environment in Copilot, it will prompt you for values even if you've defined them in the `manifest.yml`, though values input at the prompt are ignored in favor of what was specified in the file.

If using temporary environment credentials when initializing an environment:
* Copilot will prompt you to enter each variable (AWS Access Key ID, AWS Secret Access Key, AWS Session Token). If these variables are already available in your environment, these three prompts can be `enter`'d through and ignored. 
* When prompted ` Would you like to use the default configuration for a new environment?` select `Yes, use default.` as this will ultimately get ignored for what has been configured in the existing `manifest.yml`
* The last prompt will ask for the desired deployment region and should be filled out as Copilot will store this internally.

**Note**: This app also contains `kafka-broker` and `kafka-zookeeper` services which are currently experimental and usage of MSK is preferred. These services do not need to be deployed, and as so are not listed below.
```
// Initialize app
copilot app init

// Initialize env with required "dev" name
// Be cautious to specify the proper region as this will dictate where resources are deployed
copilot env init --name dev

// Initialize services with their respective required name
copilot svc init --name traffic-replayer
copilot svc init --name traffic-comparator
copilot svc init --name traffic-comparator-jupyter

copilot svc init --name elasticsearch
copilot svc init --name capture-proxy
copilot svc init --name opensearch-benchmark

```

#### Deploying Services to an Environment
When deploying a service with the Copilot CLI, a status bar will be displayed that gets updated as the deployment progresses. The command will complete when the specific service has all its resources created and health checks are passing on the deployed containers.

Currently, it seems that Copilot does not support deploying all services at once (issue [here](https://github.com/aws/copilot-cli/issues/3474)) or creating dependencies between separate services. In light of this, services need to be deployed one at a time as show below.

```
// Deploy environment
copilot env deploy --name dev

// Deploy services to a deployed environment
copilot svc deploy --name traffic-comparator-jupyter --env dev
copilot svc deploy --name traffic-comparator --env dev
copilot svc deploy --name traffic-replayer --env dev

copilot svc deploy --name elasticsearch --env dev
copilot svc deploy --name capture-proxy --env dev
copilot svc deploy --name opensearch-benchmark --env dev
```

### Running Benchmarks on the Deployed Solution

Once the solution is deployed, the easiest way to test the solution is to exec into the benchmark container and run a benchmark test through, as the following steps illustrate

```
// Exec into container
copilot svc exec -a migration-copilot -e dev -n opensearch-benchmark -c "bash"

// Run benchmark workload (i.e. geonames, nyc_taxis, http_logs)
opensearch-benchmark execute-test --distribution-version=1.0.0 --target-host=https://capture-proxy:443 --workload=geonames --pipeline=benchmark-only --test-mode --kill-running-processes --workload-params "target_throughput:0.5,bulk_size:10,bulk_indexing_clients:1,search_clients:1"  --client-options "use_ssl:true,verify_certs:false,basic_auth_user:admin,basic_auth_password:admin"
```

After the benchmark has been run, the indices and documents of the source and target clusters can be checked from the same benchmark container to confirm
```
// Check source cluster
curl https://capture-proxy:443/_cat/indices?v --insecure -u admin:admin

// Check target cluster
curl https://$MIGRATION_DOMAIN_ENDPOINT:443/_cat/indices?v --insecure -u admin:Admin123!
```

### Executing Commands on a Deployed Service

A command shell can be opened in the service's container if that service has enabled `exec: true` in their `manifest.yml` and the SSM Session Manager plugin is installed when prompted.
```
copilot svc exec -a migration-copilot -e dev -n traffic-comparator-jupyter -c "bash"
copilot svc exec -a migration-copilot -e dev -n traffic-comparator -c "bash"
copilot svc exec -a migration-copilot -e dev -n traffic-replayer -c "bash"
copilot svc exec -a migration-copilot -e dev -n elasticsearch -c "bash"
copilot svc exec -a migration-copilot -e dev -n capture-proxy -c "bash"
copilot svc exec -a migration-copilot -e dev -n opensearch-benchmark -c "bash"
```

### Addons

Addons are a Copilot concept for adding additional AWS resources outside the core ECS resources that it sets up. An example of this can be seen in the [traffic-replayer](traffic-replayer/addons/taskRole.yml) service which has an `addons` directory and yaml file which adds an IAM ManagedPolicy to the task role that Copilot creates for the service. This added policy is to allow communication with MSK.

Official documentation on Addons can be found [here](https://aws.github.io/copilot-cli/docs/developing/addons/workload/).

### Useful Commands

`copilot app show`: Provides details on the current app \
`copilot svc show`: Provides details on a particular service
