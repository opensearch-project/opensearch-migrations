## OpenSearch upgrades, migrations, and comparison tooling

OpenSearch upgrade, migration, and comparison tooling facilitates OpenSearch migrations and upgrades. With these tools, you can set up a proof-of-concept environment locally using Docker containers or deploy to AWS using a one-click deployment script. Once set up and deployed, users can redirect their production traffic from a source cluster to a provisioned target cluster, enabling a comparison of results between the two clusters. All traffic directed to the source cluster is stored for future replay. Meanwhile, traffic to the target cluster is replayed at an identical rate to ensure a direct "apple-to-apple" comparison. This toolset empowers users to fine-tune cluster configurations and manage workloads more effectively.

## Table of Contents

- [ Supported cluster versions and platforms](#supported-cluster-versions-and-platforms)
- [Build and deploy](#build-and-deploy)
    - [Local deployment](#local-deployment)
    - [AWS deployment](#aws-deployment)
- [Developer contributions](#developer-contributions)
    - [Pre-commit hooks](#pre-commit-hooks)
    - [Traffic Capture Proxy and Replayer](#traffic-capture-proxy-and-replayer)
    - [Running Tests](#running-tests)
- [Security](#security)
- [License](#license)

## Supported cluster versions and platforms

There are numerous combinations of source clusters, target clusters, and platforms. While the tools provided in this repository might work with various combinations, they might not support breaking changes between different source and target versions. Below is a growing support table that will be updated over time.

|Supported Sources|Supported Targets|Source Platform|Target Platform|
|:---------------:|:---------------:|:-------------:|:-------------:|
|ElasticSearch 7-7.10.2|OpenSearch 1.x and 2.x|AWS Self-Managed|AWS OpenSearch and OpenSearch Serverless|

## Build and deploy

### Local deployment

A containerized end-to-end solution can be deployed locally using the 
[Docker Solution](TrafficCapture/dockerSolution/README.md).

### AWS deployment

Refer to [AWS Deployment](deployment/copilot/README.md) to deploy this solution to AWS.

## Developer contributions

### Pre-commit hooks

Developers must run the "install_githooks.sh" script in order to add any pre-commit hooks.  Developers should run these hooks before opening a pull request to ensure checks pass and prevent potential rejection of the pull request."

### Traffic Capture Proxy and Replayer

The TrafficCapture directory hosts a set of projects designed to facilitate the proxying and capturing of HTTP traffic, which can then be offloaded and replayed to other HTTP(S) server(s).

More documentation on this directory including the projects within it can be found here: [Traffic Capture](TrafficCapture/README.md).

### Running Tests

Developers can run a test script which will verify the end-to-end Local Docker Solution.

More documentation on this test script can be found here:
[End-to-End Testing](test/README.md)

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This project is licensed under the Apache-2.0 License.
