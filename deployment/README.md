### Deployment
This directory is aimed at housing deployment/distribution methods for various migration related images and infrastructure. It is not specific to any given platform and should be expanded to more platforms as needed. 

**Notice**: The user is responsible for the cost of any underlying infrastructure required to operate the solution. We welcome feedback and contributions to optimize costs.

### Deploying Migration Assistant (ECS) Solution

Detailed instructions for deploying the CDK and setting up its prerequisites can be found in the opensearch-service-migration [README](./cdk/opensearch-service-migration/README.md).

### Deploying the Migration Assistant (EKS) Solution

Current and future development of the Migration Assistant is built upon Kubernetes (K8s).  
See the [user guide](https://github.com/opensearch-project/opensearch-migrations/wiki) for instructions 
to install Migration Assistant into EKS.  
More information to deploy in a development environment can be found [here](./k8s/aws/README.md)

### Deploying the Migration Assistant (GKE) Solution

The Migration Assistant can also be deployed to Google Kubernetes Engine (GKE) using
the GCP Terraform module, which provisions a GKE cluster, GCS snapshot bucket, and
the Google Service Account / Workload Identity bindings required by the migration
workflows. The Helm chart is installed with the GKE-specific overlay
[`valuesGke.yaml`](./k8s/charts/aggregates/migrationAssistantWithArgo/valuesGke.yaml).

See [deployment/terraform/gcp/README.md](./terraform/gcp/README.md) for the full
walkthrough, and [deployment/k8s/README.md](./k8s/README.md#gke) for the GKE
Kubernetes quick start.

### Deploying Migration solution with Docker

A containerized end-to-end solution (including a source and target cluster as well as the migration services) can be deployed locally using the
[Docker Solution](../TrafficCapture/dockerSolution/README.md).

