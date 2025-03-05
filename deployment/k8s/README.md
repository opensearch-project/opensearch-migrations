# Kubernetes Deployment

Audience: This is meant to be a fairly conclusive document for developers looking to build/maintain a Kubernetes deployment of the Migration Assistant tools from this (opensearch-migrations) repository to support customers that want to...
1. Migrate their configurations and data from a source cluster to a target cluster
2. Compare results between multiple different clusters (next up?)

## Prerequisites 

#### Install kubectl
Follow instructions [here](https://kubernetes.io/docs/tasks/tools/) to install the Kubernetes command-line tool. This will be the go-to tool for interacting with the Kubernetes cluster

#### Install helm
Follow instructions [here](https://helm.sh/docs/intro/install/) to install helm. helm will be used for deploying to the Kubernetes cluster

#### Install docker
Follow instructions [here](https://docs.docker.com/engine/install/) to set up Docker. Docker will be used to build Docker images as well as run a local Kubernetes cluster. Later versions are recommended.

#### Provision a Kubernetes cluster

We test our solution with minikube and are beginning to test Amazon EKS.  See below for more information to set these up.

## Setup local Kubernetes Cluster
Creating a local Kubernetes cluster is useful for testing and developing a given deployment. There are a few different tools for running a Kubernetes cluster locally. This documentation focuses on using [Minikube](https://github.com/kubernetes/minikube) to run the local Kubernetes cluster.

### Install Minikube
Follow instructions [here](https://minikube.sigs.k8s.io/docs/start/?arch=%2Fmacos%2Fx86-64%2Fstable%2Fbinary+download) to install Minikube

The default number of CPUs and Memory settings for Minikube can sometimes be relatively low compared to your machine's resources. The default resources allocated are printed out on minikube startup, similar to:
```shell
🔥  Creating docker container (CPUs=2, Memory=7788MB) ...
```
To increase these resources, make sure your Docker environment has enough allocated resources respectively, and execute commands similar to below:
```shell
minikube config set cpus 8
minikube config set memory 12000
```

### Start/Pause/Delete
A convenience script `minikubeLocal.sh` is located in this directory which wraps the Minikube commands to start/pause/delete Minikube. This is useful for automatically handling items such as mounting the local repo and creating a tunnel to make localhost calls to containers
```shell
./miniKubeLocal.sh --start
./miniKubeLocal.sh --pause
./miniKubeLocal.sh --delete
```

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

## Deploying

### Migration Assistant environment
Guide for deploying a complete Migration Assistant environment helm chart, with the ability to enabled/disable different Migration services and clusters as needed

The full Migration Assistant (charts/aggregates/migrationAssistant) helm chart consists of:
* Migration Console
* Capture Proxy
* Traffic Replayer
* Document Backfill ("RFS")
* Observability services - Prometheus, Jaeger, and Grafana

By default, all of these components will be deployed, but if a user is certain that some components aren't necessary, users can disable their installation by setting `conditionalPackageInstalls.<<PACKAGE_NAME>>` in the values that are being passed to the helm install command.

**Note**: For first-time deployments and after changes have been made to a dependent helm package, such as the `migrationConsole` chart, the following command is needed to update dependent charts
```shell
helm dependency update 
```

The full Migration Assistant environment with source and target test clusters can be deployed with the following helm commands
```shell
helm install ma -n ma charts/aggregates/migrationAssistant --create-namespace
helm install tc -n ma charts/aggregates/testClusters
```

Alternatively, specific test cluster configurations for elasticsearch and opensearch can be deployed instead of the default source and target configuration. Existing alternative configurations can be found in the `charts/components/elasticsearchCluster/environments` and `charts/components/opensearchCluster/environments` directories and deployed with helm commands similar to below
```shell
helm install tc-source -n ma charts/components/elasticsearchCluster -f charts/components/elasticsearchCluster/environments/es-5-6-single-node-cluster.yaml
helm install tc-target -n ma charts/components/opensearchCluster -f charts/components/opensearchCluster/environments/os-2-latest-single-node-cluster.yaml
```

### Configuration

Configuring the migration tools is one of the most critical parts of the user journey.  For example, what's the address of the source cluster, how fast should the replayer resend data, and what transformations does the user want to run on their data?

The applications included in this Migrations repository make few assumptions about the environments that they're running in and are generally configured via command line options.  If parameters need to be changed, those applications will need to be restarted to pick up the new parameters (eventually, we'll have Kubernetes facilitate handling that task recycling).

#### Bootstrapping K8s Configs into Applications

The deployment charts in this package to wire the customer's configurations into the launch of the applications.  Configurations are generally stored within [ConfigMaps](https://kubernetes.io/docs/concepts/configuration/configmap/) and [Secrets](https://kubernetes.io/docs/concepts/configuration/secret/) (none yet, but we'll eventually need to).  Component charts share templates to create **initialization containers** (named "arg-prep") that shim Kubernetes-specific configurations into the "main" containers.  In this case, the main container is the one that's running the service code.  Using a shim allows the main containers to launch directly from the same migration images that are applicable for any containerized environment.  The arg-prep container pulls all configurations that each application/container needs into environment variables, like the arguments passed to the invocation of the application.  Since the arg-prep container isn't going to run the main containers directly, instead of launching the main container's task, it writes all of those variables and the command-line arguments into a file.  That file is on a share-mounted volume that the main container reads from.  The main container for each application (migration-console included) then sources that file into its own shell before invoking the main command with the $ARGS variable that it had just sourced.  Notice that all the original environment variables are also sourced into the main container so that those containers can easily discover other configurations without needing to parse command line arguments (e.g. is the destination using https; is the password included; etc).

#### Setting values

To specify values, first consult the individual charts' READMEs for parameter document (these are a work-in-progress).  Notice that when installing aggregate charts, configurations for individual components must be rooted within the key with the name of the component.  For example, the following shows part of the values.yaml from the root to configure the replayer component of the `charts/aggregates/migrationAssistant` chart.

```
replayer:
  parameters:
    targetUri:
      source: parameterConfig
      value: https://opensearch-cluster-master.tc:9200/
      allowRuntimeOverride: true
```

Values that are passed to the main command that a container will run (usually the entrypoint) are defined in "parameters".  Those parameters contain a map of values to send to the command line.  Each of the keys typically represents a flag that will be passed to the entrypoint (the pod setup will handle if a specific value should be passed instead as a positional argument).

Before going into explaining how values are stored and how they're moved from those stores into application invocations, it's useful to understand the motivation behind the design.  Each configuration (ConfigMap or Secret) should be an indivisible unit for the customer.  That simplifies **granular management** of settings and removes the risk of updating races across the set of configurations.  For example, one caller can set the current speedup factor for the replayer and another can set a policy for metadata transformation and neither call has risk to change other values because it was holding stale data.  Everything is updated and the last committer wins.  Configurations are also divided between deploy-time and run-time so that we don't lose what we had done at deploy time, nor do we require a redeployment to change some settings that should otherwise have deployment time defaults.  Lastly, values can also be shared for multiple applications so that users can define a value in one place (a source cluster, or a bucket for a snapshot) and it can be used by multiple applications through a shared source of truth.  

The migrationAssistant chart constructs **shared** values that multiple pods may utilize so that common values can be shared across the kubernetes cluster.  It is helpful to understand that shared values are defined in one place so that when a value is adjusted in a shared configuration, it may have an impact on multiple applications.  Like per-application values, shared values are stored and retrieved via ConfigMaps or Secrets.  Unlike values that are defined within each application's values, these shared configurations are stored in a configuration that's managed by a separate chart (for the migrationAssistant chart, the shared configurations are in the "shared-configs" sub-chart).

Both shared and per-application configuration values have **default** configuration that is set at the **time of deployment** or a **runtime** configuration that can be set through the console (eventually) at any time.  When doing a deployment, the values passed to the installation/update will specify configuration values and policies.  Each configuration, for shared or per-application, will include an `allowRuntimeOverride` flag to specify if an additional ConfigMap should be setup for the runtime configuration so that the default value can be overridden.  Notice that this behavior will be inherited by chart consumers like any other value.  If 'allowRuntimeOverride' is not set, it will default to what the migrationAssistant or component chart specified.  In some cases, a runtime override may be critical (transforms, speedup factor) and in others, it may not make much sense (sourceCluster settings).

The initialization container described above will pull both the default values and their overrides (when appropriate) and will construct, along with the arguments list, a unified environment variable for each configuration (leaving the default as ..._DEFAULT as well).

Notice that both the per-application configurations _and_ the shared configurations can be marked for overrides or not.  Also notice that the contents of the ConfigMaps will be structured differently for per-application configurations and shared ones.  

#### Parameters

This section provides a detailed description of the configuration objects that are within the `parameters` block for each application configuration.  

The name of each key corresponds to a flag that's passed to the application.  In most cases, these keys map directly to parameters that are sent to the applications, though charts may have specific rules to route some parameters as positional arguments (e.g. the targetUri for the replayer).  Those are expected to be implementation details that users don't need to be concerned with, especially once the main way to configure settings is through a UI or helm install (with helm READMEs describing each setting).

##### allowRuntimeOverride

Values that those programs can be hardwired for a given deployment or may be updated by somebody with access to the migration console.  `allowRuntimeOverride` set to true will create two ConfigMaps (or Secrets).  When present, the runtime one will always take precedence.  The names of the maps will be kebab-case versions of the name for the runtime one and that same name + "-default" for the deploy-time configuration.

##### source

Valid values are parameterConfig (default?) and otherConfig.  

`parameterConfig` means that the chart will create a new configuration directly, setting the value as defined and setting the container to pull directly from this value.  The configMap created will have a single required key, `value`, for the string that should be passed to the program.

`otherConfig` tells the chart to pull the environment variables from another configuration.  With `otherConfig`, this chart will _not_ automatically create any configurations for this parameter.  The other configuration may be used by multiple charts and created by yet another chart.  The builder of the chart may decide to separately create a configuration that may be shared amongst multiple parameters (e.g. several parameter settings need to be configured simultaneously).

##### value

Only valid when the source=parameterConfig.  This is the value that will be stored in the default configuration store that is created by this chart.  This value will be passed directly to the application, so the value should be in the exact format that the application, or rather, the initialization script, requires. 

##### configMapName

Only valid when the source=otherConfig.  This value specifies which ConfigMap (or Secret, eventually) to perform the lookup of `configMapKey`. 

##### configMapKey

Only valid when the source=otherConfig.  The value that we require from the other configuration may be stored alongside other data or could be in a different format than what this application requires.  ConfigMaps, as their name implies, are composed of keys to values.  Those values may be scalars (strings, numbers, etc) or any other structured data (maps, lists).  The `configMapKey` specifies which valye should be pulled from the other config map (specified by `configMapName`).  To convert that value, which could still be a compound object, into a string that's suitable for the container to consume, `yqExpression` is specified to convert a key into a string. 

##### yqExpression

The initialization container extracts the value of the `configMapKey` from the configuration stored in `configMapName` and normally inlines uses that value directly to pass to the application.  When further processing is required, such as turning a list into a comma-delimited string, choosing the first one, or consing several leaf values together, this value will trigger the data retrieved from the `configMapKey` to be passed to `yq` with this expression.  That formatted output is then passed to the application rather than the raw value.  Note that normal defaulting/override activity would already be performed before applying this step.  

#### Example

Here's an example of the base capture proxy chart's values.

```
parameters:
  destinationUri:
    source: parameterConfig
    value: "http://sourcecluster.example.com:9200"
    allowRuntimeOverride: false
  listenPort:
    source: parameterConfig
    value: 9200
    allowRuntimeOverride: false
```

With no further overrides, when deployed, this configuration will create 2 ConfigMap objects `...destination-uri-default` and `...listen-port-default` (... will be expanded to be the fully-qualified name of the component being installed).  The values "http://sourcecluster.example.com:9200" and 9200 will be included in those ConfigMaps directly and migration console users should **not** have the ability to change them.  Those same values will be passed to the invocation of the capture proxy in the form `... --destinationUri http://sourcecluster.example.com:9200 --listenPort 9200`.

**NB**: Don't confuse overriding values between charts and overriding configuration values at runtime.  The allowRuntimeOverride is _independent_ to a values that are passed when installing a chart (either being installed independently or as a dependency of another chart).  In fact, the migrationAssistant chart DOES override the `destinationUri`.  As it does so, it can _also_ decide to change the `allowRuntimeOverride` setting.  Users doing the installation could also decide if they want users to be able to override configurations, further overriding what the migration assistant chart had specified.

The migrationAssistant overrides the example configurations for the capture proxy chart (default/sample values are always defined in "values.yaml" alongside Chart.yaml).  While it doesn't add values that are more legitimate it does wire the parameters up to come from a shared configuration.  Here's the example.

```
shared-configs:
  globalParameters:
    sourceCluster:
      allowRuntimeOverride: true
      object:
        endpoint: "http://sourcecluster.example.com:9200"
        allowInsecure: true


capture-proxy:
  parameters:
    destinationUri:
      source: otherConfig
      configMapName: "source-cluster"
      configMapKey: "endpoint"
    insecureDestination:
      source: otherConfig
      configMapName: "source-cluster"
      configMapKey: "allowInsecure"
```

When the migration assistant is deployed with the above configuration, it will include the capture proxy.  Recall, that without the new configurations default ConfigMaps will be created to house values that were specified in the capture proxy chart.  With the config above, ConfigMaps for 'source-cluster-default' and 'source-cluster' are created.  'source-cluster-default' will have be set to the two-key map under the `object` key.  The initialization container will pull the `endpoint` and `allowInsecure` values from the map and route them to the `--destinationUri` and `--insecureDestination` command line parameters.

### Specific services
Guide for deploying an individual Migration service helm chart

A particular service could then be deployed with a command similar to the below.
```shell
helm install console charts/components/migrationConsole
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
