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
- Periodic schedule is enabled by default for non-`pr-*` job names and disabled for `pr-*` job names

### Supported Cover Files

| Cover File | Vars Function | Override Parameter |
|------------|---------------|-------------------|
| eksCreateVPCSolutionsCFNTestCover.groovy | eksSolutionsCFNTest | JOB_NAME_OVERRIDE |
| eksBYOSIntegTestCover.groovy | eksBYOSIntegPipeline | JOB_NAME_OVERRIDE |
| eksImportVPCSolutionsCFNTestCover.groovy | eksSolutionsCFNTest | JOB_NAME_OVERRIDE |
| eksIntegTestCover.groovy | eksIntegPipeline | JOB_NAME_OVERRIDE |
| elasticsearch5xK8sLocalTestCover.groovy | elasticsearch5xK8sLocalTest | JOB_NAME_OVERRIDE |
| elasticsearch8xK8sLocalTestCover.groovy | elasticsearch8xK8sLocalTest | JOB_NAME_OVERRIDE |
| fullES68SourceE2ETestCover.groovy | fullES68SourceE2ETest | JOB_NAME_OVERRIDE |
| k8sLocalIntegTestCover.groovy | k8sLocalDeployment | JOB_NAME_OVERRIDE |
| k8sMatrixTestCover.groovy | k8sMatrixTest | JOB_NAME_OVERRIDE + CHILD_JOB_NAME_OVERRIDE |
| rfsDefaultE2ETestCover.groovy | rfsDefaultE2ETest | JOB_NAME_OVERRIDE |
| solutionsCFNTestCover.groovy | solutionsCFNTest | JOB_NAME_OVERRIDE |
| trafficReplayDefaultE2ETestCover.groovy | trafficReplayDefaultE2ETest | JOB_NAME_OVERRIDE |

### GitHub Actions Integration

The `.github/workflows/jenkins_tests.yml` workflow handles both PR and post-merge triggers using a single file. It uses `github.event_name == 'push'` to select `main-*` prefixed job names for pushes to `main`, and `pr-*` prefixed job names for pull requests. Note that `main-*` jobs may also run on a periodic cadence configured directly in Jenkins, independent of GHA webhooks.

Jobs triggered:

- `full-es68source-e2e-test` (PR and main)
- `elasticsearch-5x-k8s-local-test` (PR and main)
- `eks-integ-test` (PR with `run-eks-tests` label, and main)

This ensures PR-triggered jobs don't conflict with post-merge jobs using the same pipeline code.

### Jenkins Folder Structure

Jenkins jobs are organized into two folders:

- `main/` - pipelines that run on a periodic cadence, via GenericTrigger on push to `main`, or both (e.g., `main/main-k8s-local-integ-test`)
- `pr-checks/` - pipelines that run only via GenericTrigger from PRs. No periodic cadence, no action on PR push (e.g., `pr-checks/pr-k8s-local-integ-test`)

