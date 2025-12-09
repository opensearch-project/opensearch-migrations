# Deploying Migration Assistant into EKS

The EKS solution requires less AWS resources and fewer upfront configuration
options.  To get started with the EKS solution, install one of the 'eks'
CloudFormation templates produced by `gradlew cdkSynth`

## Deploying an EKS Cluster with CloudFormation

Create the CloudFormation template for EKS from source and deploy it.  Make 
sure that up-to-date AWS credentials are available. 

```bash
echo "Build the CloudFormation templates"
gradlew :deployment:migration-assistant-solution:cdkSynth

echo "Confirm that AWS Credentials are resolvable by the aws cli."
export AWS_REGION=us-east-2
export CFN_STACK_NAME=MA-EKS-DEV-TEST
aws cloudformation deploy \
  --template-file ../../migration-assistant-solution/cdk.out/Migration-Assistant-Infra-Create-VPC-eks.template.json \
  --stack-name "$CFN_STACK_NAME" \
  --parameter-overrides Stage=devtest \
  --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM

echo "Get the exported values to set as environment variables, which should include the $MIGRATIONS_ECR_REGISTRY"
eval $(aws cloudformation list-exports --query "Exports[?starts_with(Name, \`MigrationsExportString\`)].[Value]" --output text) 

echo "Updating kubectl to use the new EKS cluster"
aws eks update-kubeconfig --region "${AWS_CFN_REGION}" --name "${MIGRATIONS_EKS_CLUSTER_NAME}"
```


## Configuring the EKS Cluster with the Migration Assistant

The user-facing script to provision the Migration Assistant can be run by 
developers from their own workspace with

```bash
export PROJECT_DIR="${PWD}/../../.."
./deployment/k8s/aws/aws-bootstrap.sh --skip-git-pull --build-images-locally --base-dir "$PROJECT_DIR"
```

Notice that the script normally clones the git repo and uses public ECR images.
These flags build the code from the current code (assuming that the script is
run from the location of this README) but otherwise performs the same 
initialization actions as users perform.  
To update images see [buildImages](../../../buildImages/README.md).
To update the K8s deployment (other than images, configmaps, workflow templates,
resource settings, etc.) see the [k8s README](../README.md).
