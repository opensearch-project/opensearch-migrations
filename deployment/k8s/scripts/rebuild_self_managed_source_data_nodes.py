#!/usr/bin/env python3

"""Rebuild a tagged self-managed source data fleet evenly across stack subnets.

This is test-environment tooling for large capture/replay qualification runs. It
resolves the source stack's launch template, data-node target group, and private
subnets from CloudFormation instead of relying on hand-copied resource IDs.

The command is a dry run unless ``--apply`` is present. Existing data nodes cause
the command to stop unless ``--replace-existing`` is also present; that explicit
combination terminates every tagged data node without an Elasticsearch drain.
Delete application indices first when their contents are intentionally disposable.
"""

import argparse
import json
import subprocess
import sys
import uuid


REQUIRED_STACK_RESOURCES = {
    "NodeLaunchTemplate",
    "DataNodeTargetGroup",
}


def run_aws(region, *arguments):
    result = subprocess.run(
        ["aws", *arguments, "--region", region, "--output", "json"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode:
        raise RuntimeError(
            f"AWS command failed ({' '.join(arguments)}):\n{result.stderr.strip()}"
        )
    return json.loads(result.stdout or "{}")


def resolve_stack_resources(region, stack_name):
    response = run_aws(
        region,
        "cloudformation",
        "describe-stack-resources",
        "--stack-name",
        stack_name,
    )
    resources = {
        item["LogicalResourceId"]: item["PhysicalResourceId"]
        for item in response["StackResources"]
    }
    missing = REQUIRED_STACK_RESOURCES - resources.keys()
    if missing:
        raise RuntimeError(
            f"Stack {stack_name} lacks required resources: {sorted(missing)}"
        )

    subnets = [
        resources[name]
        for name in sorted(resources)
        if name.startswith("PrivateSubnet")
    ]
    if len(subnets) < 2:
        raise RuntimeError(
            f"Stack {stack_name} exposes fewer than two PrivateSubnet resources"
        )
    return resources["NodeLaunchTemplate"], resources["DataNodeTargetGroup"], subnets


def balanced_launch_plan(cluster_name, subnets, nodes_per_az):
    """Return stable node names in round-robin subnet order."""
    return [
        (f"{cluster_name}-data-{round_number * len(subnets) + subnet_index + 1}", subnet)
        for round_number in range(nodes_per_az)
        for subnet_index, subnet in enumerate(subnets)
    ]


def existing_data_nodes(region, cluster_name):
    response = run_aws(
        region,
        "ec2",
        "describe-instances",
        "--filters",
        f"Name=tag:es-cluster,Values={cluster_name}",
        "Name=tag:es-node-role,Values=data",
        "Name=instance-state-name,Values=pending,running,stopping,stopped",
    )
    return [
        instance
        for reservation in response.get("Reservations", [])
        for instance in reservation.get("Instances", [])
    ]


def retained_data_volumes(instances):
    """Find attached non-root volumes that EC2 will not delete on termination."""
    volumes = []
    for instance in instances:
        root_device = instance.get("RootDeviceName")
        for mapping in instance.get("BlockDeviceMappings", []):
            ebs = mapping.get("Ebs", {})
            if (
                mapping.get("DeviceName") != root_device
                and not ebs.get("DeleteOnTermination", False)
                and ebs.get("VolumeId")
            ):
                volumes.append(ebs["VolumeId"])
    return volumes


def target_arguments(instance_ids):
    return [f"Id={instance_id}" for instance_id in instance_ids]


def delete_existing_fleet(region, target_group, instances):
    instance_ids = [instance["InstanceId"] for instance in instances]
    volume_ids = retained_data_volumes(instances)
    if instance_ids:
        run_aws(
            region,
            "elbv2",
            "deregister-targets",
            "--target-group-arn",
            target_group,
            "--targets",
            *target_arguments(instance_ids),
        )
        run_aws(
            region,
            "ec2",
            "terminate-instances",
            "--instance-ids",
            *instance_ids,
        )
        run_aws(
            region,
            "ec2",
            "wait",
            "instance-terminated",
            "--instance-ids",
            *instance_ids,
        )
    for volume_id in volume_ids:
        run_aws(region, "ec2", "wait", "volume-available", "--volume-ids", volume_id)
        run_aws(region, "ec2", "delete-volume", "--volume-id", volume_id)


def launch_node(args, launch_template, name, subnet):
    common_tags = [
        {"Key": "Name", "Value": name},
        {"Key": "es-cluster", "Value": args.cluster_name},
        {"Key": "es-node-role", "Value": "data"},
        {"Key": "placement-policy", "Value": "balanced-across-stack-subnets"},
    ]
    response = run_aws(
        args.region,
        "ec2",
        "run-instances",
        "--launch-template",
        f"LaunchTemplateId={launch_template},Version={args.launch_template_version}",
        "--instance-type",
        args.instance_type,
        "--subnet-id",
        subnet,
        "--block-device-mappings",
        json.dumps([{
            "DeviceName": "/dev/xvdb",
            "Ebs": {
                "DeleteOnTermination": True,
                "Encrypted": True,
                "Iops": args.data_volume_iops,
                "Throughput": args.data_volume_throughput,
                "VolumeSize": args.data_volume_gib,
                "VolumeType": "gp3",
            },
        }]),
        "--client-token",
        str(uuid.uuid4()),
        "--tag-specifications",
        json.dumps([
            {"ResourceType": "instance", "Tags": common_tags},
            {"ResourceType": "volume", "Tags": common_tags},
        ]),
    )
    return response["Instances"][0]["InstanceId"]


def launch_fleet(args, launch_template, target_group, plan):
    instance_ids = [
        launch_node(args, launch_template, name, subnet)
        for name, subnet in plan
    ]
    run_aws(
        args.region,
        "ec2",
        "wait",
        "instance-running",
        "--instance-ids",
        *instance_ids,
    )
    run_aws(
        args.region,
        "elbv2",
        "register-targets",
        "--target-group-arn",
        target_group,
        "--targets",
        *target_arguments(instance_ids),
    )
    return instance_ids


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stack-name", required=True)
    parser.add_argument("--cluster-name", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--instance-type", required=True)
    parser.add_argument("--nodes-per-az", type=int, default=8)
    parser.add_argument("--launch-template-version", default="$Default")
    parser.add_argument("--data-volume-gib", type=int, default=2048)
    parser.add_argument("--data-volume-iops", type=int, default=6000)
    parser.add_argument("--data-volume-throughput", type=int, default=500)
    parser.add_argument("--replace-existing", action="store_true")
    parser.add_argument("--apply", action="store_true")
    return parser.parse_args()


def main():
    args = parse_args()
    launch_template, target_group, subnets = resolve_stack_resources(
        args.region, args.stack_name
    )
    existing = existing_data_nodes(args.region, args.cluster_name)
    plan = balanced_launch_plan(args.cluster_name, subnets, args.nodes_per_az)

    print(f"stack={args.stack_name}")
    print(f"launch_template={launch_template}")
    print(f"target_group={target_group}")
    for subnet in subnets:
        print(f"subnet={subnet} desired_nodes={args.nodes_per_az}")
    print(f"existing_data_nodes={len(existing)} planned_data_nodes={len(plan)}")

    if existing and not args.replace_existing:
        raise RuntimeError(
            "Existing data nodes found; rerun with --replace-existing only after "
            "declaring their data disposable"
        )
    if not args.apply:
        print("dry_run=true; add --apply to make changes")
        return
    if existing:
        delete_existing_fleet(args.region, target_group, existing)
    instance_ids = launch_fleet(args, launch_template, target_group, plan)
    print(f"launched={','.join(instance_ids)}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        sys.exit(1)
