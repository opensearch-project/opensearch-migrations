from upgrade_testing_framework.steps import *

# Each of these lists represents a user-workflow composed of a number of steps to be executed in series.  Once these
# get more numerous and/or complex, we can explore moving them to their own files.
DEFAULT_STEPS = [
    BootstrapDocker
]