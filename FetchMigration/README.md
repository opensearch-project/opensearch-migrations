# Fetch  Migration and Backfill

The **Fetch Migration** tool simplifies the process of moving indexes and the data contained within them from a
source cluster, such as Elasticsearch or OpenSearch, to a target OpenSearch cluster. It automates the process of 
comparing indexes between the two clusters by creating index metadata, like settings and mappings that does not already exist on the target cluster using [Data Prepper](https://github.com/opensearch-project/data-prepper).

The Fetch Migration tool consists of a Python script. However, a Docker image can be built using the included [Dockerfile](./Dockerfile) included in this repository.

## Components

The tool consists of three components:

* The **Metadata migration** module handles metadata comparison between the source and target clusters. This can output a human-readable report as well as a Data Prepper `pipeline.yaml` file.
* The **Migration monitor** module monitors the progress of the migration and shuts down the Data Prepper pipeline 
once the target document count has been reached
* The **Orchestrator** module that sequences these steps as a workflow and manages the kick-off of the Data Prepper 
process between them. The orchestrator module helps route the correct components when using Docker, though each component can be executed seperately using Python.

Help text for each module can be printed using the `-h / --help` flag when exectuing the Python script.

## Current Limitations

The Fetch Migration currently has the following limitations:

* Fetch Migration runs as a single instance and does not support vertical scaling or data slicing.
* Fetch Migration does not support customizing the list of indices included for migration
* Fetch migration only supports basic authentication
* The migration does not filter out indexes whose health is `red`.
* In the event that the migration fails or the process dies, the created indexes on the target cluster are not rolled back to the previous version.

## Running Fetch Migration

Use either [Python](#python) or [Docker](#Docker) to run the Fetch Migration tool.

### Python

1. [Clone](https://docs.github.com/en/repositories/creating-and-managing-repositories/cloning-a-repository) the `opensearch-migrations` repo using `git clone git@github.com:opensearch-project/opensearch-migrations.git`.
2. Install [Python](https://www.python.org/).
3. Ensure that [pip](https://pip.pypa.io/en/stable/installation/#) by entering `pip --version`.
4. (Optional) Set up and activate a [virtual environment](https://packaging.python.org/en/latest/tutorials/installing-packages/#creating-and-using-virtual-environments).
5. Navigate to you cloned GitHub repo. Then, install the required Python dependencies by running the following command:

  ```shell
  python -m pip install -r python/requirements.txt
  ```

6. Inside the your cloned repository, run the orchestrator module using the following command:

  ```shell
  python python/fetch_orchestrator.py --help
  ```

When successful, the script returns confirmation that your indexes have been moved from the source cluster to the target cluster.

### Docker

To use the Fetch Migration tool with Docker, use the following steps:

1. From your cloned Git repository, build the `fetch-migration` Docker image using the f

   ```shell
   docker build -t fetch-migration .
   ```

2. Run the `fetch-migration` image. Replace `<pipeline_yaml_path>` in the command below with the path to your Data Prepper `pipeline.yaml` file:

  ```shell
  docker run -p 4900:4900 -v <pipeline_yaml_path>:/code/input.yaml fetch-migration
  ```

### AWS deployment

For instructions on how deploy the Fetch Migration tool using AWS, see [AWS Deployment](../deployment/README.md). 

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
