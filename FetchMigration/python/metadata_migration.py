import argparse

import yaml

import endpoint_utils
import index_operations
import utils
from metadata_migration_params import MetadataMigrationParams
from metadata_migration_result import MetadataMigrationResult

# Constants
INDICES_KEY = "indices"
INCLUDE_KEY = "include"
INDEX_NAME_KEY = "index_name_regex"


def write_output(yaml_data: dict, new_indices: set, output_path: str):
    pipeline_config = next(iter(yaml_data.values()))
    # Result is a tuple of (type, config)
    source_config = endpoint_utils.get_supported_endpoint_config(pipeline_config, endpoint_utils.SOURCE_KEY)[1]
    source_indices = source_config.get(INDICES_KEY, dict())
    included_indices = source_indices.get(INCLUDE_KEY, list())
    for index in new_indices:
        included_indices.append({INDEX_NAME_KEY: index})
    source_indices[INCLUDE_KEY] = included_indices
    source_config[INDICES_KEY] = source_indices
    with open(output_path, 'w') as out_file:
        yaml.dump(yaml_data, out_file)


# Computes differences in indices between source and target.
# Returns a tuple with 3 elements:
# - The 1st element is the set of indices to create on the target
# - The 2nd element is a set of indices that are identical on source and target
# - The 3rd element is a set of indices that are present on both source and target,
# but differ in their settings or mappings.
def get_index_differences(source: dict, target: dict) -> tuple[set, set, set]:
    index_conflicts = set()
    indices_in_target = set(source.keys()) & set(target.keys())
    for index in indices_in_target:
        # Check settings
        if utils.has_differences(index_operations.SETTINGS_KEY, source[index], target[index]):
            index_conflicts.add(index)
        # Check mappings
        if utils.has_differences(index_operations.MAPPINGS_KEY, source[index], target[index]):
            index_conflicts.add(index)
    identical_indices = set(indices_in_target) - set(index_conflicts)
    indices_to_create = set(source.keys()) - set(indices_in_target)
    return indices_to_create, identical_indices, index_conflicts


# The order of data in the tuple is:
# (indices to create), (identical indices), (indices with conflicts)
def print_report(index_differences: tuple[set, set, set], count: int):  # pragma no cover
    print("Identical indices in the target cluster (no changes will be made): " +
          utils.string_from_set(index_differences[1]))
    print("Indices in target cluster with conflicting settings/mappings: " +
          utils.string_from_set(index_differences[2]))
    print("Indices to create: " + utils.string_from_set(index_differences[0]))
    print("Total documents to be moved: " + str(count))


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
    diff = get_index_differences(source_indices, target_indices)
    # The first element in the tuple is the set of indices to create
    indices_to_create = diff[0]
    if indices_to_create:
        result.created_indices = indices_to_create
        doc_count_result = index_operations.doc_count(indices_to_create, source_endpoint_info)
        result.target_doc_count = doc_count_result.total
    if args.report:
        print_report(diff, doc_count_result.total)
    if indices_to_create:
        # Write output YAML
        if len(args.output_file) > 0:
            write_output(dp_config, indices_to_create, args.output_file)
            if args.report:  # pragma no cover
                print("Wrote output YAML pipeline to: " + args.output_file)
        if not args.dryrun:
            index_data = dict()
            for index_name in indices_to_create:
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
