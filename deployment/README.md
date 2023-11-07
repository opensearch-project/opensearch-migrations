### Deployment
This directory is aimed at housing deployment/distribution methods for various migration related images and infrastructure. It is not specific to any given platform and should be expanded to more platforms as needed. 

**Notice**: The user is responsible for the cost of any underlying infrastructure required to operate the solution. We welcome feedback and contributions to optimize costs.


### Deploying Migration solution with Docker

A containerized end-to-end solution (including a source and target cluster as well as the migration services) can be deployed locally using the
[Docker Solution](../TrafficCapture/dockerSolution/README.md).

### Deploying Migration solution to AWS

**Note**: These features are still under development and subject to change

Detailed instructions for deploying the CDK and setting up its prerequisites can be found in the opensearch-service-migration [README](./cdk/opensearch-service-migration/README.md).