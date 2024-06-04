from console_link.models.osi_utils import (create_pipeline_from_env, start_pipeline, stop_pipeline,
                                           OpenSearchIngestionMigrationProps)
from console_link.models.cluster import Cluster
from typing import Dict
from enum import Enum
from cerberus import Validator
import boto3

OSI_SCHEMA = {
    'pipeline_role_arn': {
        'type': 'string',
        'required': True
    },
    'vpc_subnet_ids': {
        'type': 'list',
        'required': True,
        'schema': {
            'type': 'string',
        }
    },
    'security_group_ids': {
        'type': 'list',
        'required': True,
        'schema': {
            'type': 'string',
        }
    },
    'aws_region': {
        'type': 'string',
        'required': True
    },
    'pipeline_name': {
        'type': 'string',
        'required': False
    },
    'index_regex_selection': {
        'type': 'list',
        'required': False,
        'schema': {
            'type': 'string',
        }
    },
    'log_group_name': {
        'type': 'string',
        'required': False
    },
    'tags': {
        'type': 'list',
        'required': False,
        'schema': {
            'type': 'string',
        }
    }
}


class MigrationType(str, Enum):
    OSI_HISTORICAL_MIGRATION = "OSI_HISTORICAL_MIGRATION"


class Migration:
    """
    A base migration manager.
    """

    def create(self):
        raise NotImplementedError

    def start(self):
        raise NotImplementedError

    def stop(self):
        raise NotImplementedError

    def get_status(self):
        raise NotImplementedError


class OpenSearchIngestionMigration(Migration):
    """
    A migration manager for an OpenSearch Ingestion pipeline.
    """

    def __init__(self, config: Dict, source_cluster: Cluster, target_cluster: Cluster) -> None:
        v = Validator(OSI_SCHEMA)
        if not v.validate(config):
            raise ValueError("Invalid config file for OpenSearchIngestion migration", v.errors)
        self.osi_props = OpenSearchIngestionMigrationProps(config=config)
        self.osi_client = boto3.client('osis')
        self.source_cluster = source_cluster
        self.target_cluster = target_cluster

    def create(self, pipeline_template_path='/root/osiPipelineTemplate.yaml',
               print_config_only=False):
        create_pipeline_from_env(osi_client=self.osi_client,
                                 pipeline_template_path=pipeline_template_path,
                                 osi_props=self.osi_props,
                                 source_cluster=self.source_cluster,
                                 target_cluster=self.target_cluster,
                                 print_config_only=print_config_only)

    def start(self, pipeline_name=None):
        if pipeline_name is None:
            pipeline_name = self.osi_props.pipeline_name
        start_pipeline(osi_client=self.osi_client, pipeline_name=pipeline_name)

    def stop(self, pipeline_name=None):
        if pipeline_name is None:
            pipeline_name = self.osi_props.pipeline_name
        stop_pipeline(osi_client=self.osi_client, pipeline_name=pipeline_name)
