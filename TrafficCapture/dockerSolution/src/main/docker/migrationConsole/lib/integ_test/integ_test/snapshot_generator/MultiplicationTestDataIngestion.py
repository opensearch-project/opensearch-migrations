"""
Data ingestion utilities for multiplication test suite.
"""

import json
import logging
from console_link.models.cluster import HttpMethod
from integ_test.default_operations import DefaultOperationsLibrary as ops
from integ_test.snapshot_generator.MultiplicationTestUtils import read_migration_config

logger = logging.getLogger(__name__)

# Sample document used for testing
SAMPLE_DOCUMENT = {"title": "Large Snapshot Migration Test Document"}


def calculate_optimal_batch_size(total_docs, max_batch_size=1000):
    """Calculate optimal batch size to prevent bulk request errors."""
    if total_docs <= max_batch_size:
        return total_docs
    
    optimal_size = max_batch_size
    while total_docs % optimal_size > optimal_size * 0.1 and optimal_size > 100:
        optimal_size -= 50
    
    return max(optimal_size, 100)


def map_engine_version_to_cluster_version(engine_version):
    """Map engine version (ES_5.6, OS_2.19, etc.) to cluster version (es5x, os2x, etc.)."""
    if engine_version.startswith('ES_5'):
        return 'es5x'
    elif engine_version.startswith('ES_6'):
        return 'es6x'
    elif engine_version.startswith('ES_7'):
        return 'es7x'
    elif engine_version.startswith('OS_1'):
        return 'os1x'
    elif engine_version.startswith('OS_2'):
        return 'os2x'
    else:
        logger.warning(f"Unknown engine version: {engine_version}, defaulting to es7x")
        return 'es7x'


def get_es_major_version_from_cluster_version(cluster_version):
    """Extract major version from cluster version."""
    if cluster_version == 'es5x':
        return 5
    elif cluster_version == 'es6x':
        return 6
    elif cluster_version == 'es7x':
        return 7
    elif cluster_version == 'os1x':
        return 6  # OS 1.x behaves like ES 6.x for bulk API
    elif cluster_version == 'os2x':
        return 7  # OS 2.x behaves like ES 7.x for bulk API
    else:
        return 7  # Default to modern format


def get_version_info_from_config(config_file_path=None):
    """Get engine version, cluster version, and ES major version from config file."""
    config = read_migration_config(config_file_path)
    engine_version = config['engine_version']
    cluster_version = map_engine_version_to_cluster_version(engine_version)
    es_major_version = get_es_major_version_from_cluster_version(cluster_version)
    
    return {
        'engine_version': engine_version,
        'cluster_version': cluster_version,
        'es_major_version': es_major_version
    }


def get_content_type_for_bulk(es_major_version):
    """Get appropriate Content-Type header for bulk operations based on ES version."""
    if es_major_version <= 5:
        return "application/json"
    else:
        return "application/x-ndjson"


def get_bulk_index_action(index_name, doc_id, es_major_version):
    """Get bulk index action with appropriate format based on ES version."""
    if es_major_version <= 6:
        return {"index": {"_index": index_name, "_type": "doc", "_id": doc_id}}
    else:
        return {"index": {"_index": index_name, "_id": doc_id}}


def ingest_test_documents(index_name, document_count, sample_doc, source_cluster):
    """Ingest test documents to source cluster using direct API calls for reliability."""
    total_docs = document_count
    batch_size = calculate_optimal_batch_size(total_docs)
    num_batches = (total_docs + batch_size - 1) // batch_size
    
    # Get version info from config file
    version_info = get_version_info_from_config()
    engine_version = version_info['engine_version']
    cluster_version = version_info['cluster_version']
    es_version = version_info['es_major_version']
    content_type = get_content_type_for_bulk(es_version)
    
    logger.info(f"Ingesting {total_docs} documents in {num_batches} batches of ~{batch_size} documents each")
    logger.info(f"Detected from config: engine_version='{engine_version}' -> "
                f"cluster_version='{cluster_version}' -> ES major version={es_version}")
    logger.info(f"Using Content-Type: {content_type}")
    
    total_ingested = 0
    
    for batch_num in range(num_batches):
        start_doc = batch_num * batch_size + 1
        end_doc = min((batch_num + 1) * batch_size, total_docs)
        batch_doc_count = end_doc - start_doc + 1
        
        bulk_body_lines = []
        for doc_index in range(start_doc, end_doc + 1):
            doc_id = str(doc_index)
            
            document = sample_doc.copy()
            document["doc_number"] = str(doc_index)
            
            # Use version-specific bulk index action format
            index_action = get_bulk_index_action(index_name, doc_id, es_version)
            bulk_body_lines.append(json.dumps(index_action))
            bulk_body_lines.append(json.dumps(document))
        
        bulk_body = "\n".join(bulk_body_lines) + "\n"
        
        logger.info(f"Batch {batch_num + 1}/{num_batches}: Ingesting documents "
                    f"{start_doc}-{end_doc} ({batch_doc_count} docs)")
        
        # Use direct cluster API call instead of run_console_command
        response = source_cluster.call_api(
            path="/_bulk",
            method=HttpMethod.POST,
            data=bulk_body,
            headers={"Content-Type": content_type}
        )
        
        if not response.ok:
            logger.error(f"Bulk indexing failed: {response.status_code} - {response.text}")
            raise RuntimeError(f"Bulk indexing failed: {response.status_code} - {response.text}")
        
        # Check for bulk operation errors in response
        try:
            bulk_response = response.json()
            if bulk_response.get("errors", False):
                logger.error(f"Bulk operation had errors: {bulk_response}")
                raise RuntimeError(f"Bulk operation had errors: {bulk_response}")
        except Exception as e:
            logger.warning(f"Could not parse bulk response JSON: {e}")
        
        total_ingested += batch_doc_count
    
    # Refresh index using direct API call
    refresh_response = source_cluster.call_api(
        path="/_refresh",
        method=HttpMethod.POST
    )
    
    if not refresh_response.ok:
        logger.warning(f"Refresh failed: {refresh_response.status_code} - {refresh_response.text}")
    
    # Get document count using direct API call
    count_response = source_cluster.call_api(
        path=f"/{index_name}/_count",
        method=HttpMethod.GET
    )
    
    if not count_response.ok:
        raise RuntimeError(f"Count query failed: {count_response.status_code} - {count_response.text}")
    
    try:
        response_data = count_response.json()
        actual_count = response_data.get("count", 0)
    except Exception as e:
        raise RuntimeError(f"Failed to parse count response: {e}")
    
    if actual_count != document_count:
        raise RuntimeError(f"Document count mismatch: Expected {document_count}, found {actual_count}")
    
    logger.info(f"Successfully ingested {actual_count} documents to source cluster using {num_batches} batches")


def create_test_index(index_name, shard_count, source_cluster):
    """Create index with specified number of shards, handling existing index case."""
    
    # First, try to delete the index if it exists
    try:
        logger.info(f"Checking if index '{index_name}' exists...")
        result = source_cluster.call_api(f"/{index_name}", HttpMethod.HEAD)
        if result.status_code == 200:
            logger.info(f"Index '{index_name}' exists, deleting it first...")
            delete_result = source_cluster.call_api(f"/{index_name}", HttpMethod.DELETE)
            if delete_result.status_code in [200, 404]:
                logger.info(f"Successfully deleted existing index '{index_name}'")
            else:
                logger.warning(f"Failed to delete existing index: {delete_result.status_code}")
    except Exception as e:
        logger.info(f"Could not check/delete existing index (will try to create anyway): {e}")
    
    # Create the index
    index_settings = {
        "settings": {
            "number_of_shards": shard_count,
            "number_of_replicas": 0
        }
    }
    
    try:
        ops().create_index(
            index_name=index_name,
            cluster=source_cluster,
            data=json.dumps(index_settings)
        )
        logger.info(f"Index '{index_name}' created with {shard_count} shards")
    except Exception as e:
        if "resource_already_exists_exception" in str(e):
            logger.warning(f"Index '{index_name}' already exists, continuing...")
        else:
            logger.error(f"Failed to create index '{index_name}': {e}")
            raise
