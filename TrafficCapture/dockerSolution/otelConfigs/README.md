# PRE-REQUISITE

* Pipenv

## Activate your Python virtual environment

To isolate the Python environment for the project from your local machine, create virtual environment like so:
```
pipenv install
```

## Create otel-collector config files

Run `consConfigSnippets.py` with the snippet components (without the .yaml extension)
that you want to include in the output (stdout).  Common dependencies (as determined 
by dependencies.yaml) will only be included in the final output once.

For example
```
pipenv run python consConfigSnippets.py base
```