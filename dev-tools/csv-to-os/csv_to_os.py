#!/usr/bin/env python3
import argparse
import pathlib
import json
import sys

import pandas as pd
import numpy as np
from opensearchpy import OpenSearch
import opensearchpy
from tqdm import tqdm


MAX_ROWS_PER_REQUEST = 10000  # This is a total guess! Adjust it to your data.
# On my test sample, this meant ~150 seperate requests or a bit less than 1 Mb each, so that seemed reasonable.
MAX_RETRIES = 3

parser = argparse.ArgumentParser()
parser.add_argument('input_file', type=pathlib.Path,
                    help='CSV file to be sent to ES/OS.')
parser.add_argument('--index-settings', type=pathlib.Path,
                    help='JSON file with mappings and/or settings to create an index.')
parser.add_argument('--index', required=True,
                    help='Name of the index to add the data to.')
parser.add_argument('--host', required=True, help='Host of the ES/OS cluster')
parser.add_argument('--port', required=True, type=int,
                    help='Port to reach the ES/OS cluster')
parser.add_argument('--user', nargs='?', default=None,
                    help='If authentication to the cluster is necessary, a USERNAME:PASSWORD string.')


class OpenSearchClientSetupError(Exception):
    def __init__(self, failure_reason: str):
        self.failure_reason = failure_reason
        super().__init__(
            f"Setup of OpenSearch client failed; ping check unsuccessful.  Reason: {self.failure_reason}"
        )


def _create_client(host, port, user=None, password=None):
    if user and password:
        auth = (user, password)
    else:
        auth = None
    client = OpenSearch(
        hosts=[f"{host}:{port}"],
        host_compress=True,
        http_auth=auth,
        use_ssl=True if 'https' in host else False,
        verify_certs=True if not 'localhost' in host else False,
        ssl_show_warn=False
    )
    # Test whether client connection works (fail fast)
    try:
        if not client.ping():
            raise OpenSearchClientSetupError(
                "Cluster did not respond and may not be running.")
    except opensearchpy.exceptions.ConnectionError as ex:
        raise OpenSearchClientSetupError(ex.info)
    return client


def _does_index_exist(client, index):
    return client.indices.exists(index)


def _load_index_settings(index_settings_file):
    with index_settings_file.open() as source:
        body = json.load(source)
    return body


def _create_index(client, index_name, index_settings=None):
    response = client.indices.create(
        index_name, body=index_settings if index_settings else "")
    print(response)


def _load_to_dataframe(input_file):
    df = pd.read_csv(input_file)
    print(df.info(verbose=False))
    return df


def _add_os_columns_to_df(df, index):
    """ Add _index and _id columns to a dataframe and remap the numpy NaN (not a number) values to 'None'.

    OpenSearch docs using the bulk upload (can) have two additional fields. _index is required if it's not
    present in the _bulk api request path and _id is optional but if it's not present, OS will automatically
    assign an id. Assigning it intentionally allows this script to be idempotent.
    If there is an `id` field in the dataframe, it is used as the _id, otherwise the dataframe index (generally
    equivalent to the row number) is used.

    # TODO: using the df index as _id has high collision potential if there is already data in the OS index.
    """
    df = df.replace({np.nan: None})
    df['_index'] = index
    if 'id' in df.columns:
        df = df.rename(columns={"id": "_id"})
    else:
        df['_id'] = df.index
    return df


def _create_page_of_records(df, starting_index, page_size):
    return df.iloc[starting_index:starting_index+page_size].to_dict('records')


def _simple_bulk_insert(client, index, df):
    response = opensearchpy.helpers.bulk(client,
                                         df.to_dict('records'),
                                         max_retries=MAX_RETRIES)
    print(response)


def _paginated_bulk_insert(client, index, df):
    n_pages = len(df) // MAX_ROWS_PER_REQUEST + 1
    print(
        f"The records will be split into {n_pages} pages of up to {MAX_ROWS_PER_REQUEST} records."
    )
    for page in tqdm(range(n_pages)):
        response = opensearchpy.helpers.bulk(
            client,
            _create_page_of_records(
                df, page*MAX_ROWS_PER_REQUEST, MAX_ROWS_PER_REQUEST),
            max_retries=MAX_RETRIES
        )


if __name__ == "__main__":
    args = parser.parse_args()
    print("Args:")
    for k, v in vars(args).items():
        print(f"{k}: {v}")
    print()

    # Create the OpenSearch client
    print("Creating the OpenSearch client.")
    try:
        client = _create_client(
            args.host, args.port, *(args.user.split(':')) if args.user else (None, None))
    except OpenSearchClientSetupError as ex:
        print("Setting up the client failed.")
        print(ex)
        sys.exit(1)

    # If index settings are being specified, check whether the index exists and create it if not.
    if args.index_settings:
        index_exists = _does_index_exist(client, args.index)
        if index_exists:
            raise ValueError(
                'Cannot use index_settings file because the index already exists.')
        else:
            print("Creating index.")
            index_settings = _load_index_settings(args.index_settings)
            _create_index(client, args.index, index_settings)

    # Load input_file in pandas.
    print("Loading csv.")
    df = _load_to_dataframe(args.input_file)
    df = _add_os_columns_to_df(df, args.index)
    if len(df) <= MAX_ROWS_PER_REQUEST:
        print("Sending a single bulk insert.")
        _simple_bulk_insert(client, args.index, df)
    else:
        print("Preparing paginated bulk inserts.")
        _paginated_bulk_insert(client, args.index, df)
