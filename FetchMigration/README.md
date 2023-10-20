# "Fetch" Data Migration / Backfill

Fetch Migration provides an easy-to-use tool that simplifies the process of moving indices and their data from a 
"source" cluster (either Elasticsearch or OpenSearch) to a "target" OpenSearch cluster. It automates the process of 
comparing indices between the two clusters and only creates index metadata (settings and mappings) that do not already 
exist on the target cluster. Internally, the tool uses [Data Prepper](https://github.com/opensearch-project/data-prepper) 
to migrate data for these created indices.

The Fetch Migration tool is implemented in Python.
A Docker image can be built using the included [Dockerfile](./Dockerfile).

## Components

The tool consists of 3 components:
* A "metadata migration" module that handles metadata comparison between the source and target clusters. 
This can output a human-readable report as well as a Data Prepper pipeline `yaml` file.
* A "migration monitor" module that monitors the progress of the migration and shuts down the Data Prepper pipeline 
once the target document count has been reached
* An "orchestrator" module that sequences these steps as a workflow and manages the kick-off of the Data Prepper 
process between them.

The orchestrator module is the Docker entrypoint for the tool, though each component can be executed separately 
via Python. Help text for each module can be printed by supplying the `-h / --help` flag.

## Current Limitations

* Fetch Migration runs as a single instance and does not support vertical scaling or data slicing
* The tool does not support customizing the list of indices included for migration
* Metadata migration only supports basic auth
* The migration does not filter out `red` indices
* In the event that the migration fails or the process dies, the created indices on the target cluster are not rolled back

## Execution

### Python

* [Clone](https://docs.github.com/en/repositories/creating-and-managing-repositories/cloning-a-repository) this GitHub repo
* Install [Python](https://www.python.org/)
* Ensure that [pip](https://pip.pypa.io/en/stable/installation/#) is installed
* (Optional) Set up and activate a [virtual environment](https://packaging.python.org/en/latest/tutorials/installing-packages/#creating-and-using-virtual-environments)

Navigate to the cloned GitHub repo. Then, install the required Python dependencies by running:

```shell
python -m pip install -r python/requirements.txt
```

The Fetch Migration workflow can then be kicked off via the orchestrator module:

```shell
python python/fetch_orchestrator.py --help
```

### Docker

First build the Docker image from the `Dockerfile`:

```shell
docker build -t fetch-migration .
```

Then run the `fetch-migration` image.
Replace `<pipeline_yaml_path>` in the command below with the path to your Data Prepper pipeline `yaml` file:

```shell
docker run -p 4900:4900 -v <pipeline_yaml_path>:/code/input.yaml fetch-migration
```

### AWS deployment

Refer to [AWS Deployment](../deployment/README.md) to deploy this solution to AWS.

## Development

The source code for the tool is located under the `python/` directory, with unit test in the `tests/` subdirectory. 
Please refer to the [Setup](#setup) section to ensure that the necessary dependencies are installed prior to development.

Additionally, you'll also need to install development dependencies by running:

```shell
python -m pip install -r python/dev-requirements.txt
```

### Unit Tests

Unit tests can be run from the `python/` directory using:

```shell
python -m coverage run -m unittest
```

### Coverage

_Code coverage_ metrics can be generated after a unit-test run. A report can either be printed on the command line:

```shell
python -m coverage report --omit "*/tests/*"
```

or generated as HTML:

```shell
python -m coverage report --omit "*/tests/*"
python -m coverage html --omit "*/tests/*"
```

Note that the `--omit` parameter must be specified to avoid tracking code coverage on unit test code itself.