import argparse
import json
import logging
from typing import Dict, Any, Tuple, List

from console_link.environment import Environment
from cluster_tools.base.utils import console_curl

logger = logging.getLogger(__name__)

# Constants
SRC_CLUSTER = "source_cluster"
DST_CLUSTER = "target_cluster"
DEFAULT_MAX_REQUEST_SIZE = 9 * 1024 * 1024  # 9MB in bytes


def define_arguments(parser: argparse.ArgumentParser) -> None:
    """Defines arguments for migrating a document between clusters."""
    parser.add_argument("index", type=str, help="Name of the index containing the document")
    parser.add_argument("id", type=str, help="ID of the document to migrate")
    parser.add_argument("--source-type", type=str, default=SRC_CLUSTER,
                        help=f"Source type to migrate from (default: {SRC_CLUSTER})")
    parser.add_argument("--target-index", type=str,
                        help="Target index name (default: same as source index)")
    parser.add_argument("--target-id", type=str,
                        help="Target document ID (default: same as source ID)")
    parser.add_argument("--max-request-size", type=int, default=DEFAULT_MAX_REQUEST_SIZE,
                        help=f"Maximum request size in bytes (default: {DEFAULT_MAX_REQUEST_SIZE} bytes, 9MB)")


def get_document_source(env: Environment, index: str, doc_id: str, cluster: str) -> Dict[str, Any]:
    """Retrieves a document's _source from the specified cluster."""
    logger.info(f"Retrieving document '{doc_id}' from index '{index}' in {cluster}")

    response = console_curl(
        env=env,
        path=f"/{index}/_doc/{doc_id}",
        cluster=cluster,
    )

    if isinstance(response, str):
        response = json.loads(response)

    if not response.get("found", False) or "_source" not in response:
        raise ValueError(f"Document {doc_id} not found in index {index} on {cluster}")

    return response["_source"]


def calculate_document_size(doc: Dict[str, Any]) -> int:
    """Calculate the size of a document in bytes."""
    return len(json.dumps(doc).encode('utf-8'))


def get_string_fields_by_size(doc: Dict[str, Any]) -> List[Tuple[str, str]]:
    """Returns a list of (field_name, field_value) tuples for all string fields, sorted by size (largest first)."""
    string_fields = []

    for key, value in doc.items():
        if isinstance(value, str) and len(value) > 0:
            string_fields.append((key, value))

    # Sort by string length (largest first)
    string_fields.sort(key=lambda x: len(x[1]), reverse=True)

    return string_fields


def identify_fields_to_chunk(doc: Dict[str, Any], max_request_size: int) -> List[Tuple[str, str]]:
    """Identifies string fields that need to be chunked to get document size under max_request_size."""
    doc_size = calculate_document_size(doc)
    logger.info(f"Original document size: {doc_size} bytes")

    if doc_size <= max_request_size:
        logger.info("Document is already under size limit, no chunking needed")
        return []

    # Get all string fields sorted by size
    string_fields = get_string_fields_by_size(doc)

    if not string_fields:
        raise ValueError("No string fields found in the document")

    fields_to_chunk = []
    working_doc = doc.copy()

    # Remove largest fields one by one until we're under the size limit
    for field_name, field_value in string_fields:
        # Skip if field is already empty
        if not field_value:
            continue

        # Calculate size with this field removed
        working_doc[field_name] = ""
        new_size = calculate_document_size(working_doc)

        # Calculate how much size was reduced
        current_size = new_size

        # Add field to chunking list
        fields_to_chunk.append((field_name, field_value))

        # Check if we're under the size limit
        if current_size <= max_request_size:
            break

    if fields_to_chunk:
        logger.info(f"Fields to chunk: {[f[0] for f in fields_to_chunk]}")
    else:
        logger.warning("No suitable string fields found for chunking")

    return fields_to_chunk


def create_initial_document(env: Environment, index: str, doc_id: str,
                            doc: Dict[str, Any], fields_to_chunk: List[Tuple[str, str]],
                            dst_cluster: str = DST_CLUSTER) -> None:
    """Creates the initial document in the target cluster with large fields removed."""
    # Create a copy of the document
    init_doc = doc.copy()

    # Set large fields to empty strings
    for field_name, _ in fields_to_chunk:
        init_doc[field_name] = ""

    # Calculate size of initial document
    init_size = calculate_document_size(init_doc)
    logger.info(f"Creating initial document in target cluster (size: {init_size} bytes)")

    # Index the document
    console_curl(
        env=env,
        path=f"/{index}/_doc/{doc_id}",
        cluster=dst_cluster,
        method="PUT",
        json_data=init_doc
    )


def update_field_with_chunks(env: Environment, index: str, doc_id: str,
                             field_name: str, field_value: str,
                             max_request_size: int,
                             dst_cluster: str = DST_CLUSTER) -> None:
    """Updates a document field with chunks of the field value."""
    # Split the value into chunks
    chunks = [field_value[i:i + max_request_size] for i in range(0, len(field_value), max_request_size)]

    logger.info(f"Updating field '{field_name}' in {len(chunks)} chunks")

    for i, chunk in enumerate(chunks, 1):
        logger.info(f"Sending chunk {i}/{len(chunks)} for field '{field_name}' ({len(chunk)} characters)")

        # Create the update payload
        payload = {
            "script": {
                "lang": "painless",
                "source": "if(ctx._source[params.field]==null){ctx._source[params.field]=\"\";}" +
                          "ctx._source[params.field]+=params.chunk;",
                "params": {
                    "field": field_name,
                    "chunk": chunk
                }
            }
        }

        # Update the document
        console_curl(
            env=env,
            path=f"/{index}/_update/{doc_id}",
            cluster=dst_cluster,
            method="POST",
            json_data=payload
        )


def verify_migration(env: Environment, src_index: str, src_id: str,
                     dst_index: str, dst_id: str, src_type: str,
                     src_source: Dict[str, Any] = None) -> bool:
    """Verifies that the source and target documents match."""
    try:
        # Get source document if not provided
        if src_source is None:
            src_source = get_document_source(env, src_index, src_id, src_type)

        # Get target document
        dst_source = get_document_source(env, dst_index, dst_id, DST_CLUSTER)

        # Calculate sizes for logging
        src_size = calculate_document_size(src_source)
        dst_size = calculate_document_size(dst_source)

        logger.info(f"Source document size: {src_size} bytes")
        logger.info(f"Target document size: {dst_size} bytes")

        # Compare documents with sorted keys
        src_sorted = json.dumps(src_source, sort_keys=True)
        dst_sorted = json.dumps(dst_source, sort_keys=True)

        if src_sorted == dst_sorted:
            logger.info("Success: Source and target documents match")
            return True
        else:
            logger.error("Failure: Source and target documents differ")
            return False

    except Exception as e:
        logger.error(f"Error during verification: {str(e)}")
        return False


def main(env: Environment, args: argparse.Namespace) -> None:
    """Main function that executes the document migration."""
    src_index = args.index
    src_id = args.id
    src_type = args.source_type
    dst_index = args.target_index if args.target_index else src_index
    dst_id = args.target_id if args.target_id else src_id
    max_request_size = args.max_request_size

    logger.info(f"Migrating document '{src_id}' in index '{src_index}' from {src_type} to {DST_CLUSTER}")
    if dst_index != src_index:
        logger.info(f"Target index: {dst_index}")
    if dst_id != src_id:
        logger.info(f"Target document ID: {dst_id}")

    try:
        # 1. Get the document from the source cluster
        doc = get_document_source(env, src_index, src_id, src_type)

        # 2. Identify fields that need to be chunked
        fields_to_chunk = identify_fields_to_chunk(doc, max_request_size)

        if not fields_to_chunk:
            logger.info("Document doesn't need chunking, creating full document directly")
            console_curl(
                env=env,
                path=f"/{dst_index}/_doc/{dst_id}",
                cluster=DST_CLUSTER,
                method="PUT",
                json_data=doc
            )
        else:
            # 3. Create the initial document without the large fields
            create_initial_document(env, dst_index, dst_id, doc, fields_to_chunk, DST_CLUSTER)

            # 4. Update the document with chunks of each large field
            for field_name, field_value in fields_to_chunk:
                update_field_with_chunks(env, dst_index, dst_id, field_name, field_value, max_request_size, DST_CLUSTER)

        # 5. Verify the migration - pass the original document to avoid retrieving it again
        success = verify_migration(env, src_index, src_id, dst_index, dst_id, src_type, doc)

        if not success:
            raise RuntimeError("Document migration failed: Source and target documents do not match")

        logger.info("Document migration completed successfully")

    except Exception as e:
        logger.error(f"Document migration failed: {str(e)}")
        raise
