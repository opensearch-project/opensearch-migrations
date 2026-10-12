import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).with_name("rebuild_self_managed_source_data_nodes.py")
SPEC = importlib.util.spec_from_file_location("source_rebuild", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SourceDataNodeRebuildTest(unittest.TestCase):
    def test_balanced_launch_plan_rotates_evenly_through_subnets(self):
        plan = MODULE.balanced_launch_plan(
            "source",
            ["subnet-a", "subnet-b", "subnet-c"],
            2,
        )

        self.assertEqual(plan, [
            ("source-data-1", "subnet-a"),
            ("source-data-2", "subnet-b"),
            ("source-data-3", "subnet-c"),
            ("source-data-4", "subnet-a"),
            ("source-data-5", "subnet-b"),
            ("source-data-6", "subnet-c"),
        ])

    def test_retained_data_volumes_excludes_root_and_delete_on_termination(self):
        instances = [{
            "RootDeviceName": "/dev/xvda",
            "BlockDeviceMappings": [
                {
                    "DeviceName": "/dev/xvda",
                    "Ebs": {"VolumeId": "root", "DeleteOnTermination": True},
                },
                {
                    "DeviceName": "/dev/xvdb",
                    "Ebs": {"VolumeId": "retained", "DeleteOnTermination": False},
                },
                {
                    "DeviceName": "/dev/xvdc",
                    "Ebs": {"VolumeId": "ephemeral", "DeleteOnTermination": True},
                },
            ],
        }]

        self.assertEqual(MODULE.retained_data_volumes(instances), ["retained"])


if __name__ == "__main__":
    unittest.main()
