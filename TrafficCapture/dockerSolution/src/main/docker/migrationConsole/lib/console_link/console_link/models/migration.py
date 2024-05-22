from console_link.logic.osi_migration import OSIMigrationLogic, OpenSearchIngestionMigrationProps
from console_link.models.cluster import Cluster
from typing import Dict
from enum import Enum
from cerberus import Validator

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
    },
}

class MigrationType(str, Enum):
    OSI_HISTORICAL_MIGRATION = "OSI_HISTORICAL_MIGRATION"


class Migration():
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
        self.migration_logic = OSIMigrationLogic()
        self.source_cluster = source_cluster
        self.target_cluster = target_cluster

    def create(self, pipeline_template_path = '/root/osiPipelineTemplate.yaml'):
        self.migration_logic.create_pipeline_from_env(pipeline_template_path=pipeline_template_path)

    def create_from_json(self, config_json: Dict, pipeline_template_path: str) -> None:
        self.migration_logic.create_pipeline_from_json(input_json=config_json,
                                                       pipeline_template_path=pipeline_template_path)
    def start(self, pipeline_name = None):
        self.migration_logic.start_pipeline(pipeline_name=pipeline_name)

    def stop(self, pipeline_name = None):
        self.migration_logic.stop_pipeline(pipeline_name=pipeline_name)
