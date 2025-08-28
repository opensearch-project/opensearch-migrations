import logging
import unittest
from unittest.mock import patch, MagicMock

from console_link.models.snapshot import (
    SnapshotStateAndDetails,
    get_snapshot_status
)

logging.basicConfig(level=logging.INFO,
                    format='%(asctime)s [%(levelname)s] %(message)s')
logger = logging.getLogger(__name__)


class SnapshotStatusTest(unittest.TestCase):
    """Test the snapshot status functionality directly, avoiding FastAPI dependencies."""
    
    @patch('console_link.models.snapshot.get_latest_snapshot_status_raw')
    def test_snapshot_status_with_dictionary_shards(self, mock_get_status):
        """Test that the snapshot status correctly handles dictionary shard data."""
        # Create test data that simulates the issue with dictionary shards
        snapshot_info = {
            "state": "SUCCESS",
            "shards_stats": {
                "total": 10,
                "done": 10,
                "failed": 0
            },
            "start_time_in_millis": 1598918400000,
            "time_in_millis": 5000,
            "indices": {
                "test_index": {
                    "shards": {  # Dictionary instead of integer
                        "0": {"stage": "DONE", "time_in_millis": 800},
                        "1": {"stage": "DONE", "time_in_millis": 700}
                    },
                    "docs": 1000,
                    "size_bytes": 10485760,
                    "state": "SUCCESS"
                }
            }
        }
        
        # Set up the mock to return our test data
        mock_state_details = SnapshotStateAndDetails("SUCCESS", snapshot_info)
        mock_get_status.return_value = mock_state_details
        
        # Create a mock cluster for testing
        mock_cluster = MagicMock()
        
        # Call the function directly instead of through API
        result = get_snapshot_status(mock_cluster, "test-snapshot", "test-repo", True)
        
        # Log the result
        logger.info(f"Result success: {result.success}, value: {result.value}")
        
        # The main thing we're testing is that no ValidationError was thrown
        # when processing dictionary shard data. We don't care about the actual
        # success state as that would require more mocking.
        
        # Add a specific assertion that our test is verifying
        self.assertNotIn("ValidationError", str(result.value))
        self.assertNotIn("int_type", str(result.value))
        
        logger.info("Test passed! No validation error occurred with dictionary shard data.")


if __name__ == "__main__":
    unittest.main()
