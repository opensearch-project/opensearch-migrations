# Console API

### Running Locally

There is a prerequisite here that a Jenkins server is being used in which the user knows the relevant pipeline token to use.

If pipenv is not installed, install with below
```shell
python3 -m pip install --upgrade pipenv
```

Install dependencies
```shell
pipenv install --deploy
```

Run default webhook trigger
```shell
pipenv run python3 default_webhook_trigger.py \
  --jenkins_url="https://migrations.ci.opensearch.org" \
  --pipeline_token=<JENKINS_TOKEN> \
  --job_name=rfs-default-e2e-test \
  --job_param="GIT_REPO_URL=https://github.com/opensearch-project/opensearch-migrations.git" \
  --job_param="GIT_BRANCH=main"
```
