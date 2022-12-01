# Bring all the individual steps into a single convenient namespace
from upgrade_testing_framework.steps.step_demo_1 import Demo1
from upgrade_testing_framework.steps.step_demo_2 import Demo2

# Each of these lists represents a user-workflow composed of a number of steps to be executed in series
DEMO_STEPS = [
    Demo1,
    Demo2
]

DEFAULT_STEPS = [
    # Currently no real steps
]