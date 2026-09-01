# Migration Assistant on AWS EKS

Deploy the Migration Assistant to a new or existing EKS cluster, then run
your migration from a console pod inside the cluster.

This page is for **operators** running a deploy. The architecture and
build details for the CLI itself live in [`cli/README.md`](cli/README.md).
For a high-level overview of all Kubernetes deployment options see the
[K8s README](../README.md). For broader Migration Assistant documentation
see the [project wiki](https://github.com/opensearch-project/opensearch-migrations/wiki).

## What you get

A single command:

```bash
./deployment/k8s/aws/cli/bin/migration-assistant
```

…brings up a CloudFormation stack (EKS cluster, ECR, IAM, VPC), mirrors
the chart's images to your private ECR, installs the Migration Assistant
helm chart, waits for everything to be Ready, and drops you into the
`migration-console-0` pod where the migration itself runs.

Re-running the same command on a partially-completed deploy resumes from
the last successful step (state lives at
`~/.opensearch-migrate/<stage>/state.env`). Re-running once everything's
deployed drops you straight back into the console.

## Prerequisites

- [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
  with credentials authorized for CloudFormation, EKS, ECR, and IAM
- `curl`, `jq`, `tar` (the CLI auto-installs `kubectl`, `helm`, and
  `crane` if missing)
- ~15 minutes for the CloudFormation stack to come up

## Quick start

```bash
./deployment/k8s/aws/cli/bin/migration-assistant
```

The CLI is interactive on first run: it prompts for stage name, image
mirroring, and Migration Assistant version, then deploys.

If you have an existing `MigrationAssistant-*` CloudFormation stack
(deployed previously, or via Terraform / external CDK) the CLI offers
to adopt it instead of asking for a stage name. CFN is skipped in that
case and the CLI goes straight to helm.

To deploy without a repo checkout, use the curl-pipe shim from any
release:

```bash
curl -fsSL https://github.com/opensearch-project/opensearch-migrations/releases/latest/download/aws-bootstrap.sh \
  | bash
```

The shim is a 30-line script that downloads the CLI tarball into
`~/.opensearch-migrate/cli/<version>/` and execs the unpacked binary.
The Rust CLI is the operator-oriented entry point. Developers who need to
build and deploy the currently checked-out source should use
`aws-bootstrap.sh` directly, as described below.

## Build and deploy the current checkout

The repository's `aws-bootstrap.sh` workflow builds the CloudFormation
templates, container images, Helm chart, and CloudWatch dashboards from the
current checkout, pushes the images to the deployment's private ECR registry,
and installs those locally built artifacts on EKS.

In addition to the general prerequisites above, this workflow requires:

- Java 21
- Docker with the Buildx plugin
- `kubectl` and Helm
- Network access from the EKS build nodes to the public base-image registries

To create a development EKS deployment in a new VPC, run this from the
repository root:

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-create-vpc-cfn \
  --build \
  --base-dir "$(git rev-parse --show-toplevel)" \
  --stack-name MA-Dev \
  --stage dev \
  --region us-east-2
```

To rebuild and reinstall the current checkout on an existing Migration
Assistant CloudFormation/EKS deployment:

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --skip-cfn-deploy \
  --build \
  --base-dir "$(git rev-parse --show-toplevel)" \
  --stage dev \
  --region us-east-2
```

Use the same `--stage` and `--region` values as the existing deployment so the
script resolves the correct CloudFormation exports. `--build` and `--version`
are mutually exclusive. Run `./deployment/k8s/aws/aws-bootstrap.sh --help` for
the existing-VPC and other deployment options.

## Common subcommands

```bash
migration-assistant                # deploy / resume
migration-assistant console        # exec into migration-console-0 (skip the deploy flow)
migration-assistant diag           # full diagnostics dump
migration-assistant cleanup        # tear down a deploy + archive state
migration-assistant version
migration-assistant help
```

## Common flags

```bash
--stage NAME                      # stage name (default "ma"). Used as the
                                  # helm release, k8s namespace, and CFN
                                  # stack suffix.
--region REGION                   # AWS region (default us-east-1)
--version VER                     # pin Migration Assistant artifact version
--use-public-images               # skip image mirror; pull from public.ecr.aws
--non-interactive, -y             # accept all defaults; for Jenkins / CI
--verbose, -v                     # mirror logs to stderr live
--switch                          # re-prompt the deploy wizard
```

`--non-interactive` plus `--stage <name>` plus `--region <region>` is
the typical CI invocation. `--skip-console-exec` keeps the run
non-interactive (no console handoff).

Run `migration-assistant help` for the complete list of accepted flags. Some
`aws-bootstrap.sh` functionality is not implemented by this CLI, including
`--build`, `--tls-mode`, `--deploy-import-vpc-cfn`, `--vpc-id`,
`--subnet-ids`, and `--use-general-node-pool`. The CLI warns and ignores these
flags. Use `aws-bootstrap.sh` directly when you need that functionality.

## Adopting a stack you deployed elsewhere

If you provisioned the CFN stack with Terraform, CDK, or a previous
hand-run of `aws cloudformation deploy`, the CLI's discovery step finds
all `MigrationAssistant-*` stacks in the configured account/region.
On a fresh stage with no prior state, it offers:

- exactly one stack found → "Adopt `<stack>`?" (Y/n)
- multiple found → numbered picker
- none found → standard fresh-deploy prompts

You can also bypass discovery and force adoption:

```bash
migration-assistant \
  --skip-cfn-deploy \
  --stack-name MigrationAssistant-prod \
  --stage prod \
  --region us-east-2
```

## Resuming after Ctrl-C

State persists between runs. After a Ctrl-C, just re-run the same
command (no flags needed, no re-prompting through the wizard). The CLI
detects the stage's prior progress (`crane_done`, `helm_done`, etc.)
and continues from there.

If a previous helm install left a stuck release (`pending-install`,
`pending-upgrade`, `failed`), the CLI reports the exact failure cause
(failed Job name, console pod readiness) and offers rollback /
uninstall / abort. Choose abort to investigate without destroying a
working deploy.

## Diagnostics

```bash
migration-assistant diag
```

Dumps `helm status`, `kubectl get pods -o wide`, `kubectl describe`
for any unhealthy pod, sorted events, and the install-notes
ConfigMap to `~/.opensearch-migrate/<stage>/log/migrate.log`.

The same dump runs automatically on a failed `helm upgrade --install`.

## Cleanup

```bash
migration-assistant cleanup --stage prod
```

Helm-uninstalls the release, deletes the CFN stack, and archives state
to `~/.opensearch-migrate/<stage>/archive/<timestamp>/`.

## See also

- [`cli/README.md`](cli/README.md) — CLI architecture, lib layout, tests.
- [Project wiki](https://github.com/opensearch-project/opensearch-migrations/wiki)
  — full Migration Assistant documentation including deploy-from-AWS
  options that don't require a repo checkout.
- [`../README.md`](../README.md) — overall K8s deployment project.
