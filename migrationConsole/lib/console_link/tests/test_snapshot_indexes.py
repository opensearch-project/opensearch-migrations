import json
import logging
import os
import tempfile
from unittest.mock import patch

import pytest
import yaml

from console_link.environment import Environment
from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.snapshot import (
    SnapshotIndex,
    SnapshotIndexes,
    _get_index_stats,
    _get_shard_counts,
    _build_index_list,
    get_cluster_indexes
)
from tests.search_containers import SearchContainer, Version

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

TEST_VERSION = Version("OPENSEARCH", 2, 19, 1)

TEST_INDEX_PREFIX = "test_snapshot_index_"
TEST_INDEX_COUNT = 3
TEST_SHARD_COUNT = 2
TEST_DOC_COUNTS = [10, 20, 30]


def create_test_indices(cluster: Cluster):
    """Create test indices with different document counts and settings."""
    for i in range(TEST_INDEX_COUNT):
        index_name = f"{TEST_INDEX_PREFIX}{i}"
        doc_count = TEST_DOC_COUNTS[i]
        
        settings = {
            "settings": {
                "index": {
                    "number_of_shards": TEST_SHARD_COUNT,
                    "number_of_replicas": 0
                }
            },
            "mappings": {
                "properties": {
                    "content": {"type": "text"},
                    "count": {"type": "integer"}
                }
            }
        }
        
        cluster.call_api(
            f"/{index_name}",
            HttpMethod.PUT,
            data=json.dumps(settings),
            headers={"Content-Type": "application/json"}
        )
        
        bulk_data = []
        for j in range(doc_count):
            bulk_data.append(json.dumps({"index": {"_index": index_name}}))
            bulk_data.append(json.dumps({"content": f"Test document {j}", "count": j}))
        
        bulk_request = "\n".join(bulk_data) + "\n"
        cluster.call_api(
            "/_bulk",
            HttpMethod.POST,
            data=bulk_request,
            headers={"Content-Type": "application/x-ndjson"}
        )
    
    test_stream_index = "test_data_stream_index"
    settings = {
        "settings": {
            "index": {
                "number_of_shards": TEST_SHARD_COUNT,
                "number_of_replicas": 0
            }
        },
        "mappings": {
            "properties": {
                "@timestamp": {"type": "date"},
                "message": {"type": "text"}
            }
        }
    }
    
    cluster.call_api(
        f"/{test_stream_index}",
        HttpMethod.PUT,
        data=json.dumps(settings),
        headers={"Content-Type": "application/json"}
    )
    
    bulk_data = []
    for j in range(5):
        bulk_data.append(json.dumps({"index": {"_index": test_stream_index}}))
        bulk_data.append(json.dumps({"@timestamp": "2023-01-01T00:00:00.000Z", "message": f"Test message {j}"}))
    
    bulk_request = "\n".join(bulk_data) + "\n"
    cluster.call_api(
        "/_bulk",
        HttpMethod.POST,
        data=bulk_request,
        headers={"Content-Type": "application/x-ndjson"}
    )
    
    cluster.call_api("/_refresh", HttpMethod.POST)


def verify_index_info(index_info: SnapshotIndex, index_num: int):
    """Verify the index information matches expectations."""
    index_name = f"{TEST_INDEX_PREFIX}{index_num}"
    expected_doc_count = TEST_DOC_COUNTS[index_num]
    
    assert index_info.name == index_name
    assert index_info.document_count == expected_doc_count
    assert index_info.size_bytes > 0
    assert index_info.shard_count == TEST_SHARD_COUNT


@pytest.fixture(scope="class")
def env_with_cluster(request):
    """Fixture to set up a test cluster and environment once for all tests in the class."""
    version = TEST_VERSION

    container = SearchContainer(version, mem_limit="3G")
    container.start()

    base_url = f"http://{container.get_container_host_ip()}:{container.get_exposed_port(9200)}"

    services_config = {
        'source_cluster': {
            'endpoint': base_url,
            'allow_insecure': True,
            'no_auth': {}
        },
        'target_cluster': {
            'endpoint': "http://target_endpoint_not_used:9200",
            'allow_insecure': True,
            'no_auth': {}
        }
    }

    with tempfile.NamedTemporaryFile(mode='w', delete=False) as temp_config:
        yaml.dump(services_config, temp_config)
        temp_config_path = temp_config.name

    env = Environment(config_file=temp_config_path)

    if env.source_cluster is not None:
        create_test_indices(env.source_cluster)

    request.cls.env = env
    request.cls.container = container
    request.cls.temp_config_path = temp_config_path

    yield env

    container.stop()
    os.remove(temp_config_path)


def mock_resolve_index_patterns(cluster, index_patterns):
    """Mock implementation that avoids using the /_resolve/index API endpoint."""
    if not index_patterns:
        return None
    
    if any(pattern.startswith(TEST_INDEX_PREFIX) for pattern in index_patterns):
        return ",".join([f"{TEST_INDEX_PREFIX}{i}" for i in range(TEST_INDEX_COUNT)])
    
    if any("test_data_stream" in pattern for pattern in index_patterns):
        return "test_data_stream_index"
    
    return None


@pytest.mark.slow
@pytest.mark.usefixtures("env_with_cluster")
class TestSnapshotIndexes:
    """Test class for snapshot index related functions with shared cluster setup."""
    
    env: Environment
    container: SearchContainer
    temp_config_path: str
    
    @patch('console_link.models.snapshot._resolve_index_patterns', mock_resolve_index_patterns)
    def test_resolve_index_patterns(self):
        """Test the resolve_index_patterns function with our mock implementation."""
        assert self.env.source_cluster is not None
        source_cluster = self.env.source_cluster
        
        targets = mock_resolve_index_patterns(source_cluster, [f"{TEST_INDEX_PREFIX}*"])
        assert targets is not None
        target_list = targets.split(",")
        assert len(target_list) == TEST_INDEX_COUNT
        
        for i in range(TEST_INDEX_COUNT):
            assert f"{TEST_INDEX_PREFIX}{i}" in target_list
        
        targets = mock_resolve_index_patterns(source_cluster, ["test_data_stream*"])
        assert targets is not None
        assert "test_data_stream_index" in targets
        
        targets = mock_resolve_index_patterns(source_cluster, ["non_existent_index*"])
        assert targets is None
        
        targets = mock_resolve_index_patterns(source_cluster, None)
        assert targets is None
    
    @patch('console_link.models.snapshot._resolve_index_patterns', mock_resolve_index_patterns)
    def test_get_index_stats(self):
        """Test the get_index_stats function."""
        assert self.env.source_cluster is not None
        source_cluster = self.env.source_cluster
        
        targets = mock_resolve_index_patterns(source_cluster, [f"{TEST_INDEX_PREFIX}*"])
        indices = _get_index_stats(source_cluster, targets)
        
        assert len(indices) == TEST_INDEX_COUNT
        
        for i in range(TEST_INDEX_COUNT):
            index_name = f"{TEST_INDEX_PREFIX}{i}"
            assert index_name in indices
            
            index_stats = indices[index_name]
            primaries = index_stats.get('primaries', {})
            docs = primaries.get('docs', {})
            
            assert docs.get('count') == TEST_DOC_COUNTS[i]
            
            store = primaries.get('store', {})
            assert store.get('size_in_bytes', 0) > 0
        
        all_indices = _get_index_stats(source_cluster, None)
        assert len(all_indices) >= TEST_INDEX_COUNT
        
        for i in range(TEST_INDEX_COUNT):
            index_name = f"{TEST_INDEX_PREFIX}{i}"
            assert index_name in all_indices
    
    @patch('console_link.models.snapshot._resolve_index_patterns', mock_resolve_index_patterns)
    def test_get_shard_counts(self):
        """Test the get_shard_counts function."""
        assert self.env.source_cluster is not None
        source_cluster = self.env.source_cluster
        
        targets = mock_resolve_index_patterns(source_cluster, [f"{TEST_INDEX_PREFIX}*"])
        shard_counts = _get_shard_counts(source_cluster, targets)
        
        assert len(shard_counts) >= TEST_INDEX_COUNT
        
        for i in range(TEST_INDEX_COUNT):
            index_name = f"{TEST_INDEX_PREFIX}{i}"
            assert index_name in shard_counts
            
            assert shard_counts[index_name] == TEST_SHARD_COUNT
        
        all_shard_counts = _get_shard_counts(source_cluster, None)
        assert len(all_shard_counts) >= TEST_INDEX_COUNT
        
        for i in range(TEST_INDEX_COUNT):
            index_name = f"{TEST_INDEX_PREFIX}{i}"
            assert index_name in all_shard_counts
            assert all_shard_counts[index_name] == TEST_SHARD_COUNT
    
    @patch('console_link.models.snapshot._resolve_index_patterns', mock_resolve_index_patterns)
    def test_build_index_list(self):
        """Test the build_index_list function."""
        assert self.env.source_cluster is not None
        source_cluster = self.env.source_cluster
        
        targets = mock_resolve_index_patterns(source_cluster, [f"{TEST_INDEX_PREFIX}*"])
        indices = _get_index_stats(source_cluster, targets)
        shard_counts = _get_shard_counts(source_cluster, targets)
        
        index_list = _build_index_list(indices, shard_counts)
        
        assert len(index_list) == TEST_INDEX_COUNT
        
        for i, index_info in enumerate(index_list):
            verify_index_info(index_info, i)
    
    @patch('console_link.models.snapshot._resolve_index_patterns', mock_resolve_index_patterns)
    def test_get_cluster_indexes(self):
        """Test the get_cluster_indexes function."""
        assert self.env.source_cluster is not None
        source_cluster = self.env.source_cluster
        
        snapshot_indexes = get_cluster_indexes(source_cluster, [f"{TEST_INDEX_PREFIX}*"])
        
        assert isinstance(snapshot_indexes, SnapshotIndexes)
        assert len(snapshot_indexes.indexes) == TEST_INDEX_COUNT
        
        index_list = sorted(snapshot_indexes.indexes, key=lambda x: x.name)
        for i, index_info in enumerate(index_list):
            verify_index_info(index_info, i)

        all_snapshot_indexes = get_cluster_indexes(source_cluster, None)
        assert isinstance(all_snapshot_indexes, SnapshotIndexes)
        assert len(all_snapshot_indexes.indexes) >= TEST_INDEX_COUNT
        
        test_indices = [idx for idx in all_snapshot_indexes.indexes if idx.name.startswith(TEST_INDEX_PREFIX)]
        test_indices = sorted(test_indices, key=lambda x: x.name)
        
        assert len(test_indices) == TEST_INDEX_COUNT
        for i, index_info in enumerate(test_indices):
            verify_index_info(index_info, i)
        
        stream_indexes = get_cluster_indexes(source_cluster, ["test_data_stream*"])
        assert isinstance(stream_indexes, SnapshotIndexes)
        assert len(stream_indexes.indexes) >= 1
