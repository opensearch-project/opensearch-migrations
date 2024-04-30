#!/usr/bin/env python3

from pathlib import Path
from typing import List, Dict
import argparse
import logging
import coloredlogs
import boto3
import os

logger = logging.getLogger(__name__)
DEFAULT_PIPELINE_NAME = "migration-assistant-pipeline"
DEFAULT_PIPELINE_CONFIG_PATH = "./osiPipelineTemplate.yaml"
AWS_SECRET_CONFIG_PLACEHOLDER = "<AWS_SECRET_CONFIG_PLACEHOLDER>"
SOURCE_ENDPOINT_PLACEHOLDER = "<SOURCE_CLUSTER_ENDPOINT_PLACEHOLDER>"
TARGET_ENDPOINT_PLACEHOLDER = "<TARGET_CLUSTER_ENDPOINT_PLACEHOLDER>"
SOURCE_AUTH_OPTIONS_PLACEHOLDER = "<SOURCE_AUTH_OPTIONS_PLACEHOLDER>"
TARGET_AUTH_OPTIONS_PLACEHOLDER = "<TARGET_AUTH_OPTIONS_PLACEHOLDER>"
AWS_REGION_PLACEHOLDER = "<AWS_REGION_PLACEHOLDER>"
SOURCE_STS_ROLE_ARN_PLACEHOLDER = "<SOURCE_STS_ROLE_ARN_PLACEHOLDER>"
SOURCE_SECRET_ID_PLACEHOLDER = "<SOURCE_SECRET_ID_PLACEHOLDER>"
TARGET_STS_ROLE_ARN_PLACEHOLDER = "<TARGET_STS_ROLE_ARN_PLACEHOLDER>"
SECRET_CONFIG_TEMPLATE = """
pipeline_configurations:
  aws:
    secrets:
      source-secret-config:
        secret_id: <SOURCE_SECRET_ID_PLACEHOLDER>
        region: <AWS_REGION_PLACEHOLDER>
        sts_role_arn: <SOURCE_STS_ROLE_ARN_PLACEHOLDER>
"""
SOURCE_BASIC_AUTH_CONFIG_TEMPLATE = """
      username: "${{aws_secrets:source-secret-config:username}}"
      password: "${{aws_secrets:source-secret-config:password}}"
"""
SIGV4_AUTH_CONFIG_TEMPLATE = """
      aws:
        region: <AWS_REGION_PLACEHOLDER>
        sts_role_arn: <TARGET_STS_ROLE_ARN_PLACEHOLDER>
"""


class MissingEnvironmentVariable(Exception):
    pass


class InvalidAuthParameters(Exception):
    pass


# Custom action to parse argument into a list of dictionaries with two named fields
class KeyValueAction(argparse.Action):
    def __call__(self, parser, namespace, values, option_string=None):
        tag_list = getattr(namespace, self.dest, [])
        if tag_list is None:
            tag_list = []
        tag_list.append({'Key': values[0], 'Value': values[1]})
        setattr(namespace, self.dest, tag_list)


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


def parse_args():
    parser = argparse.ArgumentParser(description="Script to control migration OSI pipeline operations. Note: This tool "
                                                 "is still in an experimental state and currently being developed")
    subparsers = parser.add_subparsers(dest="subcommand")
    parser_start_command = subparsers.add_parser('start-pipeline', help='Operation to start a given OSI pipeline')
    parser_start_command.add_argument('--name', type=str, help='The name of the OSI pipeline',
                                      default=DEFAULT_PIPELINE_NAME)
    parser_stop_command = subparsers.add_parser('stop-pipeline', help='Operation to stop a given OSI pipeline')
    parser_stop_command.add_argument('--name', type=str, help='The name of the OSI pipeline',
                                     default=DEFAULT_PIPELINE_NAME)

    parser_create_pipeline_command = subparsers.add_parser('create-pipeline',
                                                           help='Operation to create an OSI pipeline')
    parser_create_pipeline_command.add_argument('--source-endpoint',
                                                type=str,
                                                help='The source cluster endpoint to use for the OSI pipeline',
                                                required=True)
    parser_create_pipeline_command.add_argument('--target-endpoint',
                                                type=str,
                                                help='The target cluster endpoint to use for the OSI pipeline',
                                                required=True)
    parser_create_pipeline_command.add_argument('--name',
                                                type=str,
                                                help='The name of the OSI pipeline',
                                                default=DEFAULT_PIPELINE_NAME)
    parser_create_pipeline_command.add_argument('--aws-region',
                                                type=str,
                                                help='The name of the AWS region for the OSI pipeline to fetch Secrets and IAM roles, e.g. us-east-1',
                                                required=True)
    parser_create_pipeline_command.add_argument('--subnet-ids',
                                                type=str,
                                                action=CommaSeparatedListAction,
                                                help='A comma delimited list of subnet-ids for the OSI pipeline to use, e.g. subnet-1234,subnet-5678',
                                                required=True)
    parser_create_pipeline_command.add_argument('--security-group-ids',
                                                type=str,
                                                action=CommaSeparatedListAction,
                                                help='A comma delimited list of security group ids for the OSI pipeline to use, e.g. sg-1234abc,sg-4567def. These should allow the OSI pipeline to make requests to both the source and target clusters',
                                                required=True)
    parser_create_pipeline_command.add_argument('--source-auth-type',
                                                type=str,
                                                help='The auth type to use when making requests from the OSI pipeline to the source cluster',
                                                choices=['BASIC_AUTH', 'SIGV4'],
                                                required=True)
    parser_create_pipeline_command.add_argument('--target-auth-type',
                                                type=str,
                                                help='The auth type to use when making requests from the OSI pipeline to the target cluster',
                                                choices=['SIGV4'],
                                                required=True)
    parser_create_pipeline_command.add_argument('--source-auth-secret',
                                                type=str,
                                                help='The AWS Secrets Manager Secret containing the \'username\' and \'password\' keys for the OSI pipeline to use when communicating with the source cluster')
    parser_create_pipeline_command.add_argument('--source-pipeline-role-arn',
                                                type=str,
                                                help='The ARN of the IAM role that the OSI pipeline should assume when communicating with the source cluster')
    parser_create_pipeline_command.add_argument('--target-pipeline-role-arn',
                                                type=str,
                                                help='The ARN of the IAM role that the OSI pipeline should assume when communicating with the target cluster')
    parser_create_pipeline_command.add_argument('--log-group-name',
                                                type=str,
                                                help='The name of an existing Cloudwatch Log Group for OSI to publish logs to. This is required to enable logging')
    parser_create_pipeline_command.add_argument('--tag',
                                                nargs=2,
                                                action=KeyValueAction,
                                                metavar=('Key', 'Value'),
                                                help='Tag to apply to the created OSI pipeline, e.g. migration_deployment 1.0.3. Argument can be used multiple times')
    parser_create_pipeline_command.add_argument("--print-config-only",
                                                action="store_true",
                                                help="Flag to only output the pipeline config template that is generated")
    parser_create_pipeline_from_solution_command = subparsers.add_parser('create-pipeline-from-solution',
                                                                         help='Operation to detect source and target fields from full Migration deployment and create an OSI pipeline. Note: Only configured for managed OpenSearch Service to managed OpenSearch Service migrations currently')
    parser_create_pipeline_from_solution_command.add_argument('--source-endpoint',
                                                              type=str,
                                                              help='The source cluster endpoint to use for the OSI pipeline',
                                                              required=True)
    parser_create_pipeline_from_solution_command.add_argument('--name',
                                                              type=str,
                                                              help='The name of the OSI pipeline',
                                                              default=DEFAULT_PIPELINE_NAME)
    parser_create_pipeline_from_solution_command.add_argument("--print-config-only",
                                                action="store_true",
                                                help="Flag to only output the pipeline config template that is generated")
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


def construct_pipeline_config(pipeline_config_file_path: str, source_endpoint: str, target_endpoint: str,
                              source_auth_type: str, target_auth_type: str, source_auth_secret=None,
                              source_pipeline_role_arn=None, target_pipeline_role_arn=None, aws_region=None):

    # Validation of auth options provided
    if aws_region is None and (source_auth_type == 'SIGV4' or target_auth_type == 'SIGV4'):
        raise InvalidAuthParameters('AWS region must be provided for a source or target auth type of SIGV4')

    if source_pipeline_role_arn is None and source_auth_type == 'SIGV4':
        raise InvalidAuthParameters('Source pipeline role ARN must be provided for an auth type of SIGV4')

    if target_pipeline_role_arn is None and target_auth_type == 'SIGV4':
        raise InvalidAuthParameters('Target pipeline role ARN must be provided for an auth type of SIGV4')

    if (source_auth_secret is None or source_pipeline_role_arn is None) and source_auth_type == 'BASIC_AUTH':
        raise InvalidAuthParameters('Source auth secret and pipeline role ARN to access secret, must be provided '
                                    'for an auth type of BASIC_AUTH')

    pipeline_config = Path(pipeline_config_file_path).read_text()

    # Fill in OSI pipeline template file from provided options
    if source_auth_type == 'BASIC_AUTH':
        secret_config = SECRET_CONFIG_TEMPLATE.replace(SOURCE_SECRET_ID_PLACEHOLDER, source_auth_secret)
        secret_config = secret_config.replace(SOURCE_STS_ROLE_ARN_PLACEHOLDER, source_pipeline_role_arn)
        secret_config = secret_config.replace(AWS_REGION_PLACEHOLDER, aws_region)
        pipeline_config = pipeline_config.replace(AWS_SECRET_CONFIG_PLACEHOLDER, secret_config)
        pipeline_config = pipeline_config.replace(SOURCE_AUTH_OPTIONS_PLACEHOLDER, SOURCE_BASIC_AUTH_CONFIG_TEMPLATE)
    else:
        pipeline_config = pipeline_config.replace(AWS_SECRET_CONFIG_PLACEHOLDER, "")

    if source_auth_type == 'SIGV4':
        aws_source_config = SIGV4_AUTH_CONFIG_TEMPLATE.replace(AWS_REGION_PLACEHOLDER, aws_region)
        aws_source_config = aws_source_config.replace(SOURCE_STS_ROLE_ARN_PLACEHOLDER, source_pipeline_role_arn)
        pipeline_config = pipeline_config.replace(SOURCE_AUTH_OPTIONS_PLACEHOLDER, aws_source_config)

    if target_auth_type == 'SIGV4':
        aws_target_config = SIGV4_AUTH_CONFIG_TEMPLATE.replace(AWS_REGION_PLACEHOLDER, aws_region)
        aws_target_config = aws_target_config.replace(TARGET_STS_ROLE_ARN_PLACEHOLDER, target_pipeline_role_arn)
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
    # Seeing issues from CW log group naming that need further investigation
    # if cw_log_group_name is not None:
    #     cw_log_options = {
    #         'IsLoggingEnabled': True,
    #         'CloudWatchLogDestination': {
    #             'LogGroup': cw_log_group_name
    #         }
    #     }

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
        #BufferOptions={
        #    'PersistentBufferEnabled': False
        #},
        Tags=tags
    )


def create_pipeline(osi_client, pipeline_name: str, pipeline_config: str, subnet_ids: List[str],
                    security_group_ids: List[str], cw_log_group_name: str, tags: List[Dict[str, str]]):
    osi_create_pipeline(osi_client, pipeline_name, pipeline_config, subnet_ids, security_group_ids, cw_log_group_name, tags)


def create_pipeline_from_stage(osi_client, pipeline_name: str, pipeline_config_path: str, source_endpoint: str,
                               target_endpoint: str, print_config_only: bool):
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
    vpc_id = param_dict[vpc_id_key]
    security_groups = [param_dict[target_sg_id_key], param_dict[source_sg_id_key]]
    subnet_ids = get_private_subnets(vpc_id)
    pipeline_role_arn = param_dict[osi_pipeline_role_key]
    tags = [{'Key': 'migration_deployment', 'Value': solution_version}]

    pipeline_config = construct_pipeline_config(pipeline_config_file_path=pipeline_config_path, aws_region=region,
                                                source_endpoint=source_endpoint, target_endpoint=target_endpoint,
                                                source_auth_type='SIGV4', target_auth_type='SIGV4',
                                                source_pipeline_role_arn=pipeline_role_arn,
                                                target_pipeline_role_arn=pipeline_role_arn)

    if print_config_only:
        print(pipeline_config)
        exit(0)

    osi_create_pipeline(osi_client, pipeline_name, pipeline_config, subnet_ids, security_groups,
                        param_dict[osi_log_group_key], tags)


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
        pipeline_config_string = construct_pipeline_config(DEFAULT_PIPELINE_CONFIG_PATH, args.source_endpoint,
                                                           args.target_endpoint, args.source_auth_type,
                                                           args.target_auth_type,
                                                           args.source_auth_secret, args.source_pipeline_role_arn,
                                                           args.target_pipeline_role_arn, args.aws_region)
        if args.print_config_only:
            print(pipeline_config_string)
            exit(0)
        create_pipeline(client, args.name, pipeline_config_string, args.subnet_ids, args.security_group_ids,
                        args.log_group_name, args.tag)
    elif args.subcommand == "create-pipeline-from-solution":
        validate_environment()
        create_pipeline_from_stage(client, args.name, DEFAULT_PIPELINE_CONFIG_PATH, args.source_endpoint,
                                   os.environ.get("MIGRATION_DOMAIN_ENDPOINT"), args.print_config_only)
