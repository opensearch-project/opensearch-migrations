# Migration Assistant Helm Charts

## Migration Assistant environment

The [Migration Assistant](aggregates/migrationAssistantWithArgo) helm
chart consists of:
* The Migration Console stateful set (a shell for users to run workflow commands to perform migration tasks)
* Argo Workflows (used by the workflow commands to dynamically provision and manage resources that perform a migration)
* Strimzi (to create Kafka clusters)
* Observability services - Prometheus, Jaeger, and Grafana (Jaeger and Grafana are optional and only enabled for local
  deployments)

During startup, the migration console pod runs a `workflow-schema-generator`
init container after the Strimzi operator is available. That init container
reads the live Strimzi OpenAPI schema from the cluster, builds the unified
migration workflow schema, and writes the resulting
`workflowMigration.schema.json` and `sample.yaml` into a shared in-pod volume.
The main migration console container then uses those generated files for
workflow-config validation.

Run this to install this chart to a new K8s namespace named 'ma'

```bash
helm install --create-namespace -n ma ma aggregates/migrationAssistantWithArgo
```

To see what has been installed, run
```bash
kubectl get all -n ma
```

There's also a utility chart to install source and target test clusters that can be deployed with
```shell
helm install tc -n ma aggregates/testClusters
```

Notice that all resources are deployed within the same namespace as that makes
the authorization models easier to manage.

## Configuration

Helm charts are configured by substituting values into yaml templates to produce
K8s manifests (such as pods, configmaps, etc.).  Charts include default values
in the chart's values.yaml file.  The migrationAssistantWithArgo chart provides
an alternate set of values
([valuesEks.yaml](aggregates/migrationAssistantWithArgo/valuesEks.yaml))
that can be specified with files that can be specified with the -f flag to
change how resources will be rendered.  Check the helm [documentation](https://helm.sh/docs/intro/using_helm#customizing-the-chart-before-installing) for more
details about configuring charts.

## Uninstalling
To show all helm deployments
```shell
helm list
```

To uninstall a particular helm deployment
```shell
helm uninstall <deployment_name>
```

## What Helm Manages (and what it doesn't manage)

The Migration Assistant is a solution that utilizes a number of different tools
at different points in a migration - taking snapshots, migrating metadata,
documents, and orchestrating live capture replays - all of which are done by
various containers that are orchestrated together with the help of Argo
Workflows.  Migrations are performed by running argo workflows via the migration
console.  Argo workflows manages deploying the resources for each of the
phases of a migration.  Helm manages bootstrapping the Argo Workflows
environment into the K8s cluster and configuring the other resources that are
used by those workflows (configmaps, RBAC policies, and the migration console).

Helm installations are unaware of the source and target environments
(unlike previous IAC in the MA ECS solution).  All of those are workflow
configurations that are used dynamically every time that a workflow is executed.
Configuration options for Helm include features like metrics & log management,
test/diagnostic features (localstack, jaeger, etc.), and low-level
configurations for Argo Workflows and other critical resources.

Helm allows users to upgrade their charts - which means updating deployed
resources - by supplying new values to override the old ones.  Helm provides
a number of tools (optional flags) to understand how values affect the final
resources.  However, this solution attempts to minimize what needs to be
configured a priori, making volatile configurations to be managed dynamically
by argo rather than by Helm.

Lastly, to minimize the user-involvement in Helm even more, the
migrationAssistantWithArgo chart itself has no direct dependencies, which can
be burdensome to update and manage.  Instead, the top-level ("umbrella") chart
installs dependent chart itself spins up a job to separately install each of
the configured helm charts, followed by configuring its own resources
(workflow templates, configmaps, stateful sets, etc).
