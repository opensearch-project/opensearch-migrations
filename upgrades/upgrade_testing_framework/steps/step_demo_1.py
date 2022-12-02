from upgrade_testing_framework.core.framework_step import FrameworkStep
import upgrade_testing_framework.core.shell_interactions as shell

"""
This is a disposible step used to demonstrate how the Framework is intended to be used.  Please delete once
real steps exist and can be used as an example instead.

The code in the step is not production-worthy and should considered solely from an instructional perspective.
"""
class Demo1(FrameworkStep):
    def __init__(self, state: dict):
        super().__init__(state)

    def _run(self):
        # => Get the state we need <=
        # In this case, we don't need any

        # => Begin the step body <=
        # We'll start with making a simple shell call to check whether the user has Docker installed on their host.
        # If they don't, we shouldn't proceed further so we invoke the fail() method with user-friendly messaging.
        self.logger.info("Checking if Docker is available...")
        exit_code, _ = shell.call_shell_command("which docker") # not platform agnostic, and should wrap this 
        if not exit_code == 0:
            self.logger.warn("Docker is required in order to execute this workflow, but it looks like you don't have"
                " it installed on your host, or it's not on your PATH.  Before proceeding further, please ensure"
                " Docker is installed and available."            
            )
            self.fail("Docker wasn't found on user machine")

        # Next, we'll pull an arbitrarily chosen factoid from the user's machine and store it in the application state
        # to demonstrate that part of the Framework.
        self.logger.info("Checking current running user...")
        _, whoami_response = shell.call_shell_command("whoami") # not platform agnostic, and should wrap this
        
        # => Update our state <=
        # Set a state value to subsequent steps will have it available
        self._set_state_value("user", whoami_response[0])