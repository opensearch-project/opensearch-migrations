# Jenkins Webhook Trigger

### Running Locally

Instructions for running the underlying python webhook token separate from the GitHub action. There is a prerequisite here that a Jenkins server is being used in which the user knows the relevant pipeline token to use.

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
  --jenkins_url=<JENKINS_URL> \
  --pipeline_token=<JENKINS_TOKEN> \
  --job_name=<JOB_NAME> \
  --job_params="GIT_REPO_URL=https://github.com/lewijacn/opensearch-migrations.git,GIT_BRANCH=main"
```

### Running Unit Tests

Install dependencies
```shell
pipenv install --deploy --dev
```

Run tests with coverage
```shell
pipenv run coverage run -m pytest --log-cli-level=INFO
```

Generate _code coverage_ metrics after a unit-test run. A report can either be printed on the command line:

```shell
pipenv run coverage report
```

or generated as HTML:

```shell
pipenv run coverage html
```

