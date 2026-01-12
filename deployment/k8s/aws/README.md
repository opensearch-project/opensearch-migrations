# Deployment of the Migration Assistant into EKS

This guide is for developers that are interested in building everything and
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
echo "Setting up buildkit to perform builds but should use ECR for image pushes"
export USE_LOCAL_REGISTRY=false
./buildImages/setUpK8sImageBuildServices.sh
./deployment/k8s/aws/aws-bootstrap.sh --skip-git-pull --build-images-locally --base-dir "$(git rev-parse --show-toplevel)"
```

Notice that the `aws-bootstrap.sh` script normally clones the git repo and uses
public ECR images. These flags build the code from the current code 
(assuming that the script is run from the location of this README) but 
otherwise performs the same initialization actions as users perform.  
To update images see [buildImages](../../../buildImages/README.md).
To update the K8s deployment (other than images, configmaps, workflow templates,
resource settings, etc.) see the [k8s README](../README.md).
