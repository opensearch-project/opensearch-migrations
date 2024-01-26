# PRE-REQUISITE

* Python3 and venv

## Activate your Python virtual environment

To isolate the Python environment for the project from your local machine, create virtual environment like so:
```
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

You can exit the Python virtual environment and remove its resources like so:
```
deactivate
rm -rf .venv
```

Learn more about venv [here](https://docs.python.org/3/library/venv.html).

## Create otel-collector config files

Run `consConfigSnippets.py` with the snippet components (without the .yaml extension)
that you want to include in the output (stdout).  Common dependencies (as determined 
by dependencies.yaml) will only be included in the final output once.

For example
```
python3 consConfigSnippets.py base
```