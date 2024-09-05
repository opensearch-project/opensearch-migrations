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
pipenv run python3 default_webhook_trigger.py --pipeline_token=<PIPELINE_TOKEN> --payload='{"pipeline_name": "test-select-pipeline"}'
```
