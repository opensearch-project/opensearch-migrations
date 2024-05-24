#!/usr/bin/env python3

from pathlib import Path
from typing import List, Dict
from urllib.parse import urlparse
import argparse
import logging
import coloredlogs
import boto3
import os
import re

logger = logging.getLogger(__name__)
DEFAULT_PIPELINE_NAME = "migration-assistant-pipeline"
DEFAULT_PIPELINE_CONFIG_PATH = "./osiPipelineTemplate.yaml"

AWS_SECRET_CONFIG_PLACEHOLDER = "<AWS_SECRET_CONFIG_PLACEHOLDER>"
INDEX_SELECTION_OPTIONS_PLACEHOLDER = "<INDEX_SELECTION_OPTIONS_PLACEHOLDER>"
SOURCE_ENDPOINT_PLACEHOLDER = "<SOURCE_CLUSTER_ENDPOINT_PLACEHOLDER>"
TARGET_ENDPOINT_PLACEHOLDER = "<TARGET_CLUSTER_ENDPOINT_PLACEHOLDER>"
SOURCE_AUTH_OPTIONS_PLACEHOLDER = "<SOURCE_AUTH_OPTIONS_PLACEHOLDER>"
TARGET_AUTH_OPTIONS_PLACEHOLDER = "<TARGET_AUTH_OPTIONS_PLACEHOLDER>"
SOURCE_BASIC_AUTH_CONFIG_TEMPLATE = """
      username: "${{aws_secrets:source-secret-config:username}}"
      password: "${{aws_secrets:source-secret-config:password}}"
"""
SOURCE_DEFAULT_INDEX_TEMPLATE = """
        exclude:
          - index_name_regex: \.*
"""


class MissingEnvironmentVariable(Exception):
    pass


class InvalidAuthParameters(Exception):
    pass


# Custom action to parse argument into a list of dictionaries with two named fields
class KeyValueAction(argparse.Action):
    def __call__(self, parser, namespace, values, option_string=None):
        tags = getattr(namespace, self.dest, [])
        if tags is None:
            tags = []
        for value in values:
            key, value = value.split('=')
            tags.append({'Key': key, 'Value': value})
        setattr(namespace, self.dest, tags)


# Custom action to parse a potentially comma-separated string into a list
class CommaSeparatedListAction(argparse.Action):
    def __call__(self, parser, namespace, values, option_string=None):
        setattr(namespace, self.dest, values.split(','))


def _initialize_logging():
    root_logger = logging.getLogger()
    root_logger.handlers = []  # Make sure we're starting with a clean slate
    root_logger.setLevel(logging.DEBUG)

    # Send INFO+ level logs to stdout, and enable colorized messages
    console_handler = logging.StreamHandler()
    console_handler.setLevel(logging.INFO)
    console_formatter = coloredlogs.ColoredFormatter('%(asctime)s [%(levelname)s] %(message)s')
    console_handler.setFormatter(console_formatter)
    root_logger.addHandler(console_handler)


def validate_environment():
    env_vars = ['AWS_REGION', 'MIGRATION_SOLUTION_VERSION', 'MIGRATION_STAGE', 'MIGRATION_DOMAIN_ENDPOINT']
    for i in env_vars:
        if os.environ.get(i) is None:
            raise MissingEnvironmentVariable(f"Required environment variable '{i}' not found")


# Allowing ability to remove port, as port is currently not supported by OpenSearch Ingestion
def sanitize_endpoint(endpoint: str, remove_port: bool):
    url = urlparse(endpoint)
    scheme = url.scheme
    hostname = url.hostname
    port = url.port
    if scheme is None or hostname is None:
        raise RuntimeError(f"Provided endpoint does not have proper endpoint formatting: {endpoint}")

    if port is not None and not remove_port:
        return f"{scheme}://{hostname}:{port}"

    return f"{scheme}://{hostname}"


def parse_args():
    pipeline_name_description = 'The name of the OSI pipeline'
    parser = argparse.ArgumentParser(description="Script to control migration OSI pipeline operations. Note: This tool "
                                                 "is still in an experimental state and currently being developed")
    subparsers = parser.add_subparsers(dest="subcommand")
    parser_start_command = subparsers.add_parser('start-pipeline', help='Operation to start a given OSI pipeline')
    parser_start_command.add_argument('--name', type=str, help=pipeline_name_description,
                                      default=DEFAULT_PIPELINE_NAME)
    parser_stop_command = subparsers.add_parser('stop-pipeline', help='Operation to stop a given OSI pipeline')
    parser_stop_command.add_argument('--name', type=str, help=pipeline_name_description,
                                     default=DEFAULT_PIPELINE_NAME)

    include_index_arg_help = ('Regex string for selecting indices that should be migrated from source to target, '
                              'e.g. \'index-.*\'. Argument can be used multiple times.')
    include_index_arg = {'arg_name': '--include-index-regex', 'arg_settings': {'type': str, 'action': 'append',
                                                                               'help': include_index_arg_help}}
    create_command = subparsers.add_parser('create-pipeline',
                                           help='Operation to create an OSI pipeline')
    create_command.add_argument('--source-endpoint',
                                type=str,
                                help='The source cluster endpoint to use for the OSI pipeline',
                                required=True)
    create_command.add_argument('--target-endpoint',
                                type=str,
                                help='The target cluster endpoint to use for the OSI pipeline',
                                required=True)
    create_command.add_argument('--name',
                                type=str,
                                help=pipeline_name_description,
                                default=DEFAULT_PIPELINE_NAME)
    create_command.add_argument('--aws-region',
                                type=str,
                                help='The name of the AWS region for the OSI pipeline to fetch Secrets and IAM roles, '
                                     'e.g. us-east-1',
                                required=True)
    create_command.add_argument('--subnet-ids',
                                type=str,
                                action=CommaSeparatedListAction,
                                help='A comma delimited list of subnet-ids for the OSI pipeline to use, '
                                     'e.g. subnet-1234,subnet-5678',
                                required=True)
    create_command.add_argument('--security-group-ids',
                                type=str,
                                action=CommaSeparatedListAction,
                                help='A comma delimited list of security group ids for the OSI pipeline to use, '
                                     'e.g. sg-1234abc,sg-4567def. These should allow the OSI pipeline to make requests '
                                     'to both the source and target clusters',
                                required=True)
    create_command.add_argument('--source-auth-type',
                                type=str,
                                help='The auth type to use when making requests from the OSI pipeline to the source '
                                     'cluster',
                                choices=['BASIC_AUTH', 'SIGV4'],
                                required=True)
    create_command.add_argument('--target-auth-type',
                                type=str,
                                help='The auth type to use when making requests from the OSI pipeline to the '
                                     'target cluster',
                                choices=['SIGV4'],
                                required=True)
    create_command.add_argument('--pipeline-role-arn',
                                type=str,
                                help='The ARN of the IAM role that the OSI pipeline will use to read from a source '
                                     'cluster and write to a target cluster ',
                                required=True)
    create_command.add_argument('--source-auth-secret',
                                type=str,
                                help='The AWS Secrets Manager Secret containing the \'username\' and \'password\' keys '
                                     'for the OSI pipeline to use when communicating with the source cluster')
    create_command.add_argument(include_index_arg['arg_name'], **include_index_arg['arg_settings'])
    create_command.add_argument('--log-group-name',
                                type=str,
                                help='The name of an existing Cloudwatch Log Group for OSI to publish logs to. '
                                     'This is required to enable logging')
    create_command.add_argument('--tag',
                                nargs='+',
                                action=KeyValueAction,
                                metavar='Key=Value',
                                help='Tag to apply to the created OSI pipeline, e.g. migration_deployment=1.0.3. '
                                     'Argument can be used multiple times')
    create_command.add_argument("--print-config-only",
                                action="store_true",
                                help="Flag to only output the pipeline config template that is generated")
    create_command_solution_help = ('Operation to detect source and target fields from full Migration deployment and '
                                    'create an OSI pipeline. Note: Only configured for managed OpenSearch Service to '
                                    'managed OpenSearch Service migrations currently')
    create_command_solution = subparsers.add_parser('create-pipeline-from-solution',
                                                    help=create_command_solution_help)
    create_command_solution.add_argument('--source-endpoint',
                                         type=str,
                                         help='The source cluster endpoint to use for the OSI pipeline',
                                         required=True)
    create_command_solution.add_argument('--name',
                                         type=str,
                                         help=pipeline_name_description,
                                         default=DEFAULT_PIPELINE_NAME)
    create_command_solution.add_argument(include_index_arg['arg_name'],
                                         **include_index_arg['arg_settings'])
    create_command_solution.add_argument("--print-config-only",
                                         action="store_true",
                                         help="Flag to only output the pipeline config template "
                                              "that is generated")
    create_command_solution.add_argument("--print-command-only",
                                         action="store_true",
                                         help="Flag to only output the equivalent create-pipeline command that is "
                                              "generated")
    return parser.parse_args()


def get_private_subnets(vpc_id):
    ec2 = boto3.client('ec2')

    response = ec2.describe_subnets(Filters=[{'Name': 'vpc-id', 'Values': [vpc_id]}])
    subnets = response['Subnets']

    private_subnets = []

    for subnet in subnets:
        response = ec2.describe_route_tables(
            Filters=[{'Name': 'association.subnet-id', 'Values': [subnet['SubnetId']]}])
        for route_table in response['RouteTables']:
            for route in route_table['Routes']:
                if route.get('NatGatewayId'):
                    private_subnets.append(subnet['SubnetId'])
                    break  # No need to check other route tables for this subnet
                else:
                    continue  # Only executed if the inner loop did NOT break
            break  # No need to check other route tables for this subnet

    return private_subnets


def generate_source_secret_config(source_auth_secret, pipeline_role_arn, aws_region):
    return f"""
pipeline_configurations:
  aws:
    secrets:
      source-secret-config:
        secret_id: {source_auth_secret}
        region: {aws_region}
        sts_role_arn: {pipeline_role_arn}
""".strip()


def generate_source_sigv4_auth_config(pipeline_role_arn, aws_region):
    return f"""
      aws:
        region: {aws_region}
        sts_role_arn: {pipeline_role_arn}
""".strip()


def generate_target_sigv4_auth_config(pipeline_role_arn, aws_region):
    return f"""
        aws:
          region: {aws_region}
          sts_role_arn: {pipeline_role_arn}
""".strip()


def generate_source_index_config(include_index_regex_list=None):
    if include_index_regex_list is not None:
        include_template_str = "include:\n"
        for regex_str in include_index_regex_list:
            include_template_str = include_template_str + f"          - index_name_regex: {regex_str}\n"
        # Return template after removing last new line character
        return include_template_str[:-1]
    else:
        # Return template after removing any leading or trailing new line
        return SOURCE_DEFAULT_INDEX_TEMPLATE.strip()


def validate_pipeline_config_arguments(source_auth_type: str, target_auth_type: str, source_auth_secret=None,
                                       pipeline_role_arn=None, include_index_regex_list=None, aws_region=None):
    # Validation of auth options provided
    if aws_region is None and (source_auth_type == 'SIGV4' or target_auth_type == 'SIGV4'):
        raise InvalidAuthParameters('AWS region must be provided for a source or target auth type of SIGV4')

    if pipeline_role_arn is None and source_auth_type == 'SIGV4':
        raise InvalidAuthParameters('Source pipeline role ARN must be provided for an auth type of SIGV4')

    if pipeline_role_arn is None and target_auth_type == 'SIGV4':
        raise InvalidAuthParameters('Target pipeline role ARN must be provided for an auth type of SIGV4')

    if (source_auth_secret is None or pipeline_role_arn is None) and source_auth_type == 'BASIC_AUTH':
        raise InvalidAuthParameters('Source auth secret and pipeline role ARN to access secret, must be provided '
                                    'for an auth type of BASIC_AUTH')

    if include_index_regex_list is not None:
        for regex_string in include_index_regex_list:
            try:
                re.compile(regex_string)
            except Exception as e:
                logger.error(f"Unable to compile provided index inclusion regex string: {regex_string}")
                raise e


def construct_pipeline_config(pipeline_config_file_path: str, source_endpoint: str, target_endpoint: str,
                              source_auth_type: str, target_auth_type: str, source_auth_secret=None,
                              pipeline_role_arn=None, include_index_regex_list=None, aws_region=None):

    validate_pipeline_config_arguments(source_auth_type=source_auth_type, target_auth_type=target_auth_type,
                                       source_auth_secret=source_auth_secret, pipeline_role_arn=pipeline_role_arn,
                                       include_index_regex_list=include_index_regex_list, aws_region=aws_region)
    pipeline_config = Path(pipeline_config_file_path).read_text()

    # Fill in index selection config
    index_config = generate_source_index_config(include_index_regex_list)
    pipeline_config = pipeline_config.replace(INDEX_SELECTION_OPTIONS_PLACEHOLDER, index_config)

    # Fill in OSI pipeline template file from provided options
    if source_auth_type == 'BASIC_AUTH':
        secret_config = generate_source_secret_config(source_auth_secret, pipeline_role_arn, aws_region)
        pipeline_config = pipeline_config.replace(AWS_SECRET_CONFIG_PLACEHOLDER, secret_config)
        pipeline_config = pipeline_config.replace(SOURCE_AUTH_OPTIONS_PLACEHOLDER,
                                                  SOURCE_BASIC_AUTH_CONFIG_TEMPLATE.strip())
    else:
        pipeline_config = pipeline_config.replace(AWS_SECRET_CONFIG_PLACEHOLDER, "")

    if source_auth_type == 'SIGV4':
        aws_source_config = generate_source_sigv4_auth_config(pipeline_role_arn, aws_region)
        pipeline_config = pipeline_config.replace(SOURCE_AUTH_OPTIONS_PLACEHOLDER, aws_source_config)

    if target_auth_type == 'SIGV4':
        aws_target_config = generate_target_sigv4_auth_config(pipeline_role_arn, aws_region)
        pipeline_config = pipeline_config.replace(TARGET_AUTH_OPTIONS_PLACEHOLDER, aws_target_config)

    pipeline_config = pipeline_config.replace(SOURCE_ENDPOINT_PLACEHOLDER, source_endpoint)
    pipeline_config = pipeline_config.replace(TARGET_ENDPOINT_PLACEHOLDER, target_endpoint)
    return pipeline_config


def start_pipeline(osi_client, pipeline_name: str):
    logger.info(f"Starting pipeline: {pipeline_name}")
    osi_client.start_pipeline(
        PipelineName=pipeline_name
    )


def stop_pipeline(osi_client, pipeline_name: str):
    logger.info(f"Stopping pipeline: {pipeline_name}")
    osi_client.stop_pipeline(
        PipelineName=pipeline_name
    )


def osi_create_pipeline(osi_client, pipeline_name: str, pipeline_config: str, subnet_ids: List[str],
                        security_group_ids: List[str], cw_log_group_name: str, tags: List[Dict[str, str]]):
    logger.info(f"Creating pipeline: {pipeline_name}")
    cw_log_options = {
        'IsLoggingEnabled': False
    }
    # Currently limited that CW log groups must already be created and follow naming pattern
    # /aws/vendedlogs/<name>
    if cw_log_group_name is not None:
        cw_log_options = {
            'IsLoggingEnabled': True,
            'CloudWatchLogDestination': {
                'LogGroup': cw_log_group_name
            }
        }

    osi_client.create_pipeline(
        PipelineName=pipeline_name,
        MinUnits=2,
        MaxUnits=4,
        PipelineConfigurationBody=pipeline_config,
        LogPublishingOptions=cw_log_options,
        VpcOptions={
            'SubnetIds': subnet_ids,
            'SecurityGroupIds': security_group_ids
        },
        # Documentation lists this as a requirement but does not seem to be the case from testing:
        # https://boto3.amazonaws.com/v1/documentation/api/latest/reference/services/osis/client/create_pipeline.html
        # BufferOptions={
        #    'PersistentBufferEnabled': False
        # },
        Tags=tags
    )


def create_pipeline(osi_client, pipeline_name: str, pipeline_config: str, subnet_ids: List[str],
                    security_group_ids: List[str], cw_log_group_name: str, tags: List[Dict[str, str]]):
    tags_clean = [] if tags is None else tags
    osi_create_pipeline(osi_client, pipeline_name, pipeline_config, subnet_ids, security_group_ids, cw_log_group_name,
                        tags_clean)


def create_pipeline_from_stage(osi_client, pipeline_name: str, pipeline_config_path: str, source_endpoint: str,
                               target_endpoint: str, include_index_regex_list: List[str], print_config_only: bool,
                               print_command_only: bool):
    region = os.environ.get("AWS_REGION")
    solution_version = os.environ.get("MIGRATION_SOLUTION_VERSION")
    stage = os.environ.get("MIGRATION_STAGE")
    vpc_id_key = f"/migration/{stage}/default/vpcId"
    target_sg_id_key = f"/migration/{stage}/default/osAccessSecurityGroupId"
    source_sg_id_key = f"/migration/{stage}/default/serviceConnectSecurityGroupId"
    osi_pipeline_role_key = f"/migration/{stage}/default/osiPipelineRoleArn"
    osi_log_group_key = f"/migration/{stage}/default/osiPipelineLogGroupName"
    ssm_client = boto3.client('ssm')
    param_response = ssm_client.get_parameters(Names=[vpc_id_key, target_sg_id_key, source_sg_id_key,
                                                      osi_pipeline_role_key, osi_log_group_key])
    parameters = param_response['Parameters']
    param_dict = {}
    for param in parameters:
        param_dict[param['Name']] = param['Value']
    log_group_name = param_dict[osi_log_group_key]
    vpc_id = param_dict[vpc_id_key]
    security_groups = [param_dict[target_sg_id_key], param_dict[source_sg_id_key]]
    subnet_ids = get_private_subnets(vpc_id)
    pipeline_role_arn = param_dict[osi_pipeline_role_key]
    tags = [{'Key': 'migration_deployment', 'Value': solution_version}]

    pipeline_config = construct_pipeline_config(pipeline_config_file_path=pipeline_config_path, aws_region=region,
                                                source_endpoint=source_endpoint, target_endpoint=target_endpoint,
                                                source_auth_type='SIGV4', target_auth_type='SIGV4',
                                                include_index_regex_list=include_index_regex_list,
                                                pipeline_role_arn=pipeline_role_arn)

    if print_config_only:
        print(pipeline_config)
        exit(0)

    if print_command_only:
        command_str = (f"./osiMigration.py create-pipeline "
                       f"--source-endpoint={source_endpoint} "
                       f"--target-endpoint={target_endpoint} "
                       f"--aws-region={region} "
                       f"--subnet-ids={','.join(map(str,subnet_ids))} "  # noqa: E231
                       f"--security-group-ids={','.join(map(str,security_groups))} "  # noqa: E231
                       f"--source-auth-type='SIGV4' "
                       f"--target-auth-type='SIGV4' "
                       f"--pipeline-role-arn={pipeline_role_arn} "
                       f"--tag=migration_deployment={solution_version} "
                       f"--log-group-name={log_group_name}")
        print(command_str)
        exit(0)

    osi_create_pipeline(osi_client=osi_client, pipeline_name=pipeline_name, pipeline_config=pipeline_config,
                        subnet_ids=subnet_ids, security_group_ids=security_groups,
                        cw_log_group_name=log_group_name, tags=tags)


if __name__ == "__main__":
    args = parse_args()
    _initialize_logging()
    logger.info(f"Incoming args: {args}")

    client = boto3.client('osis')

    if args.subcommand == "start-pipeline":
        start_pipeline(client, args.name)
    elif args.subcommand == "stop-pipeline":
        stop_pipeline(client, args.name)
    elif args.subcommand == "create-pipeline":
        source_endpoint_clean = sanitize_endpoint(args.source_endpoint, False)
        target_endpoint_clean = sanitize_endpoint(args.target_endpoint, True)
        pipeline_config_string = construct_pipeline_config(pipeline_config_file_path=DEFAULT_PIPELINE_CONFIG_PATH,
                                                           source_endpoint=source_endpoint_clean,
                                                           source_auth_type=args.source_auth_type,
                                                           source_auth_secret=args.source_auth_secret,
                                                           target_endpoint=target_endpoint_clean,
                                                           target_auth_type=args.target_auth_type,
                                                           pipeline_role_arn=args.pipeline_role_arn,
                                                           include_index_regex_list=args.include_index_regex,
                                                           aws_region=args.aws_region)
        if args.print_config_only:
            print(pipeline_config_string)
            exit(0)
        create_pipeline(osi_client=client, pipeline_name=args.name, pipeline_config=pipeline_config_string,
                        subnet_ids=args.subnet_ids, security_group_ids=args.security_group_ids,
                        cw_log_group_name=args.log_group_name, tags=args.tag)
    elif args.subcommand == "create-pipeline-from-solution":
        validate_environment()
        source_endpoint_clean = sanitize_endpoint(args.source_endpoint, False)
        target_endpoint_clean = sanitize_endpoint(os.environ.get("MIGRATION_DOMAIN_ENDPOINT"), True)
        create_pipeline_from_stage(osi_client=client,
                                   pipeline_name=args.name,
                                   pipeline_config_path=DEFAULT_PIPELINE_CONFIG_PATH,
                                   source_endpoint=source_endpoint_clean,
                                   target_endpoint=target_endpoint_clean,
                                   include_index_regex_list=args.include_index_regex,
                                   print_config_only=args.print_config_only,
                                   print_command_only=args.print_command_only)
