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
        This method is used when your step has failed unrecoverably and you want to exit it.  Ideally, this should be
        invoked whenever there is a "runtime" exception in a Step, as that indicates we've probably handled the
        scenario gracefully.
        
        The message you supply should indicate why it failed in a user-friendly manner.  If there was a precipitating
        exception that lead you to fail the step, it should be passed in via last_exception.
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
            # All "runtime" exceptions should funnel through here by way of catching them and then invoking self.fail()
            exception_message = str(exception)
            self.logger.error(exception_message)
            raise exception
        except BaseException as exception:
            # If an exception hits this section of code, then we probably haven't done our jobs correctly as it likely
            # means we're not gracefully handling an issue for the user.
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