# Migration Console
The accessible control hub for all things migrations


## Running Python tests


### Installing Requirements

To isolate the Python environment for the project from your local machine, create virtual environment like so:
```
python3 -m venv .venv
source .venv/bin/activate
```

You can exit the Python virtual environment and remove its resources like so:
```
deactivate
rm -rf .venv
```

Install developer requirements for osiMigration library like so:
```
pip install -r lib/osiMigrationLib/dev-requirements.txt
```


### Unit Tests

Unit tests can be run from this current `migrationConsole/` directory using:

```shell
python -m unittest
```

### Coverage

_Code coverage_ metrics can be generated after a unit-test run. A report can either be printed on the command line:

```shell
python -m coverage report --omit "*/test/*"
```

or generated as HTML:

```shell
python -m coverage html --omit "*/test/*"
```

Note that the `--omit` parameter must be specified to avoid tracking code coverage on unit test code itself.