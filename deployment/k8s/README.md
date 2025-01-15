# Kubernetes Deployment

## Prerequisites 

#### Install kubectl
Follow instructions [here](https://kubernetes.io/docs/tasks/tools/) to install the Kubernetes command-line tool. This will be the go-to tool for interacting with the Kubernetes cluster

#### Install helm
Follow instructions [here](https://helm.sh/docs/intro/install/) to install helm. helm will be used for deploying to the Kubernetes cluster

#### Install docker
Follow instructions [here](https://docs.docker.com/engine/install/) to set up Docker. Docker will be used to build Docker images as well as run a local Kubernetes cluster. Later versions are recommended.


## Local Kubernetes Cluster
Creating a local Kubernetes cluster is useful for testing and developing a given deployment. There are a few different tools for running a Kubernetes cluster locally. This documentation focuses on using [Minikube](https://github.com/kubernetes/minikube) to run the local Kubernetes cluster.

### Install Minikube
Follow instructions [here](https://minikube.sigs.k8s.io/docs/start/?arch=%2Fmacos%2Fx86-64%2Fstable%2Fbinary+download) to install Minikube

### Loading Docker images into Minikube
Since Minikube uses a different Docker registry than the normal host machine, the Docker images shown will differ from that on the host machine. The script `buildDockerImagesMini.sh` in this directory will configure the environment to use the Minikube Docker registry and build the Docker images into Minikube

Show Docker images available to Minikube
```shell
minikube image ls
```
Build Docker images into Minikube
```shell
./buildDockerImagesMini.sh
```

### Start/Pause/Delete
A convenience script `minikubeLocal.sh` is located in this directory which wraps the Minikube commands to start/pause/delete Minikube. This is useful for automatically handling items such as mounting the local repo and creating a tunnel to make localhost calls to containers
```shell
./miniKubeLocal.sh --start
./miniKubeLocal.sh --pause
./miniKubeLocal.sh --delete
```


## Deploying

### Migration Assistant environment
Guide for deploying a complete Migration Assistant environment helm chart, with the ability to enabled/disable different Migration services and clusters as needed

The full environment helm charts consists of:
* Source cluster
* Target cluster
* Migration services

**Note**: For first-time deployments and deployments after changes have been made to a dependent helm package, such as the `migration-console` chart, the following command is needed to update dependent charts
```shell
helm dependency update migration-assistant
```

The full environment helm chart can be deployed with the helm command
```shell
helm install ma migration-assistant
```

### Specific services
Guide for deploying an individual Migration service helm chart

A particular service could then be deployed with a command similar to the below.
```shell
helm install migration-console services/migration-console
```

## Uninstalling
To show all helm deployments
```shell
helm list
```

To uninstall a particular helm deployment
```shell
helm uninstall <deployment_name>
```

### AWS Initial Setup
#### Setting up EBS driver to dynamically provision PVs
```shell
# To check if any IAM OIDC provider is configured:
aws iam list-open-id-connect-providers
# If none exist, create one:
eksctl utils associate-iam-oidc-provider --cluster <cluster_name> --approve
# Create IAM role for service account in order to use EBS CSI driver in EKS
# This currently creates a CFN stack and may 
eksctl create iamserviceaccount \
    --name ebs-csi-controller-sa \
    --namespace kube-system \
    --cluster <cluster_name> \
    --role-name AmazonEKS_EBS_CSI_DriverRole \
    --role-only \
    --attach-policy-arn arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy \
    --approve
# Install add-on to EKS cluster using the created IAM role for the service account
eksctl create addon --cluster <cluster_name> --name aws-ebs-csi-driver --version latest --service-account-role-arn <role_arn> --force
# Create StorageClass to dynamically provision persistent volumes (PV)
kubectl apply -f aws/storage-class-ebs.yml
```
#### Setting up EFS driver to dynamically provision PVs
```shell
export cluster_name=<cluster_name>
export role_name=AmazonEKS_EFS_CSI_DriverRole
eksctl create iamserviceaccount \
    --name efs-csi-controller-sa \
    --namespace kube-system \
    --cluster $cluster_name \
    --role-name $role_name \
    --role-only \
    --attach-policy-arn arn:aws:iam::aws:policy/service-role/AmazonEFSCSIDriverPolicy \
    --approve
TRUST_POLICY=$(aws iam get-role --role-name $role_name --query 'Role.AssumeRolePolicyDocument' | \
    sed -e 's/efs-csi-controller-sa/efs-csi-*/' -e 's/StringEquals/StringLike/')
aws iam update-assume-role-policy --role-name $role_name --policy-document "$TRUST_POLICY"
eksctl create addon --cluster $cluster_name --name aws-efs-csi-driver --version latest --service-account-role-arn <role_arn> --force
kubectl apply -f aws/storage-class-efs.yml
```

Create an ECR to store images
```shell
./buildDockerImagesMini.sh --create-ecr
```

Build images and push to ECR
```shell
./buildDockerImagesMini.sh --sync-ecr
```
