# Console API

### Running Tests

If pipenv is not installed, install with below
```shell
python3 -m pip install --upgrade pipenv
```

Install dependencies
```shell
pipenv install --deploy --dev
```

Run test cases
```shell
pipenv run coverage run --source='.' manage.py test console_api
```

Generate coverage report
```shell
pipenv run coverage report
```
