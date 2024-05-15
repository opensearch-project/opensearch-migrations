from abc import ABC, abstractmethod
from typing import Dict, Optional


class AbstractReplayerService(ABC):
    """
    An abstract class that represents a replayer.
    """
    endpoint: str = ""
    authDetails: Optional[Dict] = {"type": "None"}

    @abstractmethod
    def get_config(self) -> Dict:
        """
        Returns the configuration details of the replayer.
        """
        pass

    @abstractmethod
    def start_replayer():
        """
        Starts the replayer.
        """
        pass

    @abstractmethod
    def stop_replayer():
        """
        Stops the replayer.
        """
        pass


class LocalReplayer(AbstractReplayerService):
    def get_config(self) -> Dict:
        return self.config

    def start_replayer():
        pass

    def stop_replayer():
        pass


class ECSReplayer(AbstractReplayerService):
    def get_config(self) -> Dict:
        return self.config

    def start_replayer():
        pass

    def stop_replayer():
        pass
