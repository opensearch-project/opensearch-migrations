from enum import Enum
import re
from console_link.models.command_result import CommandResult

import json
from typing import Any, Dict, Generator, List, Self, Set, Union
from cerberus import Validator
import base64
import gzip
import pathlib
from typing import Optional

import logging

logger = logging.getLogger(__name__)

SCHEMA = {
    'tuples': {
        'type': 'dict',
        'schema': {
            'path': {"type": "string", "required": False},
        }
    }
}


class TupleReader:
    def __init__(self, config: Dict) -> None:
        v = Validator(SCHEMA)
        self.config = config
        if not v.validate({"tuples": self.config}):
            raise ValueError("Invalid config file for tuples", v.errors)
        self.tuple_path = config.get('path', default='/shared-logs-output/traffic-replayer-default/')

    def list_files(self) -> CommandResult[List[str]]:
        return CommandResult(True, [])
    
    def _transform_file(self, filepath: str) -> Generator[Dict, None, None]:
        with open(filepath, 'r') as f:
            starting_data = open(f).readlines()
        for i, line in enumerate(starting_data):
            yield parse_tuple(line, i + 1)
    
    def transform_and_write_file(self, filepath: str, outfilepath: str) -> CommandResult[str]:
        transformer = self._transform_file(filepath)
        with open(outfilepath, 'a') as f:
            try:
                json.dump(f, next(transformer), indent=2)
            except StopIteration:
                return CommandResult(True, outfilepath)

        return CommandResult(True, outfilepath)


CONTENT_TYPE_JSON = "application/json"
CONTENT_ENCODING_GZIP = "gzip"
TRANSFER_ENCODING_CHUNKED = "chunked"
BULK_URI_PATH = "_bulk"

SOURCE_REQUEST = "sourceRequest"
TARGET_REQUEST = "targetRequest"
SOURCE_RESPONSE = "sourceResponse"
TARGET_RESPONSE = "targetResponses"

SINGLE_COMPONENTS = [SOURCE_REQUEST, SOURCE_RESPONSE, TARGET_REQUEST]
LIST_COMPONENTS = [TARGET_RESPONSE]

URI_PATH = SOURCE_REQUEST + ".Request-URI"

CONTENT_ENCODING_REGEX = re.compile('Content-Encoding', re.IGNORECASE)
CONTENT_TYPE_REGEX = re.compile('Content-Type', flags=re.IGNORECASE)
TRANSFER_ENCODING_REGEX = re.compile('Transfer-Encoding', flags=re.IGNORECASE)


class DictionaryPathException(Exception):
    pass


def get_element_with_regex(regex: re.Pattern, dict_: Dict, raise_on_error=False):
    keys = dict_.keys()
    try:
        match = next(filter(regex.match, keys))
    except StopIteration:
        if raise_on_error:
            raise DictionaryPathException(f"An element matching the regex ({regex}) was not found.")
        return None
    
    return dict_[match]


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
            elif try_lowercase_keys and key.lower() in rv:
                rv = rv[key.lower()]
            else:
                raise KeyError
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
        try:
            rv = rv[key]
        except KeyError:
            raise DictionaryPathException(f"Key {key} was not present.")
    try:
        rv[keys[-1]] = value
    except TypeError:
        raise DictionaryPathException(f"Path {element} did not reach an assignable object.")


def decode_chunked(data: bytes) -> bytes:
    splitlines = data.split(b'\r\n')
    text = [splitlines[i] for i in range(len(splitlines)) if i % 2 != 0]
    return b''.join(text)


Flag = Enum('Flag', ['Chunked_Transfer', 'Bulk_Request', 'Gzipped', 'Json'])


class TupleComponent:
    def __init__(self, component_name: str, component: Dict, line_no: int, is_bulk_path: bool):
        body = get_element("body", component)
        self.value: Union[bytes, str] = body
        
        self.flags = get_flags_for_component(component, is_bulk_path)

        self.line_no = line_no
        self.component_name = component_name

        self.final_value: Union[Dict, List, str, bytes] = {}
        self.error = False
    
    def b64decode(self) -> Self:
        if self.error or self.value is None:
            return self
        try:
            self.value = base64.b64decode(self.value)
        except Exception as e:
            self.error = (f"Body value of {self.component_name} on line {self.line_no} could not be decoded: {e}."
                          "Skipping parsing body value.")
        return self
    
    def dechunk(self) -> Self:
        if self.error or self.value is None:
            return self
        if Flag.Chunked_Transfer in self.flags:
            self.value = decode_chunked(self.value)
        return self
    
    def unzip(self) -> Self:
        if self.error or self.value is None:
            return self
        if Flag.Gzipped in self.flags:
            try:
                self.value = gzip.decompress(self.value)
            except Exception as e:
                self.error = (f"Body value of {self.component_name} on line {self.line_no} should be gzipped"
                              f"but could not be unzipped: {e}. Skipping parsing body value.")
        return self
    
    def decode_utf8(self) -> Self:
        if self.error or self.value is None:
            return self
        try:
            self.value = self.value.decode("utf-8")
        except Exception as e:
            self.error = (f"Body value of {self.component_name} on line {self.line_no} could not be decoded to utf-8: "
                          f"{e}. Skipping parsing body value.")
        return self
        
    def parse_as_json(self) -> Self:
        if self.error or self.value is None:
            return self
        if Flag.Json not in self.flags:
            self.final_value = self.value
            return self
        
        if Flag.Bulk_Request in self.flags:
            try:
                self.final_value = [json.loads(line) for line in self.value.splitlines()]
            except Exception as e:
                self.error = (f"Body value of {self.component_name} on line {self.line_no} should be a bulk json, but "
                              f"could not be parsed: {e}. Skipping parsing body value.")
                self.final_value = self.value
        else:
            try:
                self.final_value = json.loads(self.value)
            except Exception as e:
                self.error = (f"Body value of {self.component_name} on line {self.line_no} should be a json, but "
                              f"could not be parsed: {e}. Skipping parsing body value.")
                self.final_value = self.value
        return self


def get_flags_for_component(component: Dict[str, Any], is_bulk_path: bool) -> Set[Flag]:
    content_encoding = get_element_with_regex(CONTENT_ENCODING_REGEX, component)
    content_type = get_element_with_regex(CONTENT_TYPE_REGEX, component)
    transfer_encoding = get_element_with_regex(TRANSFER_ENCODING_REGEX, component)

    is_json = content_type is not None and CONTENT_TYPE_JSON in content_type
    is_chunked_transfer = transfer_encoding is not None and TRANSFER_ENCODING_CHUNKED in transfer_encoding.split(',')
    is_gzipped = content_encoding is not None and CONTENT_ENCODING_GZIP in content_encoding.split(',')
    return {Flag.Json if is_json else None,
            Flag.Chunked_Transfer if is_chunked_transfer else None,
            Flag.Gzipped if is_gzipped else None,
            Flag.Bulk_Request if is_bulk_path else None} - {None}


def parse_tuple(line: str, line_no: int) -> dict:
    initial_tuple = json.loads(line)
    try:
        is_bulk_path = BULK_URI_PATH in get_element(URI_PATH, initial_tuple, raise_on_error=True)
    except DictionaryPathException as e:
        logger.error(f"`{URI_PATH}` on line {line_no} could not be loaded: {e} "
                     f"Skipping parsing tuple.")
        return initial_tuple

    for component in SINGLE_COMPONENTS:
        tuple_component = TupleComponent(component, initial_tuple[component], line_no, is_bulk_path)

        processed_tuple = tuple_component.b64decode().dechunk().unzip().decode_utf8().parse_as_json()
        final_value = processed_tuple.final_value
        if not processed_tuple.error:
            set_element(component + ".body", initial_tuple, final_value)
        else:
            logger.error(processed_tuple.error)

    for component in LIST_COMPONENTS:
        for i, item in enumerate(initial_tuple[component]):
            tuple_component = TupleComponent(f"{component} item {i}", item, line_no, is_bulk_path)

            processed_tuple = tuple_component.b64decode().dechunk().unzip().decode_utf8().parse_as_json()
            final_value = processed_tuple.final_value
            if not processed_tuple.error:
                set_element("body", item, final_value)
            else:
                logger.error(processed_tuple.error)
    
    return initial_tuple
