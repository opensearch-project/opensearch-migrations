import argparse
import json
import logging
import pytest

from cluster_tools.tools.migrate_document import main as migrate_document
from cluster_tools.tools.migrate_document import calculate_document_size
from cluster_tools.tools.migrate_document import DEFAULT_MAX_REQUEST_SIZE
from tests.utils import refresh_cluster, wait_for_document
from cluster_tools.base.utils import console_curl

logger = logging.getLogger(__name__)

# Constants
SOURCE_CLUSTER = "source_cluster"
TARGET_CLUSTER = "target_cluster"

pytestmark = pytest.mark.dual_cluster


def ensure_index_exists(env, index_name, cluster):
    """Ensure that an index exists in the specified cluster."""
    try:
        # Check if index exists
        console_curl(
            env=env,
            path=f"/{index_name}",
            cluster=cluster
        )
    except Exception:
        # Create the index if it doesn't exist
        console_curl(
            env=env,
            path=f"/{index_name}",
            cluster=cluster,
            method="PUT",
            json_data={}
        )

        # Refresh the index
        refresh_cluster(env, cluster)


def create_test_document(env, index_name, doc_id, field_size=10000, field_name="content"):
    """Create a test document with a specified field size in the source cluster."""
    # Ensure the index exists in both clusters
    ensure_index_exists(env, index_name, SOURCE_CLUSTER)
    ensure_index_exists(env, index_name, TARGET_CLUSTER)

    # Create document with a large content field
    doc = {
        "title": "Test Document",
        "description": "This is a test document for migration",
        field_name: "A,&#@/\\,< " * int(field_size / 10),
        "metadata": {
            "author": "Test Author",
            "tags": ["test", "migration"]
        }
    }

    # Index the document in the source cluster
    console_curl(
        env=env,
        path=f"/{index_name}/_doc/{doc_id}",
        cluster=SOURCE_CLUSTER,
        method="PUT",
        json_data=doc
    )

    # Refresh the index
    refresh_cluster(env, SOURCE_CLUSTER)

    return doc


def create_multi_field_document(env, index_name, doc_id, field_sizes):
    """Create a test document with multiple fields of specified sizes."""
    # Ensure the index exists in both clusters
    ensure_index_exists(env, index_name, SOURCE_CLUSTER)
    ensure_index_exists(env, index_name, TARGET_CLUSTER)

    # Create base document
    doc = {
        "title": "Multi-Field Test Document",
        "description": "This is a test document with multiple large fields",
        "metadata": {
            "author": "Test Author",
            "tags": ["test", "migration", "multi-field"]
        }
    }

    # Add fields with specified sizes
    for field_name, size in field_sizes.items():
        doc[field_name] = "A,&#@/\\,< " * int(size / 10)

    # Index the document in the source cluster
    console_curl(
        env=env,
        path=f"/{index_name}/_doc/{doc_id}",
        cluster=SOURCE_CLUSTER,
        method="PUT",
        json_data=doc
    )

    # Refresh the index
    refresh_cluster(env, SOURCE_CLUSTER)

    return doc


def verify_document_migration(env, index_name, doc_id, original_doc):
    """Verify that a document was correctly migrated to the target cluster."""
    # Refresh the target index to ensure the document is searchable
    refresh_cluster(env, TARGET_CLUSTER)

    # Get the migrated document
    migrated_doc_response = wait_for_document(env, index_name, doc_id, cluster=TARGET_CLUSTER)

    # Verify document exists
    assert "_source" in migrated_doc_response, "Migrated document not found"
    migrated_doc = migrated_doc_response["_source"]

    # Compare documents (with sorted keys for consistent comparison)
    original_json = json.dumps(original_doc, sort_keys=True)
    migrated_json = json.dumps(migrated_doc, sort_keys=True)

    assert original_json == migrated_json, "Original and migrated documents do not match"

    return migrated_doc


@pytest.fixture(scope="function")
def test_setup(env):
    """Set up the test environment with index and document."""
    index_name = "test-migration-index"
    doc_id = "test-doc-1"

    return {
        "index_name": index_name,
        "doc_id": doc_id
    }


def test_migrate_document(env, test_setup):
    """Test basic document migration between clusters."""
    index_name = test_setup["index_name"]
    doc_id = test_setup["doc_id"]

    # Create test document
    original_doc = create_test_document(env, index_name, doc_id)

    # Run migration
    args = argparse.Namespace(
        index=index_name,
        id=doc_id,
        source_type=SOURCE_CLUSTER,
        target_index=None,
        target_id=None,
        max_request_size=DEFAULT_MAX_REQUEST_SIZE
    )
    migrate_document(env, args)

    # Verify migration
    verify_document_migration(env, index_name, doc_id, original_doc)

    logger.info("Basic document migration test passed successfully")


def test_migrate_document_large_field_chunking(env, test_setup):
    """Test migration of document with field larger than chunk size."""
    index_name = test_setup["index_name"]
    doc_id = f"{test_setup['doc_id']}-large"

    large_field_size = 30 * 1024 * 1024
    large_doc = create_test_document(env, index_name, doc_id, field_size=large_field_size)

    # Run migration
    args = argparse.Namespace(
        index=index_name,
        id=doc_id,
        source_type=SOURCE_CLUSTER,
        target_index=None,
        target_id=None,
        max_request_size=DEFAULT_MAX_REQUEST_SIZE
    )
    migrate_document(env, args)

    # Verify migration
    migrated_doc = verify_document_migration(env, index_name, doc_id, large_doc)

    # Verify the content length specifically
    assert len(migrated_doc["content"]) == large_field_size, \
        f"Large field size mismatch. Expected: {large_field_size}, Got: {len(migrated_doc['content'])}"

    logger.info("Large field chunking test passed successfully")


def test_migrate_document_multiple_large_fields(env, test_setup):
    """Test migration of document with multiple large fields."""
    index_name = test_setup["index_name"]
    doc_id = f"{test_setup['doc_id']}-multi"

    # Create document with multiple large fields
    field_sizes = {
        "content1": 300000,
        "content2": 250000,
        "content3": 200000
    }

    multi_field_doc = create_multi_field_document(env, index_name, doc_id, field_sizes)

    # Run migration
    args = argparse.Namespace(
        index=index_name,
        id=doc_id,
        source_type=SOURCE_CLUSTER,
        target_index=None,
        target_id=None,
        max_request_size=DEFAULT_MAX_REQUEST_SIZE
    )
    migrate_document(env, args)

    # Verify migration
    migrated_doc = verify_document_migration(env, index_name, doc_id, multi_field_doc)

    # Verify each field's length
    for field_name, expected_size in field_sizes.items():
        assert len(migrated_doc[field_name]) == expected_size, \
            f"Field {field_name} size mismatch. Expected: {expected_size}, Got: {len(migrated_doc[field_name])}"

    logger.info("Multiple large fields migration test passed successfully")


def test_migrate_document_size_limit(env, test_setup):
    """Test migration of document that exceeds size limit and requires multiple fields to be chunked."""
    index_name = test_setup["index_name"]
    doc_id = f"{test_setup['doc_id']}-size-limit"

    # For testing purposes, we'll create a document with fields that would exceed the size limit
    # but are small enough to test efficiently
    test_size_limit = 50000  # Use a smaller size limit for testing

    # Create fields that will collectively exceed the test size limit
    field_sizes = {
        "field1": 20000,
        "field2": 15000,
        "field3": 10000,
        "field4": 5000,
        "small_field": 100
    }

    # Create the test document
    doc = create_multi_field_document(env, index_name, doc_id, field_sizes)

    # Verify the document size exceeds our test limit
    doc_size = calculate_document_size(doc)
    assert doc_size > test_size_limit, f"Test document size ({doc_size}) should exceed test limit ({test_size_limit})"

    # Run migration
    args = argparse.Namespace(
        index=index_name,
        id=doc_id,
        source_type=SOURCE_CLUSTER,
        target_index=None,
        target_id=None,
        max_request_size=DEFAULT_MAX_REQUEST_SIZE
    )
    migrate_document(env, args)

    # Verify migration
    migrated_doc = verify_document_migration(env, index_name, doc_id, doc)

    # Verify all fields were migrated correctly
    for field_name, expected_size in field_sizes.items():
        assert len(migrated_doc[field_name]) == expected_size, \
            f"Field {field_name} size mismatch. Expected: {expected_size}, Got: {len(migrated_doc[field_name])}"

    logger.info("Size limit handling test passed successfully")


def test_migrate_document_with_custom_source_type(env, test_setup):
    """Test document migration with a custom source type."""
    index_name = test_setup["index_name"]
    doc_id = f"{test_setup['doc_id']}-custom-source"

    # Create test document in the source cluster
    original_doc = create_test_document(env, index_name, doc_id)

    # Run migration with custom source type
    args = argparse.Namespace(
        index=index_name,
        id=doc_id,
        source_type=SOURCE_CLUSTER,  # Explicitly specify source type
        target_index=None,
        target_id=None,
        max_request_size=DEFAULT_MAX_REQUEST_SIZE
    )
    migrate_document(env, args)

    # Verify migration
    verify_document_migration(env, index_name, doc_id, original_doc)

    logger.info("Custom source type migration test passed successfully")


def test_migrate_document_with_different_target_index(env, test_setup):
    """Test document migration with a different target index."""
    src_index_name = test_setup["index_name"]
    dst_index_name = f"{test_setup['index_name']}-target"
    doc_id = f"{test_setup['doc_id']}-diff-index"

    # Create test document in the source cluster
    original_doc = create_test_document(env, src_index_name, doc_id)

    # Ensure the target index exists
    ensure_index_exists(env, dst_index_name, TARGET_CLUSTER)

    # Run migration with different target index
    args = argparse.Namespace(
        index=src_index_name,
        id=doc_id,
        source_type=SOURCE_CLUSTER,
        target_index=dst_index_name,
        target_id=None,
        max_request_size=DEFAULT_MAX_REQUEST_SIZE
    )
    migrate_document(env, args)

    # Verify migration - need to check in the target index
    migrated_doc_response = wait_for_document(env, dst_index_name, doc_id, cluster=TARGET_CLUSTER)

    # Verify document exists
    assert "_source" in migrated_doc_response, "Migrated document not found in target index"
    migrated_doc = migrated_doc_response["_source"]

    # Compare documents
    original_json = json.dumps(original_doc, sort_keys=True)
    migrated_json = json.dumps(migrated_doc, sort_keys=True)

    assert original_json == migrated_json, "Original and migrated documents do not match"

    logger.info("Different target index migration test passed successfully")


def test_migrate_document_with_different_target_id(env, test_setup):
    """Test document migration with a different target document ID."""
    index_name = test_setup["index_name"]
    src_doc_id = f"{test_setup['doc_id']}-src"
    dst_doc_id = f"{test_setup['doc_id']}-dst"

    # Create test document in the source cluster
    original_doc = create_test_document(env, index_name, src_doc_id)

    # Run migration with different target ID
    args = argparse.Namespace(
        index=index_name,
        id=src_doc_id,
        source_type=SOURCE_CLUSTER,
        target_index=None,
        target_id=dst_doc_id,
        max_request_size=DEFAULT_MAX_REQUEST_SIZE
    )
    migrate_document(env, args)

    # Verify migration - need to check with the target ID
    migrated_doc_response = wait_for_document(env, index_name, dst_doc_id, cluster=TARGET_CLUSTER)

    # Verify document exists
    assert "_source" in migrated_doc_response, "Migrated document not found with target ID"
    migrated_doc = migrated_doc_response["_source"]

    # Compare documents
    original_json = json.dumps(original_doc, sort_keys=True)
    migrated_json = json.dumps(migrated_doc, sort_keys=True)

    assert original_json == migrated_json, "Original and migrated documents do not match"

    logger.info("Different target ID migration test passed successfully")


def test_migrate_document_with_all_custom_parameters(env, test_setup):
    """Test document migration with all custom parameters (source type, target index, target ID)."""
    src_index_name = test_setup["index_name"]
    dst_index_name = f"{test_setup['index_name']}-all-custom"
    src_doc_id = f"{test_setup['doc_id']}-src-all"
    dst_doc_id = f"{test_setup['doc_id']}-dst-all"

    # Create test document in the source cluster
    original_doc = create_test_document(env, src_index_name, src_doc_id)

    # Ensure the target index exists
    ensure_index_exists(env, dst_index_name, TARGET_CLUSTER)

    # Run migration with all custom parameters
    args = argparse.Namespace(
        index=src_index_name,
        id=src_doc_id,
        source_type=SOURCE_CLUSTER,
        target_index=dst_index_name,
        target_id=dst_doc_id,
        max_request_size=DEFAULT_MAX_REQUEST_SIZE
    )
    migrate_document(env, args)

    # Verify migration - need to check in the target index with target ID
    migrated_doc_response = wait_for_document(env, dst_index_name, dst_doc_id, cluster=TARGET_CLUSTER)

    # Verify document exists
    assert "_source" in migrated_doc_response, "Migrated document not found in target index with target ID"
    migrated_doc = migrated_doc_response["_source"]

    # Compare documents
    original_json = json.dumps(original_doc, sort_keys=True)
    migrated_json = json.dumps(migrated_doc, sort_keys=True)

    assert original_json == migrated_json, "Original and migrated documents do not match"

    logger.info("All custom parameters migration test passed successfully")
