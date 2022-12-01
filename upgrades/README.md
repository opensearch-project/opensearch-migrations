# Upgrade Testing Framework

This project scripts and library code to facilitate testing of ElasticSearch and OpenSearch upgrades

## Running Unit Tests

To run the unit tests of the library code, perform the following steps

### PRE-REQUISITES

* Python3 and venv
* Currently in the same directory as this README, the setup.py, etc

### Step 1 - Activate your Python virtual environment

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

### Step 2 - Run Pytest
The unit tests are executed by invoking Pytest:

```
python -m pytest tests/
```

You can read more about running unit tests with Pytest [here](https://docs.pytest.org/en/7.2.x/how-to/usage.html).  

