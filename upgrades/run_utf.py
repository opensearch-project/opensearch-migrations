#!/usr/bin/env python3
import argparse

from cluster_migration_core.core.framework_runner import FrameworkRunner
from cluster_migration_core.core.logging_wrangler import LoggingWrangler
from cluster_migration_core.core.workspace_wrangler import WorkspaceWrangler

import upgrade_testing_framework.workflows as workflows


def get_command_line_args():
    parser = argparse.ArgumentParser(
        description="Script to invoke the Upgrade Testing Framework."
    )

    parser.add_argument("--test_config", help="Path to your test config file (see README for more details)",
                        dest="test_config",
                        required=True
                        )    

    kbd_help = "Path to the knowledge base directory.  This is the top-level directory containing the expectations."
    parser.add_argument("--knowledge_base", help=kbd_help,
                        dest="knowledge_base_path",
                        default="../knowledge_base"
                        )

    return parser.parse_args()


def main():
    args = get_command_line_args()
    test_config = args.test_config
    knowledge_base_path = args.knowledge_base_path

    workspace = WorkspaceWrangler()
    logging = LoggingWrangler(workspace)

    FrameworkRunner(logging, workspace, step_order=workflows.SNAPSHOT_RESTORE_STEPS).run(test_config, knowledge_base_path)


if __name__ == "__main__":
    main()
