# Deployment of the Migration Assistant into EKS

This guide is for **developers** that are interested in building everything and
deploying those artifacts directly to EKS themselves.

For a more details about Kubernetes, see the [README](../README.md)
for the overall K8s deployment project.

See the [project wiki](https://github.com/opensearch-project/opensearch-migrations/wiki)
for instructions of how to deploy directly from AWS without installing anything.


## Difference from the ECS version

The EKS solution requires less AWS resources and fewer upfront configuration
options than the previous release build upon ECS.  You'll need some permissions,
but the CloudFormation should deploy in < 15 minutes.  Once that succeeds, you
should have all the permissions that you need to deploy and run the 
Migration Assistant on Kubernetes. 


# Quick Start for EKS

## Prerequisites

As a developer, you'll need to install
* Java Development Kit (JDK) 11-17
* [kubectl](https://kubernetes.io/docs/tasks/tools/) 
* [helm](https://helm.sh/docs/intro/install/)
* [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) and an AWS Account.


## Step 1: Deploying an EKS Cluster with CloudFormation

Create the CloudFormation template for EKS from source and deploy it.  Make 
sure that up-to-date AWS credentials are available. 

```bash
echo "Build the CloudFormation templates"
pushd $(git rev-parse --show-toplevel)
./gradlew :deployment:migration-assistant-solution:cdkSynthMinified

echo "Confirm that AWS Credentials are resolvable by the aws cli."
export AWS_REGION=us-east-2
export CFN_STACK_NAME=MA-EKS-DEV-TEST
aws cloudformation deploy \
  --template-file deployment/migration-assistant-solution/cdk.out-minified/Migration-Assistant-Infra-Create-VPC-eks.template.json \
  --stack-name "$CFN_STACK_NAME" \
  --parameter-overrides Stage=devtest \
  --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM

aws cloudformation wait stack-create-complete \
  --stack-name "${CFN_STACK_NAME}" --region "${AWS_REGION}"

echo "Get the exported values to set as environment variables, which should include the $MIGRATIONS_ECR_REGISTRY"
eval $(aws cloudformation list-exports --query "Exports[?starts_with(Name, \`MigrationsExportString\`)].[Value]" --output text) 

echo "Updating kubectl to use the new EKS cluster"
aws eks update-kubeconfig --region "${AWS_CFN_REGION}" --name "${MIGRATIONS_EKS_CLUSTER_NAME}"
echo "For convenience, setting the default namespace for all kubectl commands to 'ma' (default for aws-bootstrap.sh)"
kubectl config set-context --current --namespace=ma
```


## Step 2: Deploying the Migration Assistant onto the EKS Cluster

The user-facing script to provision the Migration Assistant can be run by 
developers from their own workspace with

```bash
pushd $(git rev-parse --show-toplevel)
export AWS_CFN_REGION=us-east-2
./deployment/k8s/aws/aws-bootstrap.sh --skip-git-pull --build-images-locally --base-dir "$(git rev-parse --show-toplevel)"
```

### Building locally to EKS

Notice that the `aws-bootstrap.sh` script normally clones the git repo and uses
public ECR images. These flags build the code from the current code 
(assuming that the script is run from the location of this README) but 
otherwise performs the same initialization actions as users perform.  
To update images see [buildImages](../../../buildImages/README.md).
To update the K8s deployment (other than images, configmaps, workflow templates,
resource settings, etc.) see the [k8s README](../README.md).

When building images (`--build-images` or `--build-images-locally`), the script,
by default, automatically configures cross-platform builds for amd64 and arm64.

1. A `general-work-pool` NodePool is pre-created from the Migration Assistant  
   chart.  By default, both `amd64` and `arm64` are enabled 
   (see `valuesEks.yaml` to adjust).  * If that has been overridden so that only
   one architecture is enabled, the nodepool set up for the  migration-console
   would need to be adjusted since it includes both architectures. 
2. The aws-bootstrap.sh script, when configuring --build-local* queries
   this nodepool to determine which architectures that it can build
3. When multiple architectures are configured, native buildkit pods are deployed 
   on each architecture for parallel docker builds.
4. If only one architecture is configured, we should only build for one 
   architecture.

To change the general-work-pool that will be used by default, 
change these values in within the `--helm-values` passed into the script.

```yaml
workloadsNodePool:
  architectures: ["arm64"]
  limits:
    cpu: "128"
    memory: "256Gi"
```

Or to use only amd64:

```yaml
workloadsNodePool:
  architectures: ["amd64"]
```

### In-K8s Source/Target Test Clusters

Integ tests spin up a number of Elasticsearch/OpenSearch clusters through argo
to test various workflows.  In many development scenarios, it can be helpful
to have a warm source and target cluster that are ready immediately.  To spin
up an ES 7.10 and OS 2.11 (no reason it's that version), install the following
chart with the specified overrides


```bash
pushd $(git rev-parse --show-toplevel)/deployment/k8s/charts/aggregates/
eval $(aws cloudformation list-exports --query "Exports[?starts_with(Name, \`MigrationsExportString\`)].[Value]" --output text) 
export ELASTIC_SEARCH_IMAGE_TAG=migrations_elasticsearch_searchguard_latest
helm upgrade --install -n ma tc testClusters \
  -f testClusters/valuesEks.yaml \
  --set "source.image=$MIGRATIONS_ECR_REGISTRY" \
  --set "source.imageTag=$ELASTIC_SEARCH_IMAGE_TAG" \
  --set "source.extraInitContainers[0].image=$MIGRATIONS_ECR_REGISTRY:$ELASTIC_SEARCH_IMAGE_TAG" \
  --set "source.extraContainers[0].image=$MIGRATIONS_ECR_REGISTRY:$ELASTIC_SEARCH_IMAGE_TAG"
```