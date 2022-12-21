# CSV-to-OS Tool

This is a python tool that helps to load a csv into an OpenSearch/ElasticSearch cluster.

## Functions
- Load a csv and reformat into doc-based json, suitable for upload to an OpenSearch cluster.
- Accept a cluster hostname/port and auth info and load the data using the bulk upload api.
- Identify whether the destination index already exists and create it if not, including using provided settings/data mappings
- Break large datasets up into manageable pieces for uploading.

## Usage
```
> pip install requirements.txt
> ./csv_to_os.py --help
usage: csv_to_os.py [-h] [--index-settings INDEX_SETTINGS] --index INDEX --host HOST --port PORT [--user [USER]] input_file

positional arguments:
  input_file            CSV file to be sent to ES/OS.

optional arguments:
  -h, --help            show this help message and exit
  --index-settings INDEX_SETTINGS
                        JSON file with mappings and/or settings to create an index.
  --index INDEX         Name of the index to add the data to.
  --host HOST           Host of the ES/OS cluster
  --port PORT           Port to reach the ES/OS cluster
  --user [USER]         If authentication to the cluster is necessary, a USERNAME:PASSWORD string.
```

The input file must be a CSV where the first row is column headers with the field names you'd like to use. It may be necessary to do pre-processing on the data to prepare it to be used by this script.
The other required fields are the name of the index and the host and port to reach the cluster.
When invoked with just those fields, the script will create a connection to the cluster, create the index if necessary, reformat the data (doc-oriented, add `_index` and `_id`) and use the `_bulk` upload API to load up to 10,000 rows at a time.

An index settings file is a json that can include `settings` and `mappings` sections to specify the number/types of shards and the field mappings for the documents (see `examples/nyc-taxi-settings.json`).

The username/password is necessary to authenticate to the cluster in some cases (specifically including the default setup of the managed service).


## Todos
- Refactor to keep data-reformmating and data-uploading seperate and independently invokable
- Support more robust mechanism to assign `_id` to docs (current method is suceptible to overwriting docs)
- Other necessary authentication mechanisms?
- More robust mechanism to determine data rows per request (and configurable on command line)
- Check size of csv and don't load it all into memory if it's huge (not sure what defaults Pandas has around this)
