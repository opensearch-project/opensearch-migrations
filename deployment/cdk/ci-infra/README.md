# CI infra: GitHub Actions access to the AWS test account

This CDK app (stack `osm-ci-infra`) creates what the AWS integration tests in `.github/workflows/aws-integ-tests.yml`
need in the AWS test account: the IAM roles that GitHub Actions jobs assume through the GitHub OIDC provider, a
permissions boundary on those roles, and the snapshot library bucket. It follows
[opensearch-ci](https://github.com/opensearch-project/opensearch-ci): the roles as in `lib/secrets/iam-roles.ts` and
`lib/secrets/gha-federate-access.ts`, and the credentials step and account-ID masking as in
`.github/workflows/deploy-stacks.yml`.

No workflow can deploy this stack: an admin deploys it by hand (see [Changing this stack](#changing-this-stack)), so
credentials that could change the CI roles never exist in GitHub.

## What's in the stack

| Role | GitHub environment | Assumed from | Used for |
|---|---|---|---|
| `osm-ci-main` | `migrations-ci-main` (main only) | `.github/workflows/aws-ci-job.yml` | push, schedule and dispatch runs on main |
| `osm-ci-pr` | `migrations-ci-pr` | `.github/workflows/aws-ci-job.yml` | PR runs a maintainer approved by adding a `run-*` label |

- **Trust.** Each role trusts only `aud` = `sts.amazonaws.com`, `sub` = `repo:<owner>/<repo>:environment:<name>`, and
  `job_workflow_ref` = `aws-ci-job.yml` on `refs/heads/main`. Roles are bound to environments rather than branches: a
  `pull_request_target` token has `sub` = `ref:refs/heads/main`, the same as a push to main.
- **Permissions.** Both test roles have `AdministratorAccess` (context `testRoleManagedPolicyArns`, the same as the
  Jenkins deployment role) and 6h sessions, under the `osm-ci-test-boundary` permissions boundary. The boundary denies
  IAM users and access keys, changes to the `osm-ci-*` roles and policies and the GitHub provider, changes to this
  stack, CloudTrail changes, account/organization settings, turning off the account's S3 Block Public Access, and
  sharing snapshots or images with other accounts. It also keeps the test roles away from the default CDK
  bootstrap (`hnb659fds`), which deploys this stack and whose execution role has admin rights with no boundary: they
  can't assume, pass (for example `cloudformation create-stack --role-arn`) or change its roles, nor change the
  `CDKToolkit` stack, its assets or its version parameter. The tests use their own bootstrap (`osmtest`): they can
  assume and pass its roles but not change their policies, trust or boundary, and can't delete them. Tests never run
  `cdk bootstrap`; when a CDK upgrade needs a newer bootstrap version, an admin re-runs setup step 4. Nor can they
  assume, pass or change the roles the account came with (`ACCOUNT_BASELINE_ROLES` in `lib/ci-infra-stack.ts`:
  `Admin`, `PipelinesDefaultCFNExecutionRole`, the Isengard and audit roles), none of which has the boundary. If the
  account gains such a role, add it there. Every role a test creates must carry `osm-ci-test-boundary`: the boundary
  denies creating a role without it, adding rights to a role without it, and removing it. The CDK apps get it through
  `@aws-cdk/core:permissionsBoundary`, which `aws-ci-job.yml` sets for every test (`use_test_cdk_settings` in
  `ci/aws/shared/lib.sh`).
- **Snapshot library** (`migrations-snapshots-library-<account>-us-east-1`): the snapshots the AOSS and BYOS tests
  migrate. Retained when the stack is deleted, private, TLS only; only the account's `Admin` role (and the CDK execution
  role during a deploy) can write or delete.

## How PRs reach AWS

The maintainer's label is the approval:

1. A maintainer adds the test's `run-*` label (for the smoke test, `run-aws-smoke-test`).
   `.github/workflows/aws-ci-pr-gate.yml` checks that whoever added it is in `.github/CODEOWNERS` on the base branch
   (which the PR can't change) and has write access or more, because users with only Triage can add labels too.
2. The tests run on the PR's code at that moment, in the `migrations-ci-pr` environment, so the job's OIDC token has the
   `sub` that `osm-ci-pr` trusts.
3. A later push runs nothing: the workflows trigger only on `labeled`. To test new commits, a maintainer reviews them and
   adds the label again (remove it first if it's still on). Nothing reads PR data before the gate allows the run.

Review the PR's changes before adding a label, especially `.github/`, `ci/`, build files, lock files and Dockerfiles:
the label runs that code in AWS with the PR role.

## Setup

One-time steps for a new test account, in order. Steps 1–5 need Isengard Admin on the account, for example:

```bash
ada credentials update --account=<account-id> --provider=isengard --role=Admin --profile=<profile> --once
export AWS_PROFILE=<profile> AWS_REGION=us-east-1
```

1. **Create the GitHub OIDC provider** outside any stack, so deleting the stack never deletes it:

   ```bash
   aws iam create-open-id-connect-provider \
     --url https://token.actions.githubusercontent.com \
     --client-id-list sts.amazonaws.com
   ```

   `token.actions.githubusercontent.com` is on the allowlist of the Cloud Security `IAMOpenIdProvider` detection
   (`UDDDetectionTemplateRegistry`). Check that the provider still exists two hours later.
2. **Bootstrap CDK** for this stack (default qualifier `hnb659fds`): `npx cdk bootstrap aws://<account-id>/us-east-1`.
3. **Deploy this stack**, as in [Changing this stack](#changing-this-stack). The outputs are the two test role ARNs
   and the snapshot library bucket.
4. **Bootstrap CDK for the tests.** The CDK-based tests (full-es68, rfs-default, the cluster app) deploy with their own
   qualifier, whose execution role carries the test boundary; the boundary must exist first (step 3):

   ```bash
   npx cdk bootstrap aws://<account-id>/us-east-1 --qualifier osmtest --toolkit-stack-name CDKToolkitTests \
     --custom-permissions-boundary osm-ci-test-boundary
   ```
5. **Raise the quotas** the tests use most (Service Quotas, us-east-1). Each EKS test deploys its own VPC with 2–3 NAT
   gateways. With EC2-VPC Elastic IPs = 150, VPCs per Region = 60, Internet gateways per Region = 60 and NAT gateways
   per Availability Zone = 60, about 55 tests fit at once.
6. **GitHub.**
   - Repo secrets from the stack outputs (repo admin): `MIGRATIONS_CI_MAIN_ROLE`, `MIGRATIONS_CI_PR_ROLE`. They're
     repository secrets, not environment secrets: the calling workflows pass them to `aws-ci-job.yml`.
   - Labels (maintainers can add them): `run-aws-smoke-test`, `run-aws-e2e-tests`; the other `run-*` labels already
     exist.
   - Environments: `migrations-ci-main` and `migrations-ci-pr` need no protection rules, so GitHub creates them on the
     first run that uses them. The role trust already pins `aws-ci-job.yml` on `main`; a repo admin can add a `main`
     deployment-branch rule to `migrations-ci-main` as extra safety. Don't add required reviewers to
     `migrations-ci-pr`: the maintainer's label is the approval.
7. **Run the smoke test** (`.github/workflows/aws-ci-smoke-test.yml`): dispatch it on main, and label a PR with
   `run-aws-smoke-test` (as a maintainer). Each run prints the job's OIDC claims and the role it assumed (account IDs masked). If a run
   can't assume its role, compare the printed `sub` and `job_workflow_ref` with the role's trust policy.

## Changing this stack

Changes are rare. Someone with Isengard Admin on the test account deploys them by hand:

1. The PR that changes `deployment/cdk/ci-infra/` includes the `cdk diff` output, with account IDs masked
   (`| sed -E 's/[0-9]{12}/[MASKED]/g'`).
2. After it merges, deploy from an up-to-date `main`:

   ```bash
   cd deployment/cdk/ci-infra
   npm ci && npm test
   npx cdk diff osm-ci-infra     # same changes as in the PR?
   npx cdk deploy osm-ci-infra
   ```

   Read the diff before deploying. Changes to a role's trust conditions or to `osm-ci-test-boundary` change who can
   reach AWS and what they can do there.
3. Note on the PR that it's deployed, and run the smoke test.

Deploy without `-c` overrides: `cdk.json` holds the upstream values. The test roles can't deploy this stack (the
boundary denies them the `cdk-hnb659fds-*` roles and this stack).

## Testing on a fork

The stack trusts one repository at a time. To test workflow changes on a fork, deploy it with the fork's values, then
deploy again without overrides when you're done; until then, upstream runs can't assume the roles.

Forks created after 2026-07-15 use the immutable `sub` format. Read the prefix with
`gh api repos/<owner>/<repo>/actions/oidc/customization/sub`, then deploy with context overrides:

```bash
npx cdk deploy osm-ci-infra \
  -c githubRepository=<owner>/opensearch-migrations \
  -c githubSubjectPrefix='repo:<owner>@<owner-id>/opensearch-migrations@<repo-id>' \
  -c githubWorkflowRef=refs/heads/<test-branch>
```

On a fork, `workflow_dispatch` and `pull_request_target` read workflows from the fork's default branch, so make
`<test-branch>` the default branch while testing. The fork also needs the two environments and the two role secrets.

## Development

```bash
npm ci
npm test        # eslint + jest
npx cdk synth   # needs CDK_DEFAULT_ACCOUNT or AWS credentials
```
