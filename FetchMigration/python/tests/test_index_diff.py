import copy
import unittest

from index_diff import IndexDiff
from tests import test_constants


class TestIndexDiff(unittest.TestCase):
    def test_index_diff_empty(self):
        # Base case should return an empty list
        diff = IndexDiff(dict(), dict())
        # All members should be empty
        self.assertEqual(set(), diff.indices_to_create)
        self.assertEqual(set(), diff.identical_indices)
        self.assertEqual(set(), diff.conflicting_indices)

    def test_index_diff_empty_target(self):
        diff = IndexDiff(test_constants.BASE_INDICES_DATA, dict())
        # No conflicts or identical indices
        self.assertEqual(set(), diff.conflicting_indices)
        self.assertEqual(set(), diff.identical_indices)
        # Indices-to-create
        self.assertEqual(3, len(diff.indices_to_create))
        self.assertTrue(test_constants.INDEX1_NAME in diff.indices_to_create)
        self.assertTrue(test_constants.INDEX2_NAME in diff.indices_to_create)
        self.assertTrue(test_constants.INDEX3_NAME in diff.indices_to_create)

    def test_index_diff_identical_index(self):
        test_data = copy.deepcopy(test_constants.BASE_INDICES_DATA)
        del test_data[test_constants.INDEX2_NAME]
        del test_data[test_constants.INDEX3_NAME]
        diff = IndexDiff(test_data, test_data)
        # No indices to move, or conflicts
        self.assertEqual(set(), diff.indices_to_create)
        self.assertEqual(set(), diff.conflicting_indices)
        # Identical indices
        self.assertEqual(1, len(diff.identical_indices))
        self.assertTrue(test_constants.INDEX1_NAME in diff.identical_indices)

    def test_index_diff_settings_conflict(self):
        test_data = copy.deepcopy(test_constants.BASE_INDICES_DATA)
        # Set up conflict in settings
        index_settings = test_data[test_constants.INDEX2_NAME][test_constants.SETTINGS_KEY]
        index_settings[test_constants.INDEX_KEY][test_constants.NUM_REPLICAS_SETTING] += 1
        diff = IndexDiff(test_constants.BASE_INDICES_DATA, test_data)
        # No indices to move
        self.assertEqual(set(), diff.indices_to_create)
        # Identical indices
        self.assertEqual(2, len(diff.identical_indices))
        self.assertTrue(test_constants.INDEX1_NAME in diff.identical_indices)
        self.assertTrue(test_constants.INDEX3_NAME in diff.identical_indices)
        # Conflicting indices
        self.assertEqual(1, len(diff.conflicting_indices))
        self.assertTrue(test_constants.INDEX2_NAME in diff.conflicting_indices)

    def test_index_diff_mappings_conflict(self):
        test_data = copy.deepcopy(test_constants.BASE_INDICES_DATA)
        # Set up conflict in mappings
        test_data[test_constants.INDEX3_NAME][test_constants.MAPPINGS_KEY] = {}
        diff = IndexDiff(test_constants.BASE_INDICES_DATA, test_data)
        # No indices to move
        self.assertEqual(set(), diff.indices_to_create)
        # Identical indices
        self.assertEqual(2, len(diff.identical_indices))
        self.assertTrue(test_constants.INDEX1_NAME in diff.identical_indices)
        self.assertTrue(test_constants.INDEX2_NAME in diff.identical_indices)
        # Conflicting indices
        self.assertEqual(1, len(diff.conflicting_indices))
        self.assertTrue(test_constants.INDEX3_NAME in diff.conflicting_indices)


if __name__ == '__main__':
    unittest.main()
