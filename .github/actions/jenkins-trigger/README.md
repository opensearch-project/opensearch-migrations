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
      job_timeout_minutes: "60"
```

## Running tests

```bash
cd webhook-trigger
pip install requests pytest
python -m pytest tests/ -v
```
