from abc import ABC, abstractmethod
import logging

from upgrade_testing_framework.core.exception_base import RuntimeFrameworkException, StepFailedException, UserAbortException
from upgrade_testing_framework.core.logging_wrangler import FrameworkLoggingAdapter

class MissingStateError(Exception):
    def __init__(self, step: str, key: str):
        super().__init__("While running step '{}', tried to read state value with key '{}', but that key didn't exist"
            .format(step, key))

class FrameworkStep(ABC):
    def __init__(self, state: dict):
        self.logger = FrameworkLoggingAdapter(logging.getLogger(__name__), {'step': self.name})
        self.state = state

    def fail(self, message: str, last_exception: BaseException = None):
        """
        This method is used when your step has failed unrecoverably and you want to exit it.  The message you supply
        should indicate why it failed in a user-friendly manner.  
        """
        raise StepFailedException("{} - {}".format(self.name, message), original_exception=last_exception)

    def run(self):
        try:
            self._run()
        except KeyboardInterrupt as exception:
            self.logger.warn('User initiated a keyboard interrupt.  Aborting...')
            self.logger.error('Keyboard interrupt')
            raise UserAbortException('Keyboard interrupt')
        except StepFailedException as exception:
            exception_message = str(exception)
            self.logger.error(exception_message)
            raise exception
        except BaseException as exception:
            exception_message = str(exception)
            self.logger.error(exception_message)
            raise RuntimeFrameworkException(exception_message, exception)

    @abstractmethod
    def _run(self):
        pass

    def _get_state_value(self, key: str) -> any:
        value = self.state.get(key)
        if value == None:
            raise MissingStateError(self.name, key)
        return value

    def _get_state_value_could_be_none(self, key: str) -> any:
        # Instead of failing if it's not set, just pass through the None
        return self.state.get(key)

    def _set_state_value(self, key:str, value: any) -> any:
        return self.state.set(key, value)

    @classmethod
    def cls_name(cls) -> str:
        return cls.__name__

    @property
    def name(self) -> str:
        return self.__class__.__name__