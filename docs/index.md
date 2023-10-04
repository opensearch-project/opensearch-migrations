# OpenSearch UpgradeOptim

### Migrating, upgrading, and sometimes just comparing the performance of two clusters


### What is this?

OpenSearch UpgradeOptim is used to facilitate migrations to OpenSearch, upgrades to newer versions of OpenSearch, and optimizing a cluster based on traffic patterns. This package can help you can set up a proof-of-concept environment locally using Docker containers or deploy to AWS using a one-click deployment script. This empowers you to fine-tune cluster configurations and manage workloads more effectively before migration.



## Table of Contents

[Overview](#overview)  

[System Requirements](#system-requirements)

[High Level Overview](#high-level-overview)

[Operator Guides](#operator-guides)

[Developer Guides](#developer-guiders)

[FAQ (Frequently Asked Questions)](#faq)

[Cost of running](#cost-of-running)

[Security](#security)

[Support and Contact Information](#support-and-contact-information)

[License](#license)


## Overview
- Purpose of the guide
- Intended audience
- Glossary
    - Capture Proxy
    - Source Cluster - OpenSearch/ Elasticsearch
    - Kafka Event Streamer 
        - ZooKeeper
    - Replayer
    - Results Store
        - EFS - Elastic File Store
        - OpenSearch Cluster
    - Target Cluster - OpenSeach/ Elasticsearch
    - Monitoring Dashboard
- Getting Started


## System requirements
This solution may work with other verions of the libraries below but it has been tested with the following:
 - Java 11 (There is a restriction so that the solution will not build with other verions)
 - Gradle 8
 - Python 3 (for experimental tooling and results comparisons)
 - AWS Cloud Developerment Kit (CDK) 2.84 (for AWS deployments)
 - Docker version 4.12.0, Engine 20.10.17, Compose v2.10.2
  

## High Level Overview 
 - Key features and benefits
 - Supported migrations paths

## Operator Guides
 - How to deploy locally with Docker
 - Setting up in AWS 
 - Setting up in OCI
 - How to run
    - Fetch Migrations - Moving data that exists on a source cluster to target cluster
    - Live Migration - Moving live data from a source to target cluster
    - How to transition from historical data to live data
    - How to capture, replay, and reset to test optimize your cluster
 - Reference implementation for Amazon OpenSearch Service upgrade
 - Troubleshooting

## Developer Guides
 - How to test
 - How to setup
 - Developer Guidelines
    - Environment setup
    - Branching strategy
    - Code quality
    - Testing
    - Commit messages
    - Documentation
    - Pull Requests
    - Issue Tracking
        - github issues
        - jira project
    - Continuous Integration/ Continuous Deployment
    - Code Review

 - Where to go for project updates


## FAQ (Frequently Asked Questions)

## Solution Costs

## Security

## Solution

## Support and Contact Information

## Warranty information

## License

This project is licensed under the Apache-2.0 License.


