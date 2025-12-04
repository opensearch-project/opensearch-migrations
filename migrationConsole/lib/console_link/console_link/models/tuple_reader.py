from enum import Enum
import re

import json
from typing import Any, Dict, Generator, List, Self, Set, TextIO
import base64
from typing import Optional

import logging

logger = logging.getLogger(__name__)


class TupleReader:
    """ This class is fairly minimal for now. There is likely a future in which multiple
    tuple storage locations/types are supported, but we are not there yet and don't have
    a clear enough vision for it to make sense to frame it out now."""

    def __init__(self) -> None:
        # Initialize a TupleReader object.
        pass

    def transform_stream(self, inputfile: TextIO, outputfile: TextIO):
        transformer = self._transform_lines(inputfile.readlines())
        while True:
            try:
                json.dump(next(transformer), outputfile)
                outputfile.write('\n')
            except StopIteration:
                logger.info("Reached the end of the input object")
                return

    def _transform_lines(self, lines: List[str]) -> Generator[Dict, None, None]:
        for i, line in enumerate(lines):
            yield parse_tuple(line, i + 1)


CONTENT_TYPE_JSON = "application/json"
BULK_URI_PATH = "_bulk"

SOURCE_REQUEST = "sourceRequest"
TARGET_REQUEST = "targetRequest"
SOURCE_RESPONSE = "sourceResponse"
TARGET_RESPONSE = "targetResponses"

SINGLE_COMPONENTS = [SOURCE_REQUEST, SOURCE_RESPONSE, TARGET_REQUEST]
LIST_COMPONENTS = [TARGET_RESPONSE]

URI_PATH = SOURCE_REQUEST + ".Request-URI"

CONTENT_TYPE_REGEX = re.compile('Content-Type', flags=re.IGNORECASE)


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


Flag = Enum('Flag', ['Bulk_Request', 'Json'])


class TupleComponent:
    def __init__(self, component_name: str, component: Dict, line_no: int, is_bulk_path: bool):
        body = get_element("body", component)
        self.value: bytes | str = body

        self.flags = get_flags_for_component(component, is_bulk_path)

        self.line_no = line_no
        self.component_name = component_name

        self.final_value: dict | list | str | bytes = {}
        self.error = False

    def b64decode(self) -> Self:
        if self.error or self.value is None:
            return self
        try:
            self.value = base64.b64decode(self.value)
        except Exception as e:
            self.error = (f"Body value of {self.component_name} on line {self.line_no} could not be decoded: {e}."
                          "Skipping parsing body value.")
            logger.debug(self.error)
            logger.debug(self.value)
        return self

    def decode_utf8(self) -> Self:
        if self.error or self.value is None:
            return self
        try:
            self.value = self.value.decode("utf-8")
        except Exception as e:
            self.error = (f"Body value of {self.component_name} on line {self.line_no} could not be decoded to utf-8: "
                          f"{e}. Skipping parsing body value.")
            logger.debug(self.error)
            logger.debug(self.value)
        return self

    def parse_as_json(self) -> Self:
        if self.error or self.value is None:
            return self
        if Flag.Json not in self.flags:
            self.final_value = self.value
            return self

        if self.value.strip() == "":
            self.final_value = self.value
            return self

        if Flag.Bulk_Request in self.flags:
            try:
                self.final_value = [json.loads(line) for line in self.value.splitlines()]
            except Exception as e:
                self.error = (f"Body value of {self.component_name} on line {self.line_no} should be a bulk json, but "
                              f"could not be parsed: {e}. Skipping parsing body value.")
                logger.debug(self.error)
                logger.debug(self.value)
                self.final_value = self.value
        else:
            try:
                self.final_value = json.loads(self.value)
            except Exception as e:
                self.error = (f"Body value of {self.component_name} on line {self.line_no} should be a json, but "
                              f"could not be parsed: {e}. Skipping parsing body value.")
                logger.debug(self.error)
                logger.debug(self.value)
                self.final_value = self.value
        return self


def get_flags_for_component(component: Dict[str, Any], is_bulk_path: bool) -> Set[Flag]:
    content_type = get_element_with_regex(CONTENT_TYPE_REGEX, component)
    is_json = content_type is not None and CONTENT_TYPE_JSON in content_type
    return {Flag.Json if is_json else None,
            Flag.Bulk_Request if is_bulk_path else None} - {None}


def parse_tuple(line: str, line_no: int) -> dict:
    initial_tuple = json.loads(line)
    try:
        is_bulk_path = BULK_URI_PATH in get_element(URI_PATH, initial_tuple, raise_on_error=True)
    except DictionaryPathException as e:
        logger.error(f"`{URI_PATH}` on line {line_no} could not be loaded: {e} "
                     "Skipping parsing tuple.")
        return initial_tuple

    def process_value(label: str, value, update_fn) -> None:
        tc = TupleComponent(label, value, line_no, is_bulk_path)
        processed = tc.b64decode().decode_utf8().parse_as_json()
        if processed.error:
            logger.error(processed.error)
        else:
            update_fn(processed.final_value)

    for component in SINGLE_COMPONENTS:
        if component not in initial_tuple:
            logger.info(f"`{component}` was not present on line {line_no}. Skipping component.")
            continue
        process_value(
            component,
            initial_tuple[component],
            lambda final, comp=component: set_element(f"{comp}.body", initial_tuple, final)
        )

    for component in LIST_COMPONENTS:
        if component not in initial_tuple:
            logger.info(f"`{component}` was not present on line {line_no}. Skipping component.")
            continue
        for i, item in enumerate(initial_tuple[component]):
            process_value(
                f"{component} item {i}",
                item,
                lambda final, itm=item: set_element("body", itm, final)
            )

    return initial_tuple
