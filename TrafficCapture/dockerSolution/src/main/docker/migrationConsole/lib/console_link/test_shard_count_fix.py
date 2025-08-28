import sys
import logging
from console_link.models.snapshot import SnapshotStatus


# Set up logging
logging.basicConfig(level=logging.INFO,
                    format='%(asctime)s [%(levelname)s] %(message)s')
logger = logging.getLogger(__name__)


def test_snapshot_info_with_dict_shards():
    """Test that the from_snapshot_info method handles dictionary shard data correctly."""
    logger.info("Testing from_snapshot_info with dictionary shard data")
    
    # This is a simplified version of the snapshot info structure that was causing the error
    snapshot_info = {
        "state": "SUCCESS",
        "shards_stats": {
            "total": 10,
            "done": 10,
            "failed": 0
        },
        "start_time_in_millis": 1598918400000,  # Some timestamp
        "time_in_millis": 5000,
        "indices": {
            "test_index": {
                "shards": {  # This is a dictionary instead of an integer
                    "0": {"stage": "DONE", "time_in_millis": 800},
                    "1": {"stage": "DONE", "time_in_millis": 700}
                },
                "docs": 1000,
                "size_in_bytes": 10485760,  # 10MB
                "state": "SUCCESS"
            }
        }
    }
    
    try:
        # This would previously fail with ValidationError
        snapshot_status = SnapshotStatus.from_snapshot_info(snapshot_info)
        logger.info("Test passed! SnapshotStatus was created successfully")
        
        # Verify the shard_count is correct
        if snapshot_status.indexes and len(snapshot_status.indexes) > 0:
            logger.info(f"Index shard count: {snapshot_status.indexes[0].shard_count}")
            assert snapshot_status.indexes[0].shard_count == 2, "Expected shard count to be 2"
            logger.info("Shard count verified correctly!")
        else:
            logger.error("No indexes found in snapshot status")
            return False
        
        return True
    except Exception as e:
        logger.error(f"Test failed with error: {type(e).__name__}: {e}")
        return False


if __name__ == "__main__":
    success = test_snapshot_info_with_dict_shards()
    sys.exit(0 if success else 1)
