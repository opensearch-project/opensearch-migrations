from console_link.models.cluster import Cluster
import yaml
from cerberus import Validator

SCHEMA = {
    'source_cluster': {
        'type': 'dict',
        'required': True
    },
    'target_cluster': {
        'type': 'dict',
        'required': True
    },
    'replayer': {
        'type': 'dict',
        'required': False
    },
    'backfill': {
        'type': 'dict',
        'required': False
    }
}


class Environment:
    def __init__(self, config_file: str):
        # TODO: add validation of overall yaml structure here, and details in each component.

        self.config_file = config_file
        with open(self.config_file) as f:
            self.config = yaml.safe_load(f)
        v = Validator(SCHEMA)
        if not v.validate(self.config):
            raise ValueError("Invalid config file", v.errors)

        self.source_cluster = Cluster(self.config['source_cluster'])

        # At some point, target and replayers should be stored as pairs, but for the time being
        # we can probably assume one target cluster.
        self.target_cluster = Cluster(self.config['target_cluster'])
