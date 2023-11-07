import logging
import unittest

from progress_metrics import ProgressMetrics


class TestProgressMetrics(unittest.TestCase):
    def setUp(self) -> None:
        logging.disable(logging.CRITICAL)

    def tearDown(self) -> None:
        logging.disable(logging.NOTSET)

    def test_doc_completion_percentage(self):
        metrics = ProgressMetrics(9, 0)
        # Base case
        self.assertEqual(0, metrics.get_doc_completion_percentage())
        # Completion percentage should round down
        metrics.update_success_doc_count(3)
        self.assertEqual(33, metrics.get_doc_completion_percentage())
        metrics.update_success_doc_count(9)
        self.assertEqual(100, metrics.get_doc_completion_percentage())

    def test_all_docs_migrated(self):
        metrics = ProgressMetrics(9, 0)
        # Base case
        self.assertFalse(metrics.all_docs_migrated())
        metrics.update_success_doc_count(3)
        self.assertFalse(metrics.all_docs_migrated())
        # Return value is true when >= target
        metrics.update_success_doc_count(9)
        self.assertTrue(metrics.all_docs_migrated())
        metrics.update_success_doc_count(10)
        self.assertTrue(metrics.all_docs_migrated())

    def test_is_migration_complete_success(self):
        metrics = ProgressMetrics(9, 0)
        # Base case
        self.assertFalse(metrics.is_migration_complete_success())
        # Update success docs
        metrics.update_success_doc_count(9)
        self.assertFalse(metrics.is_migration_complete_success())
        # Non-zero records in flight
        metrics.update_records_in_flight_count(1)
        self.assertFalse(metrics.is_migration_complete_success())
        # Zero records in flight, but no recorded partition count
        metrics.update_records_in_flight_count(0)
        self.assertFalse(metrics.is_migration_complete_success())
        # Record partition count, but no previous count
        metrics.update_no_partitions_count(1)
        self.assertFalse(metrics.is_migration_complete_success())
        # Update partition count, but it matches previous value
        metrics.update_no_partitions_count(1)
        self.assertFalse(metrics.is_migration_complete_success())
        # Update partition count to meet idle pipeline criteria
        metrics.update_no_partitions_count(2)
        self.assertTrue(metrics.is_migration_complete_success())

    def test_is_migration_idle(self):
        metrics = ProgressMetrics(9, 1)
        # Base case
        self.assertFalse(metrics.is_migration_idle())
        # Update success docs
        metrics.update_success_doc_count(3)
        self.assertFalse(metrics.is_migration_idle())
        # Update partition count
        metrics.update_no_partitions_count(1)
        self.assertFalse(metrics.is_migration_idle())
        # Update success docs to same value, which reaches threshold
        metrics.update_success_doc_count(3)
        self.assertTrue(metrics.is_migration_idle())

    def test_is_too_may_api_failures(self):
        metrics = ProgressMetrics(9, 1)
        # Base case
        self.assertFalse(metrics.is_too_may_api_failures())
        # Metric value failure does not count towards API failure
        metrics.record_success_doc_value_failure()
        self.assertFalse(metrics.is_too_may_api_failures())
        metrics.record_metric_api_failure()
        self.assertTrue(metrics.is_too_may_api_failures())

    def test_is_in_terminal_state(self):
        metrics = ProgressMetrics(9, 1)
        metrics.update_success_doc_count(1)
        metrics.update_no_partitions_count(1)
        self.assertFalse(metrics.is_in_terminal_state())
        # Too many API failures
        metrics.record_metric_api_failure()
        self.assertTrue(metrics.is_in_terminal_state())
        metrics.reset_metric_api_failure()
        # Idle pipeline
        metrics.update_no_partitions_count(1)
        self.assertTrue(metrics.is_in_terminal_state())
        metrics.update_no_partitions_count(2)
        # Migration complete
        metrics.update_success_doc_count(10)
        metrics.update_records_in_flight_count(0)
        self.assertTrue(metrics.is_in_terminal_state())


if __name__ == '__main__':
    unittest.main()
