#!/usr/bin/env python3

import argparse
import base64
import gzip
import json
import pathlib
from typing import Optional

LOG_JSON_TUPLE_FIELD = "message"
BASE64_ENCODED_TUPLE_PATHS = ["request.body", "primaryResponse.body", "shadowResponse.body"]
# TODO: I'm not positive about the capitalization of the Content-Encoding and Content-Type headers.
# This version worked on my test cases, but not guaranteed to work in all cases.
CONTENT_ENCODING_PATH = {
    BASE64_ENCODED_TUPLE_PATHS[0]: "request.content-encoding",
    BASE64_ENCODED_TUPLE_PATHS[1]: "primaryResponse.content-encoding",
    BASE64_ENCODED_TUPLE_PATHS[2]: "shadowResponse.content-encoding"
}
CONTENT_TYPE_PATH = {
    BASE64_ENCODED_TUPLE_PATHS[0]: "request.content-type",
    BASE64_ENCODED_TUPLE_PATHS[1]: "primaryResponse.content-type",
    BASE64_ENCODED_TUPLE_PATHS[2]: "shadowResponse.content-type"
}
CONTENT_TYPE_JSON = "application/json"
CONTENT_ENCODING_GZIP = "gzip"
URI_PATH = "request.Request-URI"
BULK_URI_PATH = "_bulk"


def get_element(element: str, dict_: dict) -> Optional[any]:
    keys = element.split('.')
    rv = dict_
    for key in keys:
        try:
            rv = rv[key]
        except KeyError:
            return None
    return rv


def set_element(element: str, dict_: dict, value: any) -> None:
    keys = element.split('.')
    rv = dict_
    for key in keys[:-1]:
        rv = rv[key]
    rv[keys[-1]] = value


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("infile", type=pathlib.Path, help="Path to input logged tuple file.")
    parser.add_argument("--outfile", type=pathlib.Path, help="Path for output human readable tuple file.")
    return parser.parse_args()


def parse_body_value(raw_value: str, content_encoding: Optional[str], content_type: Optional[str], is_bulk: bool):
    b64decoded = base64.b64decode(raw_value)
    is_gzipped = content_encoding is not None and content_encoding == CONTENT_ENCODING_GZIP
    is_json = content_type is not None and CONTENT_TYPE_JSON in content_type
    if is_gzipped:
        unzipped = gzip.decompress(b64decoded)
    else:
        unzipped = b64decoded
    decoded = unzipped.decode("utf-8")
    if is_json and len(decoded) > 0:
        if is_bulk:
            return [json.loads(line) for line in decoded.splitlines()]
        return json.loads(decoded)
    return decoded


def parse_tuple(line):
    item = json.loads(line)
    message = item[LOG_JSON_TUPLE_FIELD]
    tuple = json.loads(message)
    for path in BASE64_ENCODED_TUPLE_PATHS:
        base64value = get_element(path, tuple)
        content_encoding = get_element(CONTENT_ENCODING_PATH[path], tuple)
        content_type = get_element(CONTENT_TYPE_PATH[path], tuple)
        is_bulk_path = BULK_URI_PATH in get_element(URI_PATH, tuple)
        value = parse_body_value(base64value, content_encoding, content_type, is_bulk_path)
        set_element(path, tuple, value)
    return tuple


if __name__ == "__main__":
    args = parse_args()
    if args.outfile:
        outfile = args.outfile
    else:
        outfile = args.infile.parent / f"readable-{args.infile.name}"
    print(f"Input file: {args.infile}; Output file: {outfile}")
    with open(args.infile, 'r') as in_f:
        with open(outfile, 'w') as out_f:
            for line in in_f:
                print(parse_tuple(line), file=out_f)

# TODO: add some try/catching
# TODO: add a progress indicator for large files
