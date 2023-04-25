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

    # Test input json should match loaded pickle data
    def test_parser_happy_case(self):
        actual = parse(LOGSTASH_TEST_INPUT_FILE)
        test_diff = diff(self.test_data, actual)
        # Validate that diff is empty
        self.assertEqual(test_diff, dict())

    def test_bad_configs(self):
        # Checks for:
        # - Empty config
        # - Section should begin with type name
        # - Invalid type
        # - Valid type but no params
        bad_configs = ["", "{}", "bad {}", "input"]
        for config in bad_configs:
            self.assertRaises(lark.exceptions.UnexpectedToken, logstash_parser.parse, config)

    # Note that while these are considered valid Logstash configurations,
    # main.py considers them incomplete and would fail when validating them.
    def test_empty_config_can_be_parsed(self):
        logstash_parser.parse("input {}")
        logstash_parser.parse("filter {}")
        logstash_parser.parse("output {}")

    def test_string(self):
        val = self.test_data["input"][0][1]["string_key"]
        self.assertEqual(str, type(val))
        self.assertTrue(len(val) > 0)

    def test_bool(self):
        val = self.test_data["input"][0][1]["bool_key"]
        self.assertEqual(bool, type(val))
        self.assertTrue(val)

    def test_num(self):
        num = self.test_data["input"][0][1]["num_key"]
        neg_num = self.test_data["input"][1][1]["neg_key"]
        self.assertEqual(int, type(num))
        self.assertEqual(1, num)
        self.assertEqual(int, type(neg_num))
        self.assertEqual(-1, neg_num)


# Utility method to update the expected output pickle
# file if/when the input conf file is changed.
def __update_output_pickle():
    with open(LOGSTASH_EXPECTED_OUTPUT_FILE, "wb") as out:
        val = parse(LOGSTASH_TEST_INPUT_FILE)
        pickle.dump(val, out)


if __name__ == '__main__':
    unittest.main()
