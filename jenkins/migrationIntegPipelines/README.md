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
4. **GitHub Actions** sends webhooks with `pr-*` prefixed job names for PR-triggered tests

### Example Flow

**PR-triggered job:**
```
GitHub PR → GHA sends job_name: "pr-eks-integ-test" 
→ Jenkins job "pr-eks-integ-test" with JOB_NAME_OVERRIDE="pr-eks-integ-test"
→ eksIntegTestCover.groovy passes to eksIntegPipeline(jobName: "pr-eks-integ-test")
→ GenericTrigger matches and runs
```

**Periodic job:**
```
Cron trigger → Jenkins job "main-eks-integ-test" with JOB_NAME_OVERRIDE="main-eks-integ-test"
→ eksIntegTestCover.groovy passes to eksIntegPipeline(jobName: "main-eks-integ-test")
→ No webhook match, runs on schedule
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
- Use `CHILD_JOB_NAME_OVERRIDE` parameter
- Pass as `childJobName` to the vars function

### Supported Cover Files

| Cover File | Vars Function | Override Parameter |
|------------|---------------|-------------------|
| eksBYOSIntegTestCover.groovy | eksBYOSIntegPipeline | JOB_NAME_OVERRIDE |
| eksIntegTestCover.groovy | eksIntegPipeline | JOB_NAME_OVERRIDE |
| elasticsearch5xK8sLocalTestCover.groovy | elasticsearch5xK8sLocalTest | JOB_NAME_OVERRIDE |
| elasticsearch8xK8sLocalTestCover.groovy | elasticsearch8xK8sLocalTest | JOB_NAME_OVERRIDE |
| fullES68SourceE2ETestCover.groovy | fullES68SourceE2ETest | JOB_NAME_OVERRIDE |
| k8sLocalIntegTestCover.groovy | k8sLocalDeployment | JOB_NAME_OVERRIDE |
| k8sMatrixTestCover.groovy | k8sMatrixTest | CHILD_JOB_NAME_OVERRIDE |
| rfsDefaultE2ETestCover.groovy | rfsDefaultE2ETest | JOB_NAME_OVERRIDE |
| trafficReplayDefaultE2ETestCover.groovy | trafficReplayDefaultE2ETest | JOB_NAME_OVERRIDE |

### GitHub Actions Integration

The `.github/workflows/jenkins_tests.yml` workflow sends PR-triggered webhooks with `pr-*` prefixed job names:

- `pr-full-es68source-e2e-test`
- `pr-elasticsearch-5x-k8s-local-test`
- `pr-eks-integ-test`

This ensures PR-triggered jobs don't conflict with periodic or manually-triggered jobs using the same pipeline code.
