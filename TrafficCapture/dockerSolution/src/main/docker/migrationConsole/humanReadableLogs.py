#!/usr/bin/env python3

import argparse
import base64
import gzip
import json
import pathlib
from typing import Optional
import logging

from tqdm import tqdm
from tqdm.contrib.logging import logging_redirect_tqdm

logger = logging.getLogger(__name__)

BASE64_ENCODED_TUPLE_PATHS = ["sourceRequest.body", "targetRequest.body", "sourceResponse.body"]
# TODO: I'm not positive about the capitalization of the Content-Encoding and Content-Type headers.
# This version worked on my test cases, but not guaranteed to work in all cases.
CONTENT_ENCODING_PATH = {
    BASE64_ENCODED_TUPLE_PATHS[0]: "sourceRequest.Content-Encoding",
    BASE64_ENCODED_TUPLE_PATHS[1]: "targetRequest.Content-Encoding",
    BASE64_ENCODED_TUPLE_PATHS[2]: "sourceResponse.Content-Encoding"
}
CONTENT_TYPE_PATH = {
    BASE64_ENCODED_TUPLE_PATHS[0]: "sourceRequest.Content-Type",
    BASE64_ENCODED_TUPLE_PATHS[1]: "targetRequest.Content-Encoding",
    BASE64_ENCODED_TUPLE_PATHS[2]: "sourceResponse.Content-Type"
}
TRANSFER_ENCODING_PATH = {
    BASE64_ENCODED_TUPLE_PATHS[0]: "sourceRequest.Transfer-Encoding",
    BASE64_ENCODED_TUPLE_PATHS[1]: "targetRequest.Content-Encoding",
    BASE64_ENCODED_TUPLE_PATHS[2]: "sourceResponse.Transfer-Encoding"
}

CONTENT_TYPE_JSON = "application/json"
CONTENT_ENCODING_GZIP = "gzip"
TRANSFER_ENCODING_CHUNKED = "chunked"
URI_PATH = "sourceRequest.Request-URI"
BULK_URI_PATH = "_bulk"


class DictionaryPathException(Exception):
    pass


def get_element(element: str, dict_: dict, raise_on_error=False, try_lowercase_keys=False) -> Optional[any]:
    """This has a limited version of case-insensitivity. It specifically only checks the provided key
    and an all lower-case version of the key (if `try_lowercase_keys` is True)."""
    keys = element.split('.')
    rv = dict_
    for key in keys:
        try:
            if key in rv:
                rv = rv[key]
                continue
            if try_lowercase_keys and key.lower() in rv:
                rv = rv[key.lower()]
        except KeyError:
            if raise_on_error:
                raise DictionaryPathException(f"Key {key} was not present.")
            else:
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


def decode_chunked(data: bytes) -> bytes:
    newdata = []
    next_newline = data.index(b'\r\n')
    chunk = data[next_newline + 2:]
    while len(chunk) > 7:  # the final EOM chunk is 7 bytes
        next_newline = chunk.index(b'\r\n')
        newdata.append(chunk[:next_newline])
        chunk = chunk[next_newline + 2:]
    return b''.join(newdata)


def parse_body_value(raw_value: str, content_encoding: Optional[str],
                     content_type: Optional[str], is_bulk: bool, is_chunked_transfer: bool, line_no: int):
    # Body is base64 decoded
    try:
        b64decoded = base64.b64decode(raw_value)
    except Exception as e:
        logger.error(f"Body value on line {line_no} could not be decoded: {e}. Skipping parsing body value.")
        return None

    # Decoded data is un-chunked, if applicable
    if is_chunked_transfer:
        contiguous_data = decode_chunked(b64decoded)
    else:
        contiguous_data = b64decoded

    # Data is un-gzipped, if applicable
    is_gzipped = content_encoding is not None and content_encoding == CONTENT_ENCODING_GZIP
    if is_gzipped:
        try:
            unzipped = gzip.decompress(contiguous_data)
        except Exception as e:
            logger.error(f"Body value on line {line_no} should be gzipped but could not be unzipped: {e}. "
                         "Skipping parsing body value.")
            return contiguous_data
    else:
        unzipped = contiguous_data

    # Data is decoded to utf-8 string
    try:
        decoded = unzipped.decode("utf-8")
    except Exception as e:
        logger.error(f"Body value on line {line_no} could not be decoded to utf-8: {e}. "
                     "Skipping parsing body value.")
        return unzipped

    # Data is parsed as json, if applicable
    is_json = content_type is not None and CONTENT_TYPE_JSON in content_type
    if is_json and len(decoded) > 0:
        # Data is parsed as a bulk json, if applicable
        if is_bulk:
            try:
                return [json.loads(line) for line in decoded.splitlines()]
            except Exception as e:
                logger.error("Body value on line {line_no} should be a bulk json (list of json lines) but "
                             f"could not be parsed: {e}. Skipping parsing body value.")
                return decoded
        try:
            return json.loads(decoded)
        except Exception as e:
            logger.error(f"Body value on line {line_no} should be a json but could not be parsed: {e}. "
                         "Skipping parsing body value.")
            return decoded
    return decoded


def parse_tuple(line: str, line_no: int) -> dict:
    tuple = json.loads(line)
    try:
        is_bulk_path = BULK_URI_PATH in get_element(URI_PATH, tuple, raise_on_error=True)
    except DictionaryPathException as e:
        logger.error(f"`{URI_PATH}` on line {line_no} could not be loaded: {e} "
                     f"Skipping parsing tuple.")
        return tuple
    for body_path in BASE64_ENCODED_TUPLE_PATHS:
        base64value = get_element(body_path, tuple)
        if base64value is None:
            # This component has no body element, which is potentially valid.
            continue
        value = decode_base64_http_message(base64value, CONTENT_ENCODING_PATH[body_path], CONTENT_TYPE_PATH[body_path],
                                           TRANSFER_ENCODING_PATH[body_path], is_bulk_path, line_no, tuple)
        if value and type(value) is not bytes:
            set_element(body_path, tuple, value)
    for target_response in get_element("targetResponses", tuple):
        value = decode_base64_http_message(base64value, "Content-Encoding", "Content-Type",
                                           "Transfer-Encoding", is_bulk_path, line_no, target_response)
        if value and type(value) is not bytes:
            set_element("body", target_response, value)
    return tuple


def decode_base64_http_message(base64value, content_encoding, content_type, transfer_encoding,
                               is_bulk_path, line_no, tuple):
    content_encoding = get_element(content_encoding, tuple, try_lowercase_keys=True)
    content_type = get_element(content_type, tuple, try_lowercase_keys=True)
    is_chunked_transfer = get_element(transfer_encoding,
                                      tuple, try_lowercase_keys=True) == TRANSFER_ENCODING_CHUNKED
    return parse_body_value(base64value, content_encoding, content_type, is_bulk_path,
                             is_chunked_transfer, line_no)


if __name__ == "__main__":
    args = parse_args()
    if args.outfile:
        outfile = args.outfile
    else:
        outfile = args.infile.parent / f"readable-{args.infile.name}"
    print(f"Input file: {args.infile}; Output file: {outfile}")

    logging.basicConfig(level=logging.INFO)
    with logging_redirect_tqdm():
        with open(args.infile, 'r') as in_f:
            with open(outfile, 'w') as out_f:
                for i, line in tqdm(enumerate(in_f)):
                    print(json.dumps(parse_tuple(line, i + 1)), file=out_f)
