import argparse
import logging

import yaml

import endpoint_utils
import index_operations
import utils
from index_diff import IndexDiff
from metadata_migration_params import MetadataMigrationParams
from metadata_migration_result import MetadataMigrationResult

# Constants
INDICES_KEY = "indices"
INCLUDE_KEY = "include"
INDEX_NAME_KEY = "index_name_regex"


def write_output(yaml_data: dict, indices_to_migrate: set, output_path: str):
    pipeline_config = next(iter(yaml_data.values()))
    # Result is a tuple of (type, config)
    source_config = endpoint_utils.get_supported_endpoint_config(pipeline_config, endpoint_utils.SOURCE_KEY)[1]
    source_indices = source_config.get(INDICES_KEY, dict())
    included_indices = source_indices.get(INCLUDE_KEY, list())
    for index in indices_to_migrate:
        included_indices.append({INDEX_NAME_KEY: index})
    source_indices[INCLUDE_KEY] = included_indices
    source_config[INDICES_KEY] = source_indices
    with open(output_path, 'w') as out_file:
        yaml.dump(yaml_data, out_file)


def print_report(diff: IndexDiff, total_doc_count: int):  # pragma no cover
    logging.info("Identical indices in the target cluster: " + utils.string_from_set(diff.identical_indices))
    logging.info("Identical empty indices in the target cluster (data will be migrated): " +
                 utils.string_from_set(diff.identical_empty_indices))
    logging.info("Indices present in both clusters with conflicting settings/mappings (data will not be migrated): " +
                 utils.string_from_set(diff.conflicting_indices))
    logging.info("Indices to be created in the target cluster (data will be migrated): " +
                 utils.string_from_set(diff.indices_to_create))
    logging.info("Total number of documents to be moved: " + str(total_doc_count))


def run(args: MetadataMigrationParams) -> MetadataMigrationResult:
    # Sanity check
    if not args.report and len(args.output_file) == 0:
        raise ValueError("No output file specified")
    # Parse and validate pipelines YAML file
    with open(args.config_file_path, 'r') as pipeline_file:
        dp_config = yaml.safe_load(pipeline_file)
    # We expect the Data Prepper pipeline to only have a single top-level value
    pipeline_config = next(iter(dp_config.values()))
    # Raises a ValueError if source or sink definitions are missing
    endpoint_utils.validate_pipeline(pipeline_config)
    source_endpoint_info = endpoint_utils.get_endpoint_info_from_pipeline_config(pipeline_config,
                                                                                 endpoint_utils.SOURCE_KEY)
    target_endpoint_info = endpoint_utils.get_endpoint_info_from_pipeline_config(pipeline_config,
                                                                                 endpoint_utils.SINK_KEY)
    result = MetadataMigrationResult()
    # Fetch indices
    source_indices = index_operations.fetch_all_indices(source_endpoint_info)
    # If source indices is empty, return immediately
    if len(source_indices.keys()) == 0:
        return result
    target_indices = index_operations.fetch_all_indices(target_endpoint_info)
    # Compute index differences and print report
    diff = IndexDiff(source_indices, target_indices)
    if diff.identical_indices:
        # Identical indices with zero documents on the target are eligible for migration
        target_doc_count = index_operations.doc_count(diff.identical_indices, target_endpoint_info)
        # doc_count only returns indices that have non-zero counts, so the difference in responses
        # gives us the set of identical, empty indices
        result.migration_indices = diff.identical_indices.difference(target_doc_count.index_doc_count_map.keys())
        diff.set_identical_empty_indices(result.migration_indices)
    if diff.indices_to_create:
        result.migration_indices.update(diff.indices_to_create)
    if result.migration_indices:
        doc_count_result = index_operations.doc_count(result.migration_indices, source_endpoint_info)
        result.target_doc_count = doc_count_result.total
    if args.report:
        print_report(diff, result.target_doc_count)
    if result.migration_indices:
        # Write output YAML
        if len(args.output_file) > 0:
            write_output(dp_config, result.migration_indices, args.output_file)
            logging.debug("Wrote output YAML pipeline to: " + args.output_file)
        if not args.dryrun:
            index_data = dict()
            for index_name in diff.indices_to_create:
                index_data[index_name] = source_indices[index_name]
            index_operations.create_indices(index_data, target_endpoint_info)
    return result


if __name__ == '__main__':  # pragma no cover
    # Set up parsing for command line arguments
    arg_parser = argparse.ArgumentParser(
        prog="python metadata_migration.py",
        description="This tool creates indices on a target cluster based on the contents of a source cluster.\n" +
        "The first input to the tool is a path to a Data Prepper pipeline YAML file, which is parsed to obtain " +
        "the source and target cluster endpoints.\nThe second input is an output path to which a modified version " +
        "of the pipeline YAML file is written. This version of the pipeline adds an index inclusion configuration " +
        "to the sink, specifying only those indices that were created by the index configuration tool.\nThis tool " +
        "can also print a report based on the indices in the source cluster, indicating which ones will be created, " +
        "along with indices that are identical or have conflicting settings/mappings.",
        formatter_class=argparse.RawTextHelpFormatter
    )
    # Required positional argument
    arg_parser.add_argument(
        "config_file_path",
        help="Path to the Data Prepper pipeline YAML file to parse for source and target endpoint information"
    )
    # Optional positional argument
    arg_parser.add_argument(
        "output_file",
        nargs='?', default="",
        help="Output path for the Data Prepper pipeline YAML file that will be generated"
    )
    # Flags
    arg_parser.add_argument("--report", "-r", action="store_true",
                            help="Print a report of the index differences")
    arg_parser.add_argument("--dryrun", action="store_true",
                            help="Skips the actual creation of indices on the target cluster")
    namespace = arg_parser.parse_args()
    run(MetadataMigrationParams(namespace.config_file_path, namespace.output_file, namespace.report, namespace.dryrun))
