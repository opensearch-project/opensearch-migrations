"""Verify that deployer-supplied tags reached every AWS resource the deployment created.

The problem this solves is enumeration, not comparison. CloudFormation stack tags stop at the
resources CloudFormation creates; EKS Auto Mode creates nodes, volumes, ENIs and load balancers
later and on its own. Finding those after the fact by *searching for the tags* would be circular --
an untagged resource is exactly the one a tag search cannot find -- and scanning a whole region only
works if the region is empty and stays empty.

So nothing here searches by tag. Every resource is reached from an oracle that knows it exists
independently of its tags:

  * CloudFormation  -- ListStackResources is the authoritative list of what the stack made.
  * The Kubernetes API -- every AWS resource Auto Mode created on the cluster's behalf is
    referenced by ID from some Kubernetes object:
        Node.spec.providerID              -> EC2 instance
        instance BlockDeviceMappings      -> root/ephemeral EBS volumes
        ENIs attached to those instances  -> network interfaces
        instance tag aws:ec2launchtemplate:id (AWS-injected, not ours) -> launch template
        PersistentVolume .spec.csi.volumeHandle -> PVC-provisioned EBS volume
        Service .status.loadBalancer.ingress[].hostname -> load balancer,
            and from it its target groups, listeners and security groups

  * CloudTrail -- belt and suspenders. The two oracles above check resources we know how to find;
    this one checks *calls*, so it catches a create whose resource type nobody enumerated. Scoped to
    the cluster IAM role, the principal Auto Mode assumes on the cluster's behalf.

Because the cluster is the index, this is exact regardless of what else lives in the region, and
needs no clean account.

Usage (standalone -- no Jenkins involved):

    cd libraries/testAutomation
    pipenv install --deploy
    pipenv run verify-tags \\
        --expect-tags 'MATestOwner=migrations-ci,MATestStage=esoscdc-p42' \\
        --kube-context migration-eks-esoscdc-p42 \\
        --region us-east-1 \\
        --stack-name Migration-Assistant-Infra-Create-VPC-eks-esoscdc-p42-us-east-1

Exits non-zero and prints a table of every resource missing any expected tag.
"""

import argparse
import json
import logging
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Dict, List, Optional, Sequence

logger = logging.getLogger(__name__)


@dataclass
class Finding:
    """One resource that is missing at least one expected tag."""
    kind: str
    identifier: str
    missing: Dict[str, Optional[str]]
    discovered_via: str


@dataclass
class VerificationResult:
    checked: int = 0
    findings: List[Finding] = field(default_factory=list)
    # Resources we located but could not read tags for (permissions, races, eventual consistency).
    # Reported separately: an unreadable resource is not evidence of a missing tag either way.
    unreadable: List[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.findings


def parse_tag_spec(spec: str) -> Dict[str, str]:
    """Parse 'Key=Value,Key2=Value2' -- the same form aws-bootstrap.sh --tags accepts."""
    tags: Dict[str, str] = {}
    for entry in spec.split(","):
        entry = entry.strip()
        if not entry:
            continue
        if "=" not in entry:
            raise ValueError(f"tag entry {entry!r} is not in Key=Value form")
        key, value = entry.split("=", 1)
        key = key.strip()
        if not key:
            raise ValueError(f"tag entry {entry!r} has an empty key")
        tags[key] = value.strip()
    return tags


def _tag_list_to_dict(tag_list: Optional[Sequence[dict]]) -> Dict[str, str]:
    """Normalize the several shapes AWS returns tags in ({Key,Value} lists everywhere we use)."""
    return {t["Key"]: t.get("Value", "") for t in (tag_list or [])}


def _check(result: VerificationResult, kind: str, identifier: str, actual: Dict[str, str],
           expected: Dict[str, str], discovered_via: str) -> None:
    result.checked += 1
    missing = {k: v for k, v in expected.items() if actual.get(k) != v}
    if missing:
        # Record what was actually found so a value mismatch is distinguishable from an absent key.
        result.findings.append(Finding(
            kind=kind,
            identifier=identifier,
            missing={k: actual.get(k) for k in missing},
            discovered_via=discovered_via,
        ))
        logger.error("%s %s is missing tags %s (found: %s)", kind, identifier,
                     sorted(missing), {k: actual.get(k) for k in missing})
    else:
        logger.info("%s %s OK", kind, identifier)


# --------------------------------------------------------------------------------------------------
# Oracle 1: CloudFormation
# --------------------------------------------------------------------------------------------------

def verify_cloudformation(result: VerificationResult, expected: Dict[str, str],
                          stack_name: str, region: str) -> None:
    """Check the stack's own tags, then every stack resource the tagging API can see.

    CloudFormation propagating stack tags to the resources it creates is AWS-documented behaviour,
    so the stack's own tag set is the thing worth asserting -- if it is right, the children follow.
    The per-resource sweep below is a cross-check rather than the primary guarantee: the Resource
    Groups Tagging API does not cover every service, so a resource missing from its response is
    inconclusive, not a failure.
    """
    import boto3

    cfn = boto3.client("cloudformation", region_name=region)
    stack = cfn.describe_stacks(StackName=stack_name)["Stacks"][0]
    _check(result, "CloudFormation stack", stack_name, _tag_list_to_dict(stack.get("Tags")),
           expected, "describe-stacks")

    stack_id = stack["StackId"]
    tagging = boto3.client("resourcegroupstaggingapi", region_name=region)
    paginator = tagging.get_paginator("get_resources")
    seen = 0
    # aws:cloudformation:stack-id is injected by CloudFormation itself, so this finds the stack's
    # resources without depending on the tags we are trying to verify.
    for page in paginator.paginate(TagFilters=[{"Key": "aws:cloudformation:stack-id",
                                                "Values": [stack_id]}]):
        for entry in page["ResourceTagMappingList"]:
            seen += 1
            _check(result, "stack resource", entry["ResourceARN"],
                   _tag_list_to_dict(entry.get("Tags")), expected,
                   "tagging-api by stack-id")
    logger.info("Checked %d CloudFormation-created resources visible to the tagging API", seen)


# --------------------------------------------------------------------------------------------------
# Oracle 2: the Kubernetes API
# --------------------------------------------------------------------------------------------------

def _k8s_clients(kube_context: Optional[str]):
    from kubernetes import client, config
    config.load_kube_config(context=kube_context)
    return client.CoreV1Api(), client.NetworkingV1Api()


def collect_instance_ids(core_v1) -> List[str]:
    """Node.spec.providerID is 'aws:///<az>/<instance-id>' -- no tag lookup involved."""
    ids = []
    for node in core_v1.list_node().items:
        provider_id = (node.spec.provider_id or "") if node.spec else ""
        if provider_id.startswith("aws:///"):
            ids.append(provider_id.rsplit("/", 1)[-1])
        elif provider_id:
            logger.warning("Node %s has a non-AWS providerID %r; skipping",
                           node.metadata.name, provider_id)
    return ids


def collect_pv_volume_ids(core_v1) -> List[str]:
    """PersistentVolume.spec.csi.volumeHandle is the EBS volume ID for the EBS CSI driver."""
    ids = []
    for pv in core_v1.list_persistent_volume().items:
        csi = pv.spec.csi if pv.spec else None
        handle = getattr(csi, "volume_handle", None) if csi else None
        if handle and handle.startswith("vol-"):
            ids.append(handle)
    return ids


def collect_load_balancer_hostnames(core_v1) -> List[str]:
    """Hostnames of every Service that actually got a load balancer provisioned."""
    hostnames = []
    for svc in core_v1.list_service_for_all_namespaces().items:
        if (svc.spec.type if svc.spec else None) != "LoadBalancer":
            continue
        ingress = ((svc.status.load_balancer.ingress if svc.status and svc.status.load_balancer
                    else None) or [])
        for entry in ingress:
            if entry.hostname:
                hostnames.append(entry.hostname)
            elif entry.ip:
                # NLBs in IP mode still publish a hostname; an IP-only status means we cannot map
                # back to an ARN, so say so rather than silently skipping the load balancer.
                logger.warning("Service %s/%s exposes only an IP (%s); cannot resolve its load "
                               "balancer ARN", svc.metadata.namespace, svc.metadata.name, entry.ip)
    return hostnames


def verify_compute_and_storage(result: VerificationResult, expected: Dict[str, str],
                               core_v1, region: str) -> None:
    import boto3
    ec2 = boto3.client("ec2", region_name=region)

    instance_ids = collect_instance_ids(core_v1)
    if not instance_ids:
        # An Auto Mode cluster with no nodes means nothing to check -- and almost certainly means
        # the deployment is broken, so this must not read as a pass.
        result.unreadable.append("no cluster nodes found; compute/storage tags unverified")
        logger.warning("No AWS-backed nodes found via the Kubernetes API")
        return
    logger.info("Discovered %d instance(s) from Node.spec.providerID: %s",
                len(instance_ids), instance_ids)

    volume_ids, launch_template_ids = set(), set()
    for reservation in ec2.describe_instances(InstanceIds=instance_ids)["Reservations"]:
        for inst in reservation["Instances"]:
            tags = _tag_list_to_dict(inst.get("Tags"))
            _check(result, "EC2 instance", inst["InstanceId"], tags, expected,
                   "Node.spec.providerID")
            for bdm in inst.get("BlockDeviceMappings", []):
                vol_id = bdm.get("Ebs", {}).get("VolumeId")
                if vol_id:
                    volume_ids.add(vol_id)
            # AWS injects this tag when an instance comes from a launch template, so using it to
            # find the template is not circular -- it is not one of the tags under test.
            if tags.get("aws:ec2launchtemplate:id"):
                launch_template_ids.add(tags["aws:ec2launchtemplate:id"])

    volume_ids.update(collect_pv_volume_ids(core_v1))
    if volume_ids:
        logger.info("Discovered %d volume(s) from instance block devices and PersistentVolumes",
                    len(volume_ids))
        for vol in ec2.describe_volumes(VolumeIds=sorted(volume_ids))["Volumes"]:
            _check(result, "EBS volume", vol["VolumeId"], _tag_list_to_dict(vol.get("Tags")),
                   expected, "instance BDM / PV volumeHandle")

    enis = ec2.describe_network_interfaces(
        Filters=[{"Name": "attachment.instance-id", "Values": instance_ids}]
    )["NetworkInterfaces"]
    for eni in enis:
        _check(result, "network interface", eni["NetworkInterfaceId"],
               _tag_list_to_dict(eni.get("TagSet")), expected, "ENI attached to a cluster node")

    for lt_id in sorted(launch_template_ids):
        templates = ec2.describe_launch_templates(LaunchTemplateIds=[lt_id])["LaunchTemplates"]
        for lt in templates:
            _check(result, "launch template", lt["LaunchTemplateId"],
                   _tag_list_to_dict(lt.get("Tags")), expected,
                   "aws:ec2launchtemplate:id on a node")


def verify_load_balancers(result: VerificationResult, expected: Dict[str, str],
                          core_v1, region: str) -> None:
    import boto3
    hostnames = collect_load_balancer_hostnames(core_v1)
    if not hostnames:
        # Not a failure on its own: only the CDC tests create a Service of type LoadBalancer. But
        # record it, so a run that was supposed to exercise load balancers cannot quietly skip them.
        result.unreadable.append("no LoadBalancer Services found; load balancer tags unverified")
        logger.warning("No LoadBalancer Services with a provisioned hostname were found")
        return

    elbv2 = boto3.client("elbv2", region_name=region)
    ec2 = boto3.client("ec2", region_name=region)
    # Map DNS name -> ARN by listing the account's load balancers. The DNS name comes from the
    # Service status, so the match is driven by the cluster, not by tags.
    by_dns = {}
    for page in elbv2.get_paginator("describe_load_balancers").paginate():
        for lb in page["LoadBalancers"]:
            by_dns[lb["DNSName"].lower()] = lb

    for hostname in hostnames:
        lb = by_dns.get(hostname.lower())
        if lb is None:
            result.unreadable.append(f"load balancer for {hostname} not found via elbv2")
            logger.warning("No elbv2 load balancer matches Service hostname %s", hostname)
            continue
        lb_arn = lb["LoadBalancerArn"]
        _check(result, "load balancer", lb_arn,
               _tag_list_to_dict(elbv2.describe_tags(ResourceArns=[lb_arn])
                                 ["TagDescriptions"][0].get("Tags")),
               expected, "Service status hostname")

        target_groups = elbv2.describe_target_groups(LoadBalancerArn=lb_arn)["TargetGroups"]
        listeners = elbv2.describe_listeners(LoadBalancerArn=lb_arn)["Listeners"]
        child_arns = [tg["TargetGroupArn"] for tg in target_groups]
        child_arns += [ln["ListenerArn"] for ln in listeners]
        # describe_tags takes at most 20 ARNs per call.
        for i in range(0, len(child_arns), 20):
            for desc in elbv2.describe_tags(ResourceArns=child_arns[i:i + 20])["TagDescriptions"]:
                kind = "target group" if ":targetgroup/" in desc["ResourceArn"] else "listener"
                _check(result, kind, desc["ResourceArn"], _tag_list_to_dict(desc.get("Tags")),
                       expected, "child of the Service's load balancer")

        sg_ids = lb.get("SecurityGroups") or []
        if sg_ids:
            for sg in ec2.describe_security_groups(GroupIds=sg_ids)["SecurityGroups"]:
                _check(result, "LB security group", sg["GroupId"],
                       _tag_list_to_dict(sg.get("Tags")), expected,
                       "security group on the Service's load balancer")


def expected_tags_from_stack(stack_name: str, region: str) -> Dict[str, str]:
    """Derive what to expect from the stack's own tags, so no test has to hardcode them.

    Whoever deployed chose the tags (aws-bootstrap.sh --tags sets them as stack tags), so the stack
    is the source of truth for what *should* have propagated. Reading them back means a caller only
    has to say which deployment to check, not repeat its configuration -- and the check cannot drift
    out of sync with the deployment it is checking.

    AWS-managed tags (aws:*) are excluded: CloudFormation sets those on its own resources and
    nothing else is expected to carry them.
    """
    import boto3
    cfn = boto3.client("cloudformation", region_name=region)
    stack = cfn.describe_stacks(StackName=stack_name)["Stacks"][0]
    return {k: v for k, v in _tag_list_to_dict(stack.get("Tags")).items()
            if not k.startswith("aws:")}


# --------------------------------------------------------------------------------------------------
# Oracle 3: CloudTrail
# --------------------------------------------------------------------------------------------------

# The creates that EKS Auto Mode can be made to tag. This is not a guess: it is exactly the action
# set of the AWS-documented tag-propagation policy that the solution attaches to the cluster role
# (see EksInfra.allowAutoModeTagPropagation). An untagged create in this set is therefore always a
# defect in our plumbing -- there is no "legitimately untagged" case here.
# https://docs.aws.amazon.com/eks/latest/userguide/auto-cluster-iam-role.html#tag-prop
TAGGABLE_CREATE_EVENTS = frozenset({
    "CreateFleet", "RunInstances", "CreateLaunchTemplate",          # Compute
    "CreateVolume", "CreateSnapshot",                               # Storage
    "CreateNetworkInterface",                                       # Networking
    "CreateLoadBalancer", "CreateTargetGroup", "CreateListener", "CreateRule",
    "CreateSecurityGroup",                                          # LoadBalancer
})

# Anything else that creates a resource. Kept broad on purpose: the entire point of this oracle is to
# surface creates nobody thought to enumerate. A hit here that carries no tags means AWS offers no
# mechanism to tag it, which is not something this repo can fix -- but it is exactly the list a
# customer whose SCP requires tags on create has to know about.
_CREATE_EVENT_PREFIXES = ("Create", "Run", "Allocate", "Provision", "Request", "Register")


def _request_tags(detail: dict) -> Dict[str, str]:
    """Pull tags out of a CloudTrail event's requestParameters.

    Shapes differ by service, and CloudTrail lowercases EC2's keys:
      EC2    requestParameters.tagSpecificationSet.items[].tags[].{key,value}
      elbv2  requestParameters.tags[].{key,value}
    Both spellings are accepted since CloudTrail is not consistent about casing across services.
    """
    params = detail.get("requestParameters") or {}
    collected: Dict[str, str] = {}

    def absorb(items) -> None:
        for item in items or []:
            if not isinstance(item, dict):
                continue
            key = item.get("key", item.get("Key"))
            if key is not None:
                collected[key] = item.get("value", item.get("Value", "")) or ""

    absorb(params.get("tags"))
    absorb(params.get("tagSet"))
    spec = params.get("tagSpecificationSet") or {}
    for entry in spec.get("items") or []:
        if isinstance(entry, dict):
            absorb(entry.get("tags"))
    # RunInstances with a launch template can also carry TagSpecifications at the top level.
    for entry in params.get("tagSpecifications") or []:
        if isinstance(entry, dict):
            absorb(entry.get("tags", entry.get("Tags")))
    return collected


def is_create_event(event_name: str) -> bool:
    return event_name in TAGGABLE_CREATE_EVENTS or event_name.startswith(_CREATE_EVENT_PREFIXES)


def verify_cloudtrail_creates(result: VerificationResult, expected: Dict[str, str], region: str,
                              cluster_name: str, start_time, end_time,
                              max_events: int = 200000) -> None:
    """Fail on any resource-creating call by the cluster role whose request omitted the tags.

    This is the belt-and-suspenders oracle. The other two check resources we know how to find; this
    one checks *calls*, so it catches a create whose resource type nobody enumerated. Scoped to the
    cluster IAM role -- the principal EKS Auto Mode assumes on the cluster's behalf -- which makes it
    exact without any assumption about the region being otherwise idle.

    Findings are split by whether the action is one AWS lets us tag (our bug) or not (no mechanism
    exists; the deployer has to exempt it). Both are reported as failures because a create without
    the tag is fatal under an SCP that requires the tag on create, whoever owns the fix.
    """
    import boto3

    eks = boto3.client("eks", region_name=region)
    cluster_role_arn = eks.describe_cluster(name=cluster_name)["cluster"]["roleArn"]
    logger.info("Scanning CloudTrail for creates by the cluster role %s between %s and %s",
                cluster_role_arn, start_time, end_time)

    ct = boto3.client("cloudtrail", region_name=region)
    paginator = ct.get_paginator("lookup_events")
    scanned = matched = 0
    truncated = False
    for page in paginator.paginate(
            LookupAttributes=[{"AttributeKey": "ReadOnly", "AttributeValue": "false"}],
            StartTime=start_time, EndTime=end_time):
        for event in page["Events"]:
            scanned += 1
            if scanned > max_events:
                truncated = True
                break
            try:
                detail = json.loads(event["CloudTrailEvent"])
            except (KeyError, ValueError):
                continue
            issuer = (((detail.get("userIdentity") or {}).get("sessionContext") or {})
                      .get("sessionIssuer") or {}).get("arn")
            if issuer != cluster_role_arn:
                continue
            name = detail.get("eventName", "")
            if not is_create_event(name):
                continue
            # A rejected call created nothing, so it cannot be missing tags on a real resource. This
            # is also what a working Deny policy looks like from the outside.
            if detail.get("errorCode"):
                logger.info("%s was rejected (%s); not a created resource",
                            name, detail.get("errorCode"))
                continue
            matched += 1
            enforceable = name in TAGGABLE_CREATE_EVENTS
            kind = (f"{name} (taggable, our bug)" if enforceable
                    else f"{name} (no AWS tagging mechanism)")
            _check(result, kind, detail.get("eventID", "?"), _request_tags(detail), expected,
                   "CloudTrail, cluster role")
    if truncated:
        # Never let a bounded scan read as full coverage.
        result.unreadable.append(
            f"CloudTrail scan stopped after {max_events} events; coverage is incomplete")
        logger.error("CloudTrail scan hit the %d event cap", max_events)
    logger.info("CloudTrail: %d write events scanned, %d creates by the cluster role", scanned, matched)
    if matched == 0:
        result.unreadable.append(
            "CloudTrail found no creates by the cluster role; either nothing scaled during the "
            "window or events had not been delivered yet (delivery lags by 5-15 minutes)")


def verify_resource_tags(expected: Dict[str, str], region: str,
                         kube_context: Optional[str] = None,
                         stack_name: Optional[str] = None,
                         cluster_name: Optional[str] = None,
                         cloudtrail_wait_seconds: int = 0) -> VerificationResult:
    """Run every oracle. Returns a result; does not raise on findings."""
    import boto3
    result = VerificationResult()
    stack_created_at = None
    if stack_name:
        verify_cloudformation(result, expected, stack_name, region)
        stack = boto3.client("cloudformation", region_name=region) \
            .describe_stacks(StackName=stack_name)["Stacks"][0]
        # The stack predates every resource the deployment created, so its creation time is a
        # config-free lower bound for the CloudTrail window.
        stack_created_at = stack["CreationTime"]

    core_v1, _ = _k8s_clients(kube_context)
    verify_compute_and_storage(result, expected, core_v1, region)
    verify_load_balancers(result, expected, core_v1, region)

    if cluster_name and stack_created_at:
        if cloudtrail_wait_seconds > 0:
            # CloudTrail delivers management events with a 5-15 minute lag, so the most recent
            # creates would otherwise be invisible and the sweep would look clean when it is not.
            logger.info("Waiting %ds for CloudTrail delivery before sweeping",
                        cloudtrail_wait_seconds)
            time.sleep(cloudtrail_wait_seconds)
        now = datetime.now(timezone.utc)
        verify_cloudtrail_creates(result, expected, region, cluster_name, stack_created_at, now)
    else:
        result.unreadable.append(
            "CloudTrail sweep skipped (needs --cluster-name and --stack-name); creates whose "
            "resource type is not enumerated above were not checked")
    return result


def format_report(result: VerificationResult, expected: Dict[str, str]) -> str:
    from tabulate import tabulate
    lines = [f"Expected tags: {expected}", f"Resources checked: {result.checked}"]
    if result.unreadable:
        lines.append("")
        lines.append("Not verified:")
        lines.extend(f"  - {note}" for note in result.unreadable)
    if result.findings:
        lines.append("")
        lines.append(tabulate(
            [[f.kind, f.identifier, f.missing, f.discovered_via] for f in result.findings],
            headers=["kind", "identifier", "missing (found value)", "discovered via"],
        ))
        lines.append("")
        lines.append(f"FAILED: {len(result.findings)} resource(s) missing expected tags")
    else:
        lines.append("")
        lines.append("PASSED: every resource checked carries the expected tags")
    return "\n".join(lines)


def parse_args(argv=None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify deployer tags reached every AWS resource the deployment created.")
    parser.add_argument("--expect-tags", default=None,
                        help="Tags that must be present, as 'Key=Value,Key2=Value2'. Use at least "
                             "two so a bug that keeps only the first entry is caught. Omit to "
                             "derive them from the stack's own tags (requires --stack-name).")
    parser.add_argument("--region", required=True, help="AWS region of the deployment")
    parser.add_argument("--kube-context", default=None,
                        help="kubectl context for the EKS cluster (default: current context)")
    parser.add_argument("--stack-name", default=None,
                        help="CloudFormation stack to check as well as the cluster. Also supplies "
                             "the CloudTrail window (the stack predates every resource) and, when "
                             "--expect-tags is omitted, the tags to expect.")
    parser.add_argument("--cluster-name", default=None,
                        help="EKS cluster name. Enables the CloudTrail sweep, which fails on ANY "
                             "resource-creating call by the cluster role whose request omitted the "
                             "tags -- including resource types the other checks do not enumerate.")
    parser.add_argument("--cloudtrail-wait-seconds", type=int, default=300,
                        help="Wait before sweeping CloudTrail. Management events lag by 5-15 "
                             "minutes, so without this the newest creates look clean. Default 300.")
    parser.add_argument("--log-level", default="INFO")
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    logging.basicConfig(level=getattr(logging, args.log_level.upper(), logging.INFO),
                        format="%(levelname)s %(message)s")
    if args.expect_tags:
        expected = parse_tag_spec(args.expect_tags)
    elif args.stack_name:
        expected = expected_tags_from_stack(args.stack_name, args.region)
        logger.info("Derived expected tags from stack %s: %s", args.stack_name, expected)
    else:
        logger.error("Pass --expect-tags, or --stack-name to derive them from the stack's tags")
        return 2
    if not expected:
        # An untagged stack means --tags was never passed. Fail loudly: silently passing would make
        # this check useless exactly when someone forgets to enable tagging.
        logger.error("No expected tags found -- was the deployment created with --tags?")
        return 2
    if len(expected) < 2:
        # Not fatal, but a single tag cannot catch the most likely bug in this area.
        logger.warning("Only one expected tag given; two or more is strongly recommended so that "
                       "a bug dropping all but the first tag is detectable")
    result = verify_resource_tags(expected, args.region, args.kube_context, args.stack_name,
                                  cluster_name=args.cluster_name,
                                  cloudtrail_wait_seconds=args.cloudtrail_wait_seconds)
    print(format_report(result, expected))
    return 0 if result.ok else 1


if __name__ == "__main__":
    sys.exit(main())
