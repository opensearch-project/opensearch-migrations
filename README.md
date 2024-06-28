## OpenSearch upgrades, migrations, and comparison tooling

OpenSearch upgrade, migration, and comparison tooling facilitates OpenSearch migrations and upgrades. With these tools, you can set up a proof-of-concept environment locally using Docker containers or deploy to AWS using a one-click deployment script. Once set up and deployed, users can redirect their production traffic from a source cluster to a provisioned target cluster, enabling a comparison of results between the two clusters. All traffic directed to the source cluster is stored for future replay. Meanwhile, traffic to the target cluster is replayed at an identical rate to ensure a direct "apple-to-apple" comparison. This toolset empowers users to fine-tune cluster configurations and manage workloads more effectively.

## Table of Contents

- [OpenSearch upgrades, migrations, and comparison tooling](#opensearch-upgrades-migrations-and-comparison-tooling)
- [Table of Contents](#table-of-contents)
- [Supported cluster versions and platforms](#supported-cluster-versions-and-platforms)
  - [Supported Source and Target Versions](#supported-source-and-target-versions)
  - [Supported Source and Target Platforms](#supported-source-and-target-platforms)
- [Build and deploy](#build-and-deploy)
  - [Local deployment](#local-deployment)
  - [AWS deployment](#aws-deployment)
- [Developer contributions](#developer-contributions)
  - [Code Style](#code-style)
  - [Pre-commit hooks](#pre-commit-hooks)
  - [Traffic Capture Proxy and Replayer](#traffic-capture-proxy-and-replayer)
  - [Fetch Migration](#fetch-migration)
  - [Running Tests](#running-tests)
- [Security](#security)
- [License](#license)
- [Releasing](#releasing)
- [Publishing](#publishing)

## Supported cluster versions and platforms

There are numerous combinations of source clusters, target clusters, and platforms. While the tools provided in this repository might work with various combinations, they might not support breaking changes between different source and target versions. Below is a list of supported source and target versions and platforms.

### Supported Source and Target Versions
* Elasticsearch 6.x (Coming soon...)
* Elasticsearch 7.0 - 7.17.x
* OpenSearch 1.x
* OpenSearch 2.x

### Supported Source and Target Platforms
* Self-managed (hosted by cloud provider)
* Self-managed (on-premises)
* Managed cloud offerings (e.g., Amazon OpenSearch, Amazon OpenSearch Serverless)

## Build and deploy

### Local deployment

A containerized end-to-end solution can be deployed locally using the 
[Docker Solution](TrafficCapture/dockerSolution/README.md).

### AWS deployment

Refer to [AWS Deployment](deployment/README.md) to deploy this solution to AWS.

## Developer contributions

### Code Style

There are many different source type unders this project, the overall style is enforced via `./gradlew spotlessCheck` and is verified on all pull requests.  You can ask resolve the issues automatically with `./gradlew spotlessApply`.  For java files an eclipse formatter is avaliable [formatter.xml](./formatter.xml) in the root of the project, consult your IDE extensions/plugins for how to use this formatter during development.

### Pre-commit hooks

Developers must run the "install_githooks.sh" script in order to add any pre-commit hooks.  Developers should run these hooks before opening a pull request to ensure checks pass and prevent potential rejection of the pull request."

### Traffic Capture Proxy and Replayer

The TrafficCapture directory hosts a set of projects designed to facilitate the proxying and capturing of HTTP traffic, which can then be offloaded and replayed to other HTTP(S) server(s).

More documentation on this directory including the projects within it can be found here: [Traffic Capture](TrafficCapture/README.md).

### Fetch Migration

The FetchMigration directory hosts tools that simplify the process of backfilling / moving data from one cluster to another.

Further documentation can be found here: [Fetch Migration README](FetchMigration/README.md).

### Running Tests

Developers can run a test script which will verify the end-to-end Local Docker Solution.

More documentation on this test script can be found here:
[End-to-End Testing](test/README.md)

## Security

See [CONTRIBUTING](CONTRIBUTING.md#security-issue-notifications) for more information.

## License

This project is licensed under the Apache-2.0 License.


## Releasing

The release process is standard across repositories in this org and is run by a release manager volunteering from amongst [maintainers](MAINTAINERS.md).

1. Create a tag, e.g. 0.1.0, and push it to this GitHub repository.
2. The [release-drafter.yml](.github/workflows/release-drafter.yml) will be automatically kicked off and a draft release will be created.
3. This draft release triggers the [jenkins release workflow](https://build.ci.opensearch.org/job/opensearch-migrations-release) as a result of which the opensearch-migrations toolset is released and published on artifacts.opensearch.org example as https://artifacts.opensearch.org/migrations/0.1.0/opensearch-migrations-0.1.0.tar.gz. 
4. Once the above release workflow is successful, the drafted release on GitHub is published automatically.

## Publishing

This project can be published to a local maven repository with:
```sh
./gradlew publishToMavenLocal
```

And subsequently imported into a separate gradle project with (replacing name with any subProject name)
```groovy
repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation group: "org.opensearch.migrations.trafficcapture", name: "captureKafkaOffloader", version: "0.1.0-SNAPSHOT"
    //... other dependencies
}
```

The entire list of published subprojects can be viewed with     
```sh
./gradlew listPublishedArtifacts
```


To include a testFixture dependency, define the import like

```groovy
testImplementation testFixtures('org.opensearch.migrations.trafficcapture:trafficReplayer:0.1.0-SNAPSHOT')
```
