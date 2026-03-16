# Jenkins Job Trigger and Monitor

A Github Action to trigger Jenkins jobs and monitor for completion

## Usage

```yml
name: "Trigger Jenkins Job"
on:
  push:
  pull_request_target:
    types: [opened, synchronize, reopened]

jobs:
  trigger-jenkins:
    runs-on: ubuntu-latest
    steps:
      - name: Jenkins Job Trigger and Monitor
        uses: lewijacn/jenkins-trigger@1.0.5
        with:
          jenkins_url: 'https://test-jenkins-url'
          job_name: 'test-job'
          api_token: "${{ secrets.WEBHOOK_TOKEN }}"
          job_params: "STAGE=dev,BRANCH=main"
          job_timeout_minutes: '10'
```

## Sample Action Output
```
Executing jenkins webhook trigger for url: https://test.ci.org and job: main-integ-test
2024-09-25 01:57:14,348 [INFO] Using following payload for workflow trigger: {'GIT_REPO_URL': 'https://github.com/test/ci-repo.git', 'GIT_BRANCH': 'main', 'job_name': 'main-integ-test'}
2024-09-25 01:57:14,864 [INFO] Webhook triggered successfully: 200
2024-09-25 01:57:14,864 [INFO] Received response body: {'jobs': {'main-integ-test': {'regexpFilterExpression': '^main-integ-test$', 'triggered': True, 'resolvedVariables': {'GIT_BRANCH': 'main', 'GIT_REPO_URL': 'https://github.com/test/ci-repo.git', 'job_name': 'main-integ-test'}, 'regexpFilterText': 'main-integ-test', 'id': 494, 'url': 'queue/item/494/'}}, 'message': 'Triggered jobs.'}
2024-09-25 01:57:14,864 [INFO] The following pipelines were started: ['main-integ-test']
2024-09-25 01:57:14,865 [INFO] Detected jenkins queue_url: queue/item/494/
2024-09-25 01:57:14,865 [INFO] Waiting for Jenkins to start workflow
2024-09-25 01:57:29,865 [INFO] Using queue information to find build number in Jenkins if available
2024-09-25 01:57:29,933 [INFO] Jenkins workflow_url: https://test.ci.org/job/main-integ-test/233/
2024-09-25 01:57:29,934 [INFO] Waiting for Jenkins to complete the run
2024-09-25 01:57:29,934 [INFO] Still running, wait for another 30 seconds before checking again, max timeout 3600
2024-09-25 01:57:29,934 [INFO] Total time waiting: 30
2024-09-25 01:58:00,169 [INFO] Workflow currently in progress: True
2024-09-25 01:58:00,169 [INFO] Still running, wait for another 30 seconds before checking again, max timeout 3600
2024-09-25 01:58:00,169 [INFO] Total time waiting: 60
...
2024-09-25 02:15:04,985 [INFO] Workflow currently in progress: True
2024-09-25 02:15:04,985 [INFO] Still running, wait for another 30 seconds before checking again, max timeout 3600
2024-09-25 02:15:04,985 [INFO] Total time waiting: 1080
2024-09-25 02:15:35,052 [INFO] Workflow currently in progress: False
2024-09-25 02:15:35,052 [INFO] Run completed, checking results now...
2024-09-25 02:15:35,292 [INFO] Action Result: SUCCESS. Please check jenkins url for logs: https://test.ci.org/job/main-integ-test/233/
```

## Inputs
| Name                | Required | Description                                                                                                                                                                                                                   |
|---------------------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| jenkins_url         | `true`   | Jenkins URL including http/https protocol                                                                                                                                                                                     |
| job_name            | `true`   | The job name to trigger in Jenkins                                                                                                                                                                                            |
| api_token           | `true`   | The token for authenticating with the Jenkins generic webhook                                                                                                                                                                 |
| job_params          | false    | Job parameters, separated by a comma, to provide to a Jenkins workflow. Job name will automatically be added as a parameter.<br/> e.g. `"GIT_REPO_URL=https://github.com/lewijacn/opensearch-migrations.git,GIT_BRANCH=main"` |
| job_timeout_minutes | false    | Max time (minutes) this Github Action will wait for completion. Default is 60 minutes                                                                                                                                         |

# Changelog

## v1.0
- Initial Release
