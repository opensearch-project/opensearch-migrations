# This is a scratch script that's based upon aws-bootstrap.sh.
# It's intentionally much simpler and serves as a reference point for doing a developer
# build to ECR and EKS from a local host that has gradle, docker, etc on it already
#
# This assumes that you've deployed the template
# Migration-Assistant-Infra-Create-VPC-v3.template.json
#
# Once that's been deployed, fill in the STACK_NAME and REGION and run the rest of the
# commands to build images to the private ECR and to deploy the helm chart to reference them.

# build images to a private ecr
export STACK_NAME=MA-EKS
export REGION=us-east-2

export ECR_REPO=$(echo $MIGRATIONS_ECR_REGISTRY | cut -d / -f 1)
export ACCOUNT_NUMBER=$(echo $MIGRATIONS_ECR_REGISTRY| cut -f 1 -d .)

# setup docker to push to the private ECR reop in the account
aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin $ECR_REPO
eval $(aws cloudformation describe-stacks --stack-name $STACK_NAME --region $REGION --output text --query 'Stacks[0].Outputs[?OutputKey==\`MigrationsExportString\`].OutputValue'  )

docker buildx create --name local-remote-builder --driver remote tcp://buildkitd:1234
agradle buildImagesToRegistry -PregistryEndpoint=$MIGRATIONS_ECR_REGISTRY -PimageArch=amd64 -Pbuilder=local-remote-builder

# setup helm variables to set the image locations to the newly built images
IMAGE_FLAGS=(
    --set images.captureProxy.repository="${MIGRATIONS_ECR_REGISTRY}"
    --set images.captureProxy.tag=migrations_capture_proxy_latest
    --set images.trafficReplayer.repository="${MIGRATIONS_ECR_REGISTRY}"
    --set images.trafficReplayer.tag=migrations_traffic_replayer_latest
    --set images.reindexFromSnapshot.repository="${MIGRATIONS_ECR_REGISTRY}"
    --set images.reindexFromSnapshot.tag=migrations_reindex_from_snapshot_latest
    --set images.migrationConsole.repository="${MIGRATIONS_ECR_REGISTRY}"
    --set images.migrationConsole.tag=migrations_migration_console_latest
    --set images.installer.repository="${MIGRATIONS_ECR_REGISTRY}"
    --set images.installer.tag=migrations_migration_console_latest
)

# setup kubectl context
aws eks update-kubeconfig --region $REGION --name $MIGRATIONS_EKS_CLUSTER_NAME
kubectl config set-context --current --namespace=ma

# deploy helm w/ some overrides
helm install  ma ./deployment/k8s/charts/aggregates/migrationAssistantWithArgo \
  --create-namespace \
  --namespace ma \
  --timeout 20m \
  -f ./deployment/k8s/charts/aggregates/migrationAssistantWithArgo/values.yaml \
  -f ./deployment/k8s/charts/aggregates/migrationAssistantWithArgo/valuesEks.yaml \
  --set stageName=$STAGE \
  --set aws.region=$REGION \
  --set aws.account=$ACCOUNT_STACK_NAME \
  --set defaultBucketConfiguration.snapshotRoleArn=$SNAPSHOT_ROLE \
  "${IMAGE_FLAGS[@]}"

# See localTesting.sh for port forwards to run for debugging
