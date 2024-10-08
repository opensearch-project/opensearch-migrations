# Cleanup Deployment

Utility tool for removing deployed resources

### Running Tool

If pipenv is not installed, install with below
```shell
python3 -m pip install --upgrade pipenv
```

Install dependencies
```shell
pipenv install --deploy
```

Run clean deployment 
```shell
pipenv run python3 cleanup_deployment.py --stage rfs-integ1
```
