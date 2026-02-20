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

### Deploying Migration solution with Docker

A containerized end-to-end solution (including a source and target cluster as well as the migration services) can be deployed locally using the
[Docker Solution](../TrafficCapture/dockerSolution/README.md).

