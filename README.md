## OpenSearch Migrations

```
Data flows to OpenSearch
On wings of the morning breeze
Effortless migration
```

This repo will contain tools and documentation to assist in migrations to and upgrades of OpenSearch clusters.

## Table of Contents

- [OpenSearch Migrations](#opensearch-migrations)
    - [Table of Contents](#table-of-contents)
    - [Developer Setup](#setup-for-commits)
    - [Traffic Capture](#traffic-capture)
    - [Local Docker Solution](#local-docker-solution)
    - [End-to-End Testing](#end-to-end-testing)
    - [Cloud Deployment](#deploying-to-aws-with-copilot)
    - [Security](#security)
    - [License](#license)

## Setup for commits

Developers must run the "install_githooks.sh" script in order to add the pre-commit hook, which runs a linting command
on the `*.py` files.

## Traffic Capture

The TrafficCapture directory hosts a set of projects designed to facilitate the proxying and capturing of HTTP
traffic, which can then be offloaded and replayed to other HTTP server(s).

### Local Docker Solution

A containarized end-to-end solution can be deployed locally using Docker.

More documentation on this solution can be found here:
[Docker Solution](TrafficCapture/dockerSolution/README.md)

### End-to-End Testing

Developers can run a test script which will verify the end-to-end Local Docker Solution.

More documentation on this test script can be found here:
[End-to-End Testing](test/README.md)

## Deploying to AWS with Copilot

The containerized services that this repo provides can be deployed to AWS with the use of [Copilot](https://aws.github.io/copilot-cli/)

Documentation for getting started and deploying these services can be found here:
[AWS Deployment](deployment/copilot/README.md)

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This project is licensed under the Apache-2.0 License.
