import pickle
import unittest
from os.path import dirname

import lark.exceptions
from jsondiff import diff

from logstash_conf_parser import logstash_parser, parse

# Constants
LOGSTASH_TEST_INPUT_FILE = dirname(__file__) + "/logstash_test_input.conf"
LOGSTASH_EXPECTED_OUTPUT_FILE = dirname(__file__) + "/expected_parse_output.pickle"


class TestLogstashParser(unittest.TestCase):

    # Run before each test
    def setUp(self) -> None:
        with open(LOGSTASH_EXPECTED_OUTPUT_FILE, "rb") as f:
            # The root DS is a dict, with input type as key.
            # The value of each key is an array of inputs.
            # Each input is a tuple of plugin name and data,
            # where the data is a dict of key-value pairs.
            self.test_data = pickle.load(f)

    def test_parser_happy_case(self):
        actual = parse(LOGSTASH_TEST_INPUT_FILE)
        test_diff = diff(self.test_data, actual)
        self.assertFalse(test_diff)

    def test_bad_configs(self):
        with self.assertRaises(lark.exceptions.UnexpectedToken):
            # Empty config is an error
            logstash_parser.parse("")
            # Sections should begin with type name
            logstash_parser.parse("{}")
            # Invalid type
            logstash_parser.parse("bad {}")
            # Valid type but without params
            logstash_parser.parse("input")

    def test_empty_config(self):
        logstash_parser.parse("input {}")
        logstash_parser.parse("filter {}")
        logstash_parser.parse("output {}")

    def test_string(self):
        val = self.test_data["input"][0][1]["string_key"]
        assert type(val) is str
        self.assertTrue(len(val) > 0)

    def test_bool(self):
        val = self.test_data["input"][0][1]["bool_key"]
        assert type(val) is bool
        self.assertTrue(val)

    def test_num(self):
        num = self.test_data["input"][0][1]["num_key"]
        neg_num = self.test_data["input"][1][1]["neg_key"]
        assert type(num) is int
        self.assertTrue(num > 0)
        assert type(neg_num) is int
        self.assertTrue(neg_num < 0)


# Utility method to update the expected output pickle
# file if/when the input conf file is changed.
def _update_output_pickle():
    with open(LOGSTASH_EXPECTED_OUTPUT_FILE, "wb") as out:
        val = parse(LOGSTASH_TEST_INPUT_FILE)
        pickle.dump(val, out)


if __name__ == '__main__':
    unittest.main()
    #_update_output_pickle()
