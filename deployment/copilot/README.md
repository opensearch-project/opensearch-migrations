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
Otherwise please follow the manual instructions [here](https://aws.github.io/copilot-cli/docs/getting-started/install/)


#### Importing values from CDK
While not a requirement, the typical use case for this Copilot app is to initially use the `opensearch-service-migration` CDK to deploy the surrounding infrastructure (VPC, OpenSearch Domain, Managed Kafka (MSK)) and then deploy the desired Copilot services, which will use these resources, afterwards

The provided CDK will output export commands once deployed that can be ran on a given deployment machine to meet the required environment variables this Copilot app uses:
```
export MIGRATION_VPC_ID=vpc-123;
export MIGRATION_PUBLIC_SUBNET_1=subnet-123;
export MIGRATION_PUBLIC_SUBNET_2=subnet-124;
export MIGRATION_DOMAIN_ENDPOINT=vpc-aos-domain-123.us-east-1.es.amazonaws.com;
export MIGRATION_COMPARATOR_EFS_ID=fs-123;
export MIGRATION_COMPARATOR_EFS_SG_ID=sg-123;
export MIGRATION_KAFKA_BROKER_IDS=b-1-public.loggingmskcluster.123.45.kafka.us-east-1.amazonaws.com:9198,b-2-public.loggingmskcluster.123.46.kafka.us-east-1.amazonaws.com:9198
```

#### Setting up existing Copilot infrastructure

When initially setting up Copilot; apps, services, and environments need to be initialized. Beware when initializing an environment in Copilot, it will prompt you for values even if you've defined them in manifest.yml though values input at the prompt are ignored in favor of what was specified in the file.

If using temporary environment credentials when initializing an environment, Copilot will prompt you to enter each variable (AWS Access Key ID, AWS Secret Access Key, AWS Session Token). If you have another means for exporting these variables to your environment, these three prompts can be `enter`'d through in favor of using another means. The last prompt will ask for the desired deployment region and should be filled out as Copilot will store this internally.

**Note**: This app also contains `kafka-broker` and `kafka-zookeeper` services which are currently experimental and usage of MSK is preferred. These services do not need to be deployed, and as so are not listed below.
```
// Initialize app
copilot app init

// Initialize env
// If using temporary credentials, when prompted select 'Temporary Credentials' and press `enter` for each default value as these can be ignored
// Be cautious to specify the proper region as this will dictate where resources are deployed
copilot env init --name test

// Initialize services
copilot svc init --name kafka-puller
copilot svc init --name traffic-replayer
copilot svc init --name traffic-comparator
copilot svc init --name traffic-comparator-jupyter

```

### Deploying Services to an Environment
When deploying a service with the Copilot CLI, a status bar will be displayed that gets updated as the deployment progresses. The command will complete when the specific service has all its resources created and health checks are passing on the deployed containers.

Currently, it seems that Copilot does not support deploying all services at once or creating dependencies between separate services. In light of this, services need to be deployed one at a time as show below.

```
// Deploy service to a configured environment
copilot svc deploy --name traffic-comparator-jupyter --env test
copilot svc deploy --name traffic-comparator --env test
copilot svc deploy --name traffic-replayer --env test
copilot svc deploy --name kafka-puller --env test
```

### Executing Commands on a Deployed Service

A command shell can be opened in the service's container if that service has enabled `exec: true` in their `manifest.yml` and the SSM Session Manager plugin is installed when prompted.
```
copilot svc exec traffic-comparator --container traffic-comparator --command "bash"
```

### Addons

Addons are a Copilot concept for adding additional AWS resources outside the core ECS resources that it sets up. An example of this can be seen in the [kafka-puller](kafka-puller/addons/taskRole.yml) service which has an `addons` directory and yaml file which adds an IAM ManagedPolicy to the task role that Copilot creates for the service. This added policy is to allow communication with MSK.

Official documentation on Addons can be found [here](https://aws.github.io/copilot-cli/docs/developing/addons/workload/).

### Useful Commands

`copilot app show`: Provides details on the current app \
`copilot svc show`: Provides details on a particular service
