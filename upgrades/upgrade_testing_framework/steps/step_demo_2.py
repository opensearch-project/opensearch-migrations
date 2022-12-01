import time

from upgrade_testing_framework.core.framework_step import FrameworkStep

"""
This is a disposible step used to demonstrate how the Framework is intended to be used.  Please delete once
real steps exist and can be used as an example instead.

The code in the step is not production-worthy and should considered solely from an instructive perspective.
"""
class Demo2(FrameworkStep):
    def __init__(self, state: dict):
        super().__init__(state)

    def _run(self):
        # => Get the state we need <=
        user = self._get_state_value("user") # Exception will be thrown if not available

        # => Begin the step body <=
        # Perform a sleep so you can CTRL+C and abort the run, allowing you to test the resume feature
        self.logger.info("Pausing for 5 seconds...")
        time.sleep(5)

        # We'll now use the state we just pulled
        self.logger.info(f"You are: {user}")
        
        # => Update our state <=
        # None to set