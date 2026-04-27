# Jenkins Migration Integration Pipelines

## Overview

This directory contains Jenkins pipeline "cover" files that serve as entry points for migration integration tests. These files are thin wrappers that load shared library functions from the `vars/` directory.

## Job Name Override Pattern

### Purpose

The `JOB_NAME_OVERRIDE` parameter enables multiple Jenkins jobs to share the same pipeline code while maintaining separate webhook routing. This allows organizing jobs into different views (e.g., "PR-Triggered Tests" vs "Periodic Tests") without code duplication.

### How It Works

1. **Cover files** accept a `JOB_NAME_OVERRIDE` parameter and pass it to the vars function
2. **Vars files** use `config.jobName ?: 'default-name'` to accept the override or use the default
3. **GenericTrigger** in the pipeline uses the jobName in `regexpFilterExpression` to filter webhooks
4. **GitHub Actions** sends webhooks with `pr-*` or `main-*` prefixed job names depending on the trigger event

### Example Flow

**PR-triggered job:**
```
GitHub PR → GHA sends job_name: "pr-eks-integ-test" 
→ Jenkins job "pr-eks-integ-test" with JOB_NAME_OVERRIDE="pr-eks-integ-test"
→ eksIntegTestCover.groovy passes to eksIntegPipeline(jobName: "pr-eks-integ-test")
→ GenericTrigger matches and runs
```

**Main-triggered job (push to main):**
```
Push to main → GHA sends job_name: "main-eks-integ-test"
→ Jenkins job "main-eks-integ-test" with JOB_NAME_OVERRIDE="main-eks-integ-test"
→ eksIntegTestCover.groovy passes to eksIntegPipeline(jobName: "main-eks-integ-test")
→ GenericTrigger matches and runs
```

**Main-triggered job (periodic cadence):**
```
Cron schedule fires → Jenkins runs "main-eks-integ-test" directly
→ No webhook involved, job runs on its configured schedule
```

### Creating New Jobs

To create a new job using this pattern:

1. **Create the Jenkins job** pointing to the appropriate cover file
2. **Add a string parameter** named `JOB_NAME_OVERRIDE` (or `CHILD_JOB_NAME_OVERRIDE` for matrix tests)
3. **Set the default value** to your desired job name (e.g., `pr-my-test` or `main-my-test`)
4. **Configure webhook routing** (if needed) by ensuring GitHub Actions sends the matching job name

### Cover Files

All cover files follow this pattern:

```groovy
def gitBranch = params.GIT_BRANCH ?: 'main'
def gitUrl = params.GIT_REPO_URL ?: 'https://github.com/opensearch-project/opensearch-migrations.git'

library identifier: "migrations-lib@${gitBranch}", retriever: modernSCM(
        [$class: 'GitSCMSource',
         remote: "${gitUrl}"])

// Allow job name override for webhook routing (e.g., pr-* vs main-*)
def jobNameOverride = params.JOB_NAME_OVERRIDE ?: ''
functionName(jobName: jobNameOverride ?: null)
```

**Special case for matrix tests:**
- Use `JOB_NAME_OVERRIDE` for matrix parent webhook routing
- Use `CHILD_JOB_NAME_OVERRIDE` parameter
- Pass as `childJobName` to the vars function
- Default child job path follows parent job name:
  - `main-*` parent defaults to `main/main-k8s-local-integ-test`
  - `pr-*` parent defaults to `pr-checks/pr-k8s-local-integ-test`
- Periodic schedules are defined in `vars/periodicCron.groovy` (dispatch table keyed on job name). `pr-*` jobs never have a cadence; `main-*` and `release-*` jobs pick their cadence from the table

### Supported Cover Files

| Cover File | Vars Function | Override Parameter |
|------------|---------------|-------------------|
| cleanupDeploymentCover.groovy | cleanupDeployment | JOB_NAME_OVERRIDE |
| eksAOSSSearchIntegTestCover.groovy | eksAOSSIntegPipeline | JOB_NAME_OVERRIDE |
| eksAOSSTimeSeriesIntegTestCover.groovy | eksAOSSIntegPipeline | JOB_NAME_OVERRIDE |
| eksAOSSVectorIntegTestCover.groovy | eksAOSSIntegPipeline | JOB_NAME_OVERRIDE |
| eksBYOSIntegTestCover.groovy | eksBYOSIntegPipeline | JOB_NAME_OVERRIDE |
| eksCdcAossCdcOnlyIntegTestCover.groovy | eksCdcAossIntegPipeline | JOB_NAME_OVERRIDE |
| eksCdcAossFullE2eIntegTestCover.groovy | eksCdcAossIntegPipeline | JOB_NAME_OVERRIDE |
| eksCdcBulkGenerateDataIntegTestCover.groovy | eksCdcIntegPipeline | JOB_NAME_OVERRIDE |
| eksCdcIntegTestCover.groovy | eksCdcIntegPipeline | JOB_NAME_OVERRIDE |
| eksCdcMixedOpsIntegTestCover.groovy | eksCdcIntegPipeline | JOB_NAME_OVERRIDE |
| eksCdcSimpleBulkE2eIntegTestCover.groovy | eksCdcIntegPipeline | JOB_NAME_OVERRIDE |
| eksCreateVPCSolutionsCFNTestCover.groovy | eksSolutionsCFNTest | JOB_NAME_OVERRIDE |
| eksImportVPCSolutionsCFNTestCover.groovy | eksSolutionsCFNTest | JOB_NAME_OVERRIDE |
| eksIntegTestCover.groovy | eksIntegPipeline | JOB_NAME_OVERRIDE |
| elasticsearch5xK8sLocalTestCover.groovy | elasticsearch5xK8sLocalTest | JOB_NAME_OVERRIDE |
| elasticsearch8xK8sLocalTestCover.groovy | elasticsearch8xK8sLocalTest | JOB_NAME_OVERRIDE |
| fullES68SourceE2ETestCover.groovy | fullES68SourceE2ETest | JOB_NAME_OVERRIDE |
| k8sLocalIntegTestCover.groovy | k8sLocalDeployment | JOB_NAME_OVERRIDE |
| k8sMatrixTestCover.groovy | k8sMatrixTest | JOB_NAME_OVERRIDE + CHILD_JOB_NAME_OVERRIDE |
| rfsDefaultE2ETestCover.groovy | rfsDefaultE2ETest | JOB_NAME_OVERRIDE |
| solr8xK8sLocalTestCover.groovy | solr8xK8sLocalTest | JOB_NAME_OVERRIDE |
| solutionsCFNTestCover.groovy | solutionsCFNTest | JOB_NAME_OVERRIDE |
| trafficReplayDefaultE2ETestCover.groovy | trafficReplayDefaultE2ETest | JOB_NAME_OVERRIDE |

### GitHub Actions Integration

The `.github/workflows/jenkins_tests.yml` workflow handles both PR and post-merge triggers using a single file. It uses `github.event_name == 'push'` to select `main-*` prefixed job names for pushes to `main`, and `pr-*` prefixed job names for pull requests. Periodic cadences are separate and defined in code (see [Periodic Cron Schedules](#periodic-cron-schedules)).

Jobs triggered:

- `full-es68source-e2e-test` (PR and main)
- `elasticsearch-5x-k8s-local-test` (PR and main)
- `solr-8x-k8s-local-test` (PR and main)
- `eks-integ-test` (PR with `run-eks-tests` label, and main)
- `eks-cdc-*` (PR with `run-eks-tests` label, and main)
- `eks-aoss-*` (PR with `run-aoss-tests` label, and main)
- `eks-byos-integ-test` (PR with `run-eks-byos-tests` label, and main)
- `eks-cfn-*` (PR with `run-cfn-tests` label, and main)

This ensures PR-triggered jobs don't conflict with post-merge jobs using the same pipeline code.

### Jenkins Folder Structure

Jenkins jobs are organized into three folders:

- `main/` - pipelines that run on a periodic cadence, via GenericTrigger on push to `main`, or both (e.g., `main/main-k8s-local-integ-test`)
- `pr-checks/` - pipelines that run only via GenericTrigger from PRs. No periodic cadence, no action on PR push (e.g., `pr-checks/pr-k8s-local-integ-test`)
- `release-canaries/` - pipelines that exercise release-candidate artifacts on a canary cadence, independent of PR and post-merge triggers


### Periodic Cron Schedules

Periodic cadences are defined in code via `vars/periodicCron.groovy`, a dispatch table keyed on job name. Each shared pipeline calls `cron(periodicCron(jobName))` in its `triggers {}` block.

Rules:
- `pr-*` jobs never fire on a cadence (the helper returns `''` for any name not in the table)
- `release-*` jobs all run every 6 hours (`H H(0-5)/6 * * *`), spread across all 24 hours
- `main-*` jobs have per-job cadences defined in the switch statement

Jenkins' `H` token deterministically hashes the job name into a slot within the allowed range, so jobs sharing the same cron expression still fire at unique minutes. The explicit `H(0-5)/6` hour range forces Jenkins to also spread jobs across different starting hours — without the range, all `H/6` jobs collapse into hour slot 0.

To change a cadence or add one for a new `main-*` / `release-*` job, edit the switch in `vars/periodicCron.groovy` and rebuild the shared library.
