import logging
from typing import List

import upgrade_testing_framework.core.constants as constants
import upgrade_testing_framework.core.exception_base as exceptions
from upgrade_testing_framework.core.framework_state import FrameworkState, get_initial_state
from upgrade_testing_framework.core.framework_step import FrameworkStep
from upgrade_testing_framework.core.logging_wrangler import FrameworkLoggingAdapter, LoggingWrangler
from upgrade_testing_framework.core.workspace_wrangler import WorkspaceWrangler
import upgrade_testing_framework.steps as steps  # noqa F401 -- used by the unit tests
import upgrade_testing_framework.workflows as workflows

ISSUE_LINK = "https://github.com/opensearch-project/opensearch-migrations/issues/new/choose"


class FrameworkRunner:
    def __init__(self, logging_context: LoggingWrangler, workspace: WorkspaceWrangler,
                 step_order: List[FrameworkStep] = workflows.SNAPSHOT_RESTORE_STEPS):
        self.logging_context = logging_context
        self.logger = FrameworkLoggingAdapter(logging.getLogger(__name__), {'step': self.__class__.__name__})
        self.workspace = workspace
        self.step_order = step_order

    @property
    def log_file(self):
        return self.logging_context.log_file

    def run(self, test_config_path: str):
        state = get_initial_state(test_config_path)
        state.set_key('log_file', self.log_file)
        state.set_key('test_results_directory', self.workspace.test_results_directory)

        try:
            # Figure out where in the list of steps we should begin.  Should just be the first one, but if we want to
            # be able to resume a previous run this would be the spot you'd pick something else
            starting_step_index = 0

            # From that starting step through the end of the step list, run the steps
            for step_index in range(starting_step_index, len(self.step_order)):
                current_step = self.step_order[step_index]
                state.set_key('current_step', current_step.cls_name())
                self.logger.info(f"============ Running Step: {current_step.cls_name()} ============")
                current_step(state).run()
                self.logger.info(f"Step Succeeded: {current_step.cls_name()}")
            self.logger.info("Ran through all steps successfully")
            state.set_key('exit_type', constants.EXIT_TYPE_SUCCESS)
        except exceptions.UserAbortException as exception:
            self.logger.warning('User aborted the operation')
            self._set_exit_state_for_exception(state, exception)
        except exceptions.StepFailedException as exception:
            exit_message = (f"A Framework step {current_step.cls_name()} failed.  Please review the terminal and log"
                            f" output to understand why. You can find the log output here: {self.log_file}")
            self.logger.warning(exit_message)
            cut_an_issue_message = ("Please cut an issue to us on GitHub so that we can address the problem:"
                                    f" {ISSUE_LINK}")
            self.logger.warning(cut_an_issue_message)
            self._set_exit_state_for_exception(state, exception)
        except BaseException as exception:
            try:
                self.logger.debug('Exception encountered:', exc_info=True)
                exit_message = (
                    f"We're exiting unexpectedly while attempting step {current_step.cls_name()}. Please review the"
                    f"  terminal and log output to understand why.  You can find the log output here: {self.log_file}")
                self.logger.warning(exit_message)
                cut_an_issue_message = ("Please cut an issue to us on GitHub so that we can address the problem:"
                                        f" {ISSUE_LINK}")
                self.logger.warning(cut_an_issue_message)
                self._set_exit_state_for_exception(state, exception)
            except BaseException:
                self.logger.debug('Exception encountered:', exc_info=True)

        finally:
            if state.get_key('exit_type') in constants.EXIT_TYPES_FAILURE:
                self.logger.warning("Step Failed: {}".format(state.get_key('current_step')))
            if not state.get_key('state_file'):
                state.set_key('state_file', self.workspace.state_file)
            self.logger.info('Saving application state to file...')
            with open(state.get_key('state_file'), 'w') as config_file:
                config_file.write(str(state))
            self.logger.info('Application state saved')

            self.logger.info("Application state saved to: {}".format(state.get_key('state_file')))
            self.logger.info("Full run details logged to: {}".format(self.log_file))

            return state

    def _set_exit_state_for_exception(self, state: FrameworkState, exception: BaseException):
        if exceptions.is_exception_in_type_list(exception, [exceptions.UserAbortException]):
            state.set_key('exit_type', constants.EXIT_TYPE_ABORT)
        elif exceptions.is_exception_in_type_list(exception, [exceptions.StepFailedException]):
            state.set_key('exit_type', constants.EXIT_TYPE_FAILURE)
            if exception.original_exception:
                state.set_key('last_exception_message', str(exception.original_exception))
                state.set_key('last_exception_type', exception.original_exception.__class__.__name__)
        elif exceptions.is_exception_in_type_list(exception, [exceptions.RuntimeFrameworkException]):
            state.set_key('exit_type', constants.EXIT_TYPE_FAILURE_UNEXPECTED)
            state.set_key('last_exception_message', str(exception.original_exception))
            state.set_key('last_exception_type', exception.original_exception.__class__.__name__)
        else:
            state.set_key('exit_type', constants.EXIT_TYPE_FAILURE_UNHANDLED)
            state.set_key('last_exception_message', str(exception))
            state.set_key('last_exception_type', exception.__class__.__name__)
