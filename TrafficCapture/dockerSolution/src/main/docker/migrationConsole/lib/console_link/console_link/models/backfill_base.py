from enum import Enum


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
