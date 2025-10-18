"""Template for a workflow with suspend node for testing approve functionality."""

from datetime import datetime


def get_suspend_workflow_spec():
    """Get a workflow specification with a suspend node.

    This workflow template includes a suspend node that requires manual approval
    to continue. It's useful for testing the approve/resume functionality.

    Returns:
        Dict containing the workflow spec with suspend node
    """
    timestamp = datetime.now().isoformat()

    return {
        "metadata": {
            "generateName": "suspend-workflow-",
            "labels": {
                "workflows.argoproj.io/completed": "false"
            }
        },
        "spec": {
            "templates": [
                {
                    "name": "main",
                    "steps": [
                        [
                            {
                                "name": "step1",
                                "template": "whalesay"
                            }
                        ],
                        [
                            {
                                "name": "approve",
                                "template": "approve-gate"
                            }
                        ],
                        [
                            {
                                "name": "step2",
                                "template": "whalesay"
                            }
                        ]
                    ]
                },
                {
                    "name": "whalesay",
                    "container": {
                        "name": "",
                        "image": "busybox",
                        "command": ["sh", "-c"],
                        "args": [f'echo "Workflow step at {timestamp}"'],
                        "resources": {}
                    }
                },
                {
                    "name": "approve-gate",
                    "suspend": {}
                }
            ],
            "entrypoint": "main",
            "arguments": {}
        }
    }
