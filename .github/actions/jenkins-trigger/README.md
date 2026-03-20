# Jenkins Job Trigger and Monitor

Local GitHub Action that triggers a Jenkins job via the [Generic Webhook Trigger](https://plugins.jenkins.io/generic-webhook-trigger/) plugin and polls for completion.

Forked from [`jugal-chauhan/jenkins-trigger@1.0.6`](https://github.com/jugal-chauhan/jenkins-trigger/tree/1.0.6) with the following changes:

- **Cancellation support** — when the GitHub workflow is cancelled (SIGTERM/SIGINT), the action aborts the running Jenkins build via the Jenkins stop API
- **Composite action** — runs directly on the runner (no Docker build overhead)

## Usage

```yaml
steps:
  - uses: actions/checkout@v4
  - uses: ./.github/actions/jenkins-trigger
    with:
      jenkins_url: "https://jenkins.example.com"
      job_name: "my-job"
      api_token: "${{ secrets.JENKINS_TOKEN }}"
      job_params: "BRANCH=main,ENV=dev"
      job_timeout_minutes: "120"
```

## Running unit tests

```bash
cd webhook-trigger
pip install requests pytest
python -m pytest tests/ -v
```

## Testing in GitHub Actions

The `jenkins_tests.yml` workflow uses `pull_request_target`, which reads the workflow
YAML from the base branch (`main`) — not from the PR. This means changes to this action
or the workflow cannot be tested via a normal PR.

To test changes end-to-end against real Jenkins:

1. Create a branch matching `jenkins-trigger-test-*` on `opensearch-project/opensearch-migrations`
   (the workflow triggers on push to branches matching this pattern)
2. Push your changes to that branch — the Jenkins workflow will run using the branch's
   version of the action and workflow file
3. To test cancellation, cancel the workflow run mid-execution and verify in Jenkins
   that the build was aborted
4. Delete the branch when done

```bash
# Example: test from your feature branch
git push upstream my-branch:jenkins-trigger-test-my-change
# ... verify in GitHub Actions ...
git push upstream --delete jenkins-trigger-test-my-change
```
