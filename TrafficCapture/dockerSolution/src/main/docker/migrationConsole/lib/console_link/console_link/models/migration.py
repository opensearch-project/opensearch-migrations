from typing import Dict, Optional
from enum import Enum

MigrationType = Enum("MigrationType", ["OSI_HISTORICAL_MIGRATION"])
# class MigrationType(str, Enum):
#     OSI_HISTORICAL_MIGRATION = "OSI_HISTORICAL_MIGRATION"

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

    def create(self):
        raise NotImplementedError