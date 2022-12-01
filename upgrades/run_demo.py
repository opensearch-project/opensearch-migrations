#!/usr/bin/env python3
import argparse
import json
import os

from upgrade_testing_framework.core.framework_runner import FrameworkRunner
from upgrade_testing_framework.core.logging_wrangler import LoggingWrangler
import upgrade_testing_framework.steps as steps
from upgrade_testing_framework.core.workspace_wrangler import WorkspaceWrangler

def get_command_line_args():
    parser = argparse.ArgumentParser(
        description="A quick script to demo the framework.  Delete in next commit."
    )

    parser.add_argument("--resume", help="DEBUG FEATURE - Resume your previous run",
        dest="resume",
        action='store_true',
        default=False
    )

    return parser.parse_args()


def main():
    args = get_command_line_args()
    resume = args.resume

    workspace = WorkspaceWrangler()
    logging = LoggingWrangler(workspace)

    FrameworkRunner(logging, workspace, step_order=steps.DEMO_STEPS).run(resume)  

if __name__ == "__main__":
    main()