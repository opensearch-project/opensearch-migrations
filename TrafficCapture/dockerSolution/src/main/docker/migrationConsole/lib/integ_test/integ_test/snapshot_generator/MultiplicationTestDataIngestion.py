"""
Data ingestion utilities for multiplication test suite.
"""

import json
import logging
from datetime import datetime
from console_link.models.cluster import HttpMethod
from integ_test.default_operations import DefaultOperationsLibrary as ops
from integ_test.snapshot_generator.MultiplicationTestUtils import (
    read_migration_config,
    get_environment_values
)

logger = logging.getLogger(__name__)


def generate_document(doc_id, batch_num, doc_in_batch):
    """Generate document structure with realistic fields for all cluster versions."""
    return {
        "timestamp": datetime.now().isoformat(),
        "value": f"test_value_{doc_in_batch}",
        "doc_number": doc_in_batch,
        "description": (
            f"This is a detailed description for document {doc_id} "
            "containing information about the test data and its purpose "
            "in the large snapshot creation process."
        ),
        "metadata": {
            "tags": [f"tag1_{doc_in_batch}", f"tag2_{doc_in_batch}", f"tag3_{doc_in_batch}"],
            "category": f"category_{doc_in_batch % 10}",
            "subcategories": [f"subcat1_{doc_in_batch % 5}", f"subcat2_{doc_in_batch % 5}"],
            "attributes": [f"attr1_{doc_in_batch % 8}", f"attr2_{doc_in_batch % 8}"],
            "status": f"status_{doc_in_batch % 6}",
            "version": f"1.{doc_in_batch % 10}.{doc_in_batch % 5}",
            "region": f"region_{doc_in_batch % 12}",
            "details": f"Detailed metadata information for document {doc_id} including test parameters."
        },
        "content": (
            f"Main content for document {doc_id}. This section contains the primary information "
            "and data relevant to the testing process. The content is designed to create minimal "
            "document for migration and multiplication."
        ),
        "additional_info": (
            f"Supplementary information for document {doc_id} "
            "providing extra context and details about the test data."
        )
    }


def get_index_settings(shard_count, es_major_version):
    """Get index settings with version-aware mapping structure for all 5 supported versions."""
    
    # Index properties common to all versions
    index_properties = {
        "timestamp": {"type": "date"},
        "value": {"type": "keyword"},
        "doc_number": {"type": "integer"},
        "description": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}},
        "metadata": {
            "properties": {
                "tags": {"type": "keyword"},
                "category": {"type": "keyword"},
                "subcategories": {"type": "keyword"},
                "attributes": {"type": "keyword"},
                "status": {"type": "keyword"},
                "version": {"type": "keyword"},
                "region": {"type": "keyword"},
                "details": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}}
            }
        },
        "content": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}},
        "additional_info": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}}
    }
    
    # Base settings
    index_settings = {
        "settings": {
            "number_of_shards": str(shard_count),
            "number_of_replicas": "0"
        }
    }
    
    # Version-specific mapping structure
    if es_major_version <= 6:
        # ES 5.x, ES 6.x: Include "doc" type in mapping
        index_settings["mappings"] = {
            "doc": {
                "properties": index_properties
            }
        }
    else:
        # ES 7.x, OS 1.x, OS 2.x: No type, direct properties
        index_settings["mappings"] = {
            "properties": index_properties
        }
    
    return index_settings


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


def ingest_test_documents(index_name, total_docs, sample_doc, source_cluster):
    """Ingest test documents using user-specified batch parameters."""
    # Get actual parameters from environment variables
    env_values = get_environment_values()
    batch_size = env_values['docs_per_batch']
    num_batches = env_values['batch_count']
    
    # Validate that the math works out
    expected_total = batch_size * num_batches
    if expected_total != total_docs:
        raise ValueError(
            f"Parameter mismatch: {batch_size} × {num_batches} = "
            f"{expected_total}, but total_docs = {total_docs}"
        )
    
    # Get version info from config file
    version_info = get_version_info_from_config()
    engine_version = version_info['engine_version']
    cluster_version = version_info['cluster_version']
    es_version = version_info['es_major_version']
    content_type = get_content_type_for_bulk(es_version)
    
    logger.info(
        f"Ingesting {total_docs} documents in {num_batches} batches "
        f"of {batch_size} documents each"
    )
    logger.info(
        f"Using user parameters: BATCH_COUNT={num_batches}, "
        f"DOCS_PER_BATCH={batch_size}"
    )
    logger.info(
        f"Detected from config: engine_version='{engine_version}' -> "
        f"cluster_version='{cluster_version}' -> ES major version="
        f"{es_version}"
    )
    logger.info(f"Using Content-Type: {content_type}")
    
    total_ingested = 0
    
    for batch_num in range(num_batches):
        start_doc = batch_num * batch_size + 1
        end_doc = min((batch_num + 1) * batch_size, total_docs)
        batch_doc_count = end_doc - start_doc + 1
        
        bulk_body_lines = []
        for doc_index in range(start_doc, end_doc + 1):
            doc_id = f"doc_{batch_num}_{doc_index - start_doc + 1}"
            
            # Generate each document to ingest
            document = generate_document(doc_id, batch_num, doc_index - start_doc + 1)
            
            # Use version-specific bulk index action format
            index_action = get_bulk_index_action(index_name, str(doc_index), es_version)
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
    
    if actual_count != total_docs:
        raise RuntimeError(f"Document count mismatch: Expected {total_docs}, found {actual_count}")
    
    logger.info(f"Successfully ingested {actual_count} documents to source cluster using {num_batches} batches")


def create_test_index(index_name, shard_count, source_cluster):
    """Create index with specific mapping and specified number of shards, handling existing index case."""
    
    # Get version info for index mappings
    version_info = get_version_info_from_config()
    es_major_version = version_info['es_major_version']
    
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
    
    # Use enhanced index settings with version-aware mapping
    index_settings = get_index_settings(shard_count, es_major_version)
    
    try:
        # Use create_index with specific index mappings
        ops().create_index(
            cluster=source_cluster,
            index_name=index_name,
            data=json.dumps(index_settings)
        )
        logger.info(f"Index '{index_name}' created with {shard_count} shards and enhanced mapping")
        logger.info(f"Version-aware mapping applied for ES major version {es_major_version}")
    except Exception as e:
        if "resource_already_exists_exception" in str(e):
            logger.warning(f"Index '{index_name}' already exists, continuing...")
        else:
            logger.error(f"Failed to create index '{index_name}': {e}")
            raise
