#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

import argparse
import logging

import yaml

import endpoint_utils
import index_management
import utils
from endpoint_info import EndpointInfo
from exceptions import MetadataMigrationError
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
    logging.info("Identical empty indices in the target cluster (will be migrated): " +
                 utils.string_from_set(diff.identical_empty_indices))
    logging.info("Indices present in both clusters with conflicting settings/mappings (will NOT be migrated): " +
                 utils.string_from_set(diff.conflicting_indices))
    logging.info("Indices to be created in the target cluster (will be migrated): " +
                 utils.string_from_set(diff.indices_to_create))
    logging.info("Target document count: " + str(total_doc_count))


def index_metadata_migration(source: EndpointInfo, target: EndpointInfo,
                             args: MetadataMigrationParams) -> MetadataMigrationResult:
    result = MetadataMigrationResult()
    # Fetch indices
    source_indices = index_management.fetch_all_indices(source)
    # If source indices is empty, return immediately
    if len(source_indices.keys()) == 0:
        return result
    target_indices = index_management.fetch_all_indices(target)
    # Compute index differences and create result object
    diff = IndexDiff(source_indices, target_indices)
    if diff.identical_indices:
        # Identical indices with zero documents on the target are eligible for migration
        target_doc_count = index_management.doc_count(diff.identical_indices, target)
        # doc_count only returns indices that have non-zero counts, so the difference in responses
        # gives us the set of identical, empty indices
        result.migration_indices = diff.identical_indices.difference(target_doc_count.index_doc_count_map.keys())
        diff.set_identical_empty_indices(result.migration_indices)
    if diff.indices_to_create:
        result.migration_indices.update(diff.indices_to_create)
    if result.migration_indices:
        doc_count_result = index_management.doc_count(result.migration_indices, source)
        result.target_doc_count = doc_count_result.total
    # Print report
    if args.report:
        print_report(diff, result.target_doc_count)
    # Create index metadata on target
    if result.migration_indices and not args.dryrun:
        index_data = dict()
        for index_name in diff.indices_to_create:
            index_data[index_name] = source_indices[index_name]
        failed_indices = index_management.create_indices(index_data, target)
        fail_count = len(failed_indices)
        if fail_count > 0:
            logging.error(f"Failed to create {fail_count} of {len(index_data)} indices")
            for failed_index_name, error in failed_indices.items():
                logging.error(f"Index name {failed_index_name} failed: {error!s}")
            raise MetadataMigrationError("Metadata migration failed, index creation unsuccessful")
    return result


# Returns true if there were failures, false otherwise
def __log_template_failures(failures: dict, target_count: int) -> bool:
    fail_count = len(failures)
    if fail_count > 0:
        logging.error(f"Failed to create {fail_count} of {target_count} templates")
        for failed_template_name, error in failures.items():
            logging.error(f"Template name {failed_template_name} failed: {error!s}")
        # Return true to signal failures
        return True
    else:
        # No failures, return false
        return False


# Raises RuntimeError if component/index template migration fails
def template_migration(source: EndpointInfo, target: EndpointInfo):
    # Fetch and migrate component templates first
    templates = index_management.fetch_all_component_templates(source)
    failures = index_management.create_component_templates(templates, target)
    if not __log_template_failures(failures, len(templates)):
        # Only migrate index templates if component template migration had no failures
        templates = index_management.fetch_all_index_templates(source)
        failures = index_management.create_index_templates(templates, target)
        if __log_template_failures(failures, len(templates)):
            raise MetadataMigrationError("Failed to create some index templates")
    else:
        raise MetadataMigrationError("Failed to create some component templates, aborting index template creation")


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
    result = index_metadata_migration(source_endpoint_info, target_endpoint_info, args)
    # Write output YAML
    if result.migration_indices and len(args.output_file) > 0:
        write_output(dp_config, result.migration_indices, args.output_file)
        logging.debug("Wrote output YAML pipeline to: " + args.output_file)
    if not args.dryrun:
        # Create component and index templates, may raise RuntimeError
        template_migration(source_endpoint_info, target_endpoint_info)
    # Finally return result
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
                            help="Skips the actual creation of metadata on the target cluster")
    namespace = arg_parser.parse_args()
    run(MetadataMigrationParams(namespace.config_file_path, namespace.output_file, namespace.report, namespace.dryrun))
