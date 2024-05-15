from console_link.models.cluster import Cluster
import yaml


class Environment:
    def __init__(self, config_file: str):
        self.config_file = config_file
        with open(self.config_file) as f:
            self.config = yaml.safe_load(f)
        self.source_cluster = Cluster(self.config['source_cluster'])
        self.target_cluster = Cluster(self.config['target_cluster'])
