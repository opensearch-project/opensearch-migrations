# Migration Console

## Introduction

**Overview**
The Migration Console serves as the control plane for orchestrating and managing the components of the Migration Assistant. It coordinates the activities of various components in the migration pipeline and provides a single point of interaction for users. The Migration Console can refer both to the ECS instance deployed in the user's account and selected VPC, and the command line application installed on that instance to interact with the Migration Assistant components.

**Goals and Scope**:

- Ensure centralized control and visibility into the migration process.
- Provide interfaces for managing communication between the Migration Console and other components.
- Operate statelessly with a transparent source of truth for predictable and configurable behavior.

## System Architecture

The Migration Console is deployed into the customer's VPC and maintains connections with effectively all other components of the Migration Assistant. The below diagram is not comprehensive and does not illustrate any of the data flow between various other components.

![[diagrams/migration-console-arch.svg]]
[Source](https://tiny.amazon.com/jmayhucf/desia2zIMi)

### Individual Connections

#### Bootstrap Box

The Bootstrap Box an EC2 instance that's used to deploy all other services and components of the Migration Assistant solution, including the Migration Console. The Migration Console is accessed from the Bootstrap Box using the `./accessContainer` script which uses the AWS CLI `execute-command` function to start an interactive bash session on the Migration Console. This is how a user runs commands on the Migration Console.

#### Source Cluster

The source cluster connection is optional.  A customer can configure the `cdk.context.json` with source cluster details (endpoint, auth method, etc.), which are populated in the `migration-services.yaml` file. The Migration Console uses these details directly to make http requests, like `/_cat/indices`, and also passes them to other programs running on the Migration Console box, specifically `CreateSnapshot`.

#### Target Cluster

The target cluster is also populated in the `cdk.context.json` and the Migration Console uses the connection details to make http requests directly, as well as passing them to the `MetadataMigration` tool. Direct HTTP requests are used during an RFS migration to query the `.migrations_working_state` index and inform the user about the progress of the backfill.

#### Kafka

In a migration with replayer components deployed, the Migration Console has some utility tools to query or manipulate the Kafka cluster, to e.g. create, delete or describe topics. The Kafka broker endpoints are populated in the `migration-services.yaml` by the CDK deployment and also specify whether IAM properties should be added to the requests for AWS Managed Service Kafka.

#### Reindex-From-Snapshot & Replayer Service

The Reindex-From-Snapshot and Replayer services are deployed (if enabled) by the CDK scripts to ECS, in the same cluster as the Migration Console. The Migration Console does not directly interact with either of these services. Instead, it uses the AWS API to manipulate them at the control-plane level by changing the number of desired services to disable, enable, and scale the services.

#### Shared Logs EFS Volume

The Shared Logs Volume is an EFS volume that's mounted to the Migration Console task, as well as the Replayer task (if deployed). Logs are written to the volume from the Replayer (in the form of tuples) and processes on the Migration Console (specifically Metadata Migration). These logs can be accessed directly by the user (e.g. with shell commands `ls`, `cat`, `jq`, etc.) or, in the case of tuples, manipulated using Migration Console CLI commands.

#### Cloudwatch Metrics

The various services making up the Migration Assistant emit metrics to Cloudwatch. The Migration Console contains basic functionality to query the metrics in Cloudwatch via the AWS SDK.

## Library Architecture and Interface

The Console Library is intended to be a layer that provides a unified way to access the many components involved in a migration, regardless of the specific options enabled and where they are deployed.

It allows "frontends" (a CLI app, a Web API, etc.) to ask questions and control a migration while being insulated from the details of how to communicate with each component ("backend services").

The overall architecture can be seen in this diagram. Each of the subsections below discusses a component in more detail, moving from left (user) to right (deployed services). Green indicates examples, not definite implementations.

![[diagrams/migration-console-library.svg]]
[source](diagrams/migration-console-library-source.md) (This is an [excalidraw](https://github.com/excalidraw/excalidraw) markdown file, not particularly human readable.)

### Interfaces

The frontends currently envisioned are a command line interface (CLI) and a web API. While these are not explicitly part of the library, they are closely linked and discussing them is helpful context for the design decisions of the library interfaces.

#### CLI

In the near term of the Migration Assistant, the CLI is the primary way for users to manage and understand their migration. After deploying their tools, a user will log onto the Migration Console and use the CLI for tasks like starting and checking the progress of their historic backfill, turning on and off the replayer, running `_cat/indices` against the source and target cluster, etc. These functions will be performed in the same way by the user, regardless of whether their tools are deployed locally on Docker, remotely on AWS, a different hosting platform, or a combination thereof.

#### Web API

Parallel to the CLI, there will be a web API to access the same functionality. A web API to access the full functionality, with authorization, etc., is not on our roadmap yet, but this is envisioned as being a powerful way for users to access and manage their migration from their own environment, potentially programatically. We could also use the web API to build a web frontend with UI, etc. down the line.

##### DMS API

In the short term, a small subset of the functionality is necessary to meet the requirements of our in-progress collaboration with Database Migration Service (DMS). In some of these cases, DMS has specified an API contract that doesn't align with our plan for the API endpoints and data received.  In these cases, we will build out DMS specific endpoints that meet these exact purposes without trying to also match the general case.

#### Generation from a spec

For the CLI and (standard) web API, the intention is that the “frontend” should be very small—essentially just the necessary code to get the user’s data from the input source (either CLI commands or http requests) into the library itself. To accomplish this, the actual endpoints in both cases could be generated from an API spec or one of them from the other. This will be a helpful way to minimize the developer effort to support additional commands and ensure that behavior is consistent between the two modalities.

#### Custom Frontends

It is also possible and supported for users or ourselves in the future to write additional custom frontends that leverage the library. For instance, we could write a "wizard" that walks a user step-by-step through a deployment and migration, by importing the library and leveraging its abstraction for the services. Or an SA could write an application that manages automatic performance testing with a large fleet of target clusters and replayers.

While we have no immediate plans to write applications like this, the library is currently being used directly by the end to end integration tests.

### Configuration & the Environment Object

Configuration defines the services, connection details, and preferences for a migration. The "inner" parts of the library don't interact directly with any configuration files, they rely on an Environment object that contains and manages the implementation models for each involved component. The default behavior is that this Environment object is instantiated from a file at `/etc/migrations_services.yaml`. However, any frontend/user can specify a different file or can instantiate an Environment object via another strategy entirely.

#### Configuration with a `services.yaml` file

As discussed above and additionally in the next session, a `services.yaml` file is effectively an implementation detail and not the only way of specifying a configuration. However, in our current implementation, it is the default and most widely used way and therefore merits some discussion here. The YAML file has a fully defined spec in the [Migration Console README](https://github.com/opensearch-project/opensearch-migrations/blob/main/TrafficCapture/dockerSolution/src/main/docker/migrationConsole/lib/console_link/README.md#servicesyaml-spec).

In general, there is one block in the YAML file for each object that will be instantiated, with all of the configuration details necessary to determine which model implementation should be created and used. The structure of the YAML is enforced by schemas in each model.

#### The Environment Object

One of the core objects used to interact with the library is an `Environment` object that contains and manages the implementation objects for each deployed service. It can be thought of as the in-code implementation of the `services.yaml` file. Each service described in the YAML will be mapped to its appropriate model, instantiated with the provided settings, and held in the Environment object.

This Environment object will be a parameter to every command function in the middleware layer because it contains the full context of the deployed services.

The frontend has a few options for how to interact with the Environment object. In the basic case, the frontend can ask the middleware layer to instantiate and populate the Environment. In this case, the middleware will load the YAML (from the default or a specified path), validate it, and create implementation models for each service involved. It will then return the Environment object to the frontend, and the frontend will supply it along with all command calls.

In the "power user" case--likely to be used by the DMS API, which may not rely on a `services.yaml` file--the frontend can start with an empty Environment, and define and instantiate the underlying models itself. When that Environment is passed into the commands exposed by the middleware layer, it will be used as the source of truth for all deployed services. One option for tools taking this option would be to define a custom configuration format and include an Environment builder/factory to convert that configuration into a suitable Environment object.

### Middleware Layer

The middleware layer is the public-facing API of the library as a whole. As much logic as possible related to error handling, type checking, manipulating arguments, and calling functions on the underlying models should happen through the middleware layer. This allows the frontend interfaces (CLI, web API, etc.) to remain very thin and suitable for generation from a spec.

Each call into the middleware layer should include three pieces of data: the command to be called (e.g. `backfill scale`), any arguments for it (e.g. `units=5`), and the Environment object (which in this case might include a `RFS on ECS Backfill` model). The middleware layer is responsible for calling the appropriate function on the model, validating and passing in the arguments, and handling errors during the execution, and passing the result back to the original caller.

### Model Interfaces & Implementations

Models contain the logic for interacting with each component of the migration. There are two subtypes here: "simple" models and those with multiple implementations.  These aren't hard-and-fast distinctions. A model that currently only has one implementation may end up with additional implementations in the future.

Models with multiple implementations have an [Abstract Base Class](https://docs.python.org/3/library/abc.html) that defines the interface for the model and sets a contract for implemented functions. Each implementation inherits from the base class. This means that the middleware layer doesn't know or care which implementation is being used because it relies on the contract of the abstract base class.

For instance, a `Cluster` model is initialized with the endpoint and auth details for a given cluster and is responsible for using those details to make API calls to the cluster whether the user invokes a cluster command directly (e.g. `console cluster cat-indices`) or indirectly (e.g. when a user invokes `console snapshot status`, the `Snapshot` model makes a call to the `Cluster` model to check the status of the snapshot). This allows us to keep the logic to make a cluster API call exclusively in one place. Currently, we only have a single `Cluster` implementation, but in the future, perhaps a new (or very old) version of Elasticsearch or OpenSearch would need a different client and this would be encapsulated by creating a variation of the current `Cluster` model. This new version would handle the custom client without exposing that implementation detail to the middleware layer or the `Snapshot` model that asks for a given API call to be made.

Effectively, the only component that needs to know which implementation of a model is being used is the Environment which makes the objects available to the middleware layer.
