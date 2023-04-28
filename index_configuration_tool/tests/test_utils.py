import re
import unittest

import utils


class TestUtils(unittest.TestCase):
    def test_string_from_set_empty(self):
        self.assertEqual("[]", utils.string_from_set(set()))

    def test_string_from_set(self):
        test_set = {'a b c', 'xyz', '123'}
        # Ordering from a set is not deterministic, but the output should match this regex
        pattern = re.compile(r"^\[(.+)(, )*]$")
        self.assertTrue(pattern.match(utils.string_from_set(test_set)))

    def test_has_differences(self):
        # Base case of empty dicts
        self.assertFalse(utils.has_differences("key", dict(), dict()))
        # Non empty dicts but differing key
        self.assertFalse(utils.has_differences("key", {"test": 0}, {"test": 0}))
        # Missing key
        self.assertTrue(utils.has_differences("key", dict(), {"key": 0}))
        self.assertTrue(utils.has_differences("key", {"key": 0}, {}))
        # Differing values
        self.assertTrue(utils.has_differences("key", {"key": 0}, {"key": 1}))
        # Identical dicts
        self.assertFalse(utils.has_differences("key", {"key": 0}, {"key": 0}))


if __name__ == '__main__':
    unittest.main()
