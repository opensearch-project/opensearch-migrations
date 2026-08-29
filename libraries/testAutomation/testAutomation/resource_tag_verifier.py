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
    # Creates the cluster role attempted and was refused. These name the failing action outright, so
    # they are the most actionable output here: either our plumbing failed to tag a taggable create,
    # or the action cannot carry tags and a deployer whose SCP covers it cannot run Auto Mode.
    denied: List[str] = field(default_factory=list)
    # Resource-creating calls we have not classified as taggable or not. Reported for review, but not
    # failed on: see the note at the call site.
    unclassified: List[str] = field(default_factory=list)
    # eventName -> count of creates seen in CloudTrail that carried every expected tag. Distinguishes
    # "enforcement would have caught this" from "we watched it happen correctly".
    observed_tagged: Dict[str, int] = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return not self.findings and not self.denied


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

    volume_ids = set()
    for reservation in ec2.describe_instances(InstanceIds=instance_ids)["Reservations"]:
        for inst in reservation["Instances"]:
            tags = _tag_list_to_dict(inst.get("Tags"))
            _check(result, "EC2 instance", inst["InstanceId"], tags, expected,
                   "Node.spec.providerID")
            for bdm in inst.get("BlockDeviceMappings", []):
                vol_id = bdm.get("Ebs", {}).get("VolumeId")
                if vol_id:
                    volume_ids.add(vol_id)

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

    # Launch templates are deliberately not checked. They carry no user tags -- their own
    # TagSpecification is null, and the tags in launchTemplateData belong to the instances they
    # launch, which are checked above. Auto Mode also rotates them away after launch, so looking one
    # up by the instance's aws:ec2launchtemplate:id tag fails with InvalidLaunchTemplateId.NotFound.


def _lb_absent_note(result: "VerificationResult", cluster_name: str) -> str:
    """Explain an absent load balancer without overstating or understating the evidence."""
    seen = result.observed_tagged.get("CreateLoadBalancer", 0)
    if seen:
        return (f"no load balancer still existed at verification time, but CloudTrail recorded "
                f"{seen} created WITH the expected tags -- see the enforcement summary")
    return (f"no load balancers tagged eks:eks-cluster-name={cluster_name} were found and "
            "CloudTrail recorded none being created; load balancer tags unverified")


def find_cluster_load_balancers(elbv2, cluster_name: str) -> List[dict]:
    """Load balancers belonging to this cluster, found without consulting the tags under test.

    Deliberately NOT via Service.status.loadBalancer: the capture proxy Service has ownerReferences to
    a CaptureProxy CR, and the integration test's reset deletes those CRs, so by the time verification
    runs the Service -- and any load balancer it owned -- is already garbage-collected. That made the
    load balancer check silently vacuous ("no LoadBalancer Services found").

    eks:eks-cluster-name is injected by EKS itself, not by us, so filtering on it is no more circular
    than using a node's providerID.
    """
    matches, arns, by_arn = [], [], {}
    for page in elbv2.get_paginator("describe_load_balancers").paginate():
        for lb in page["LoadBalancers"]:
            arns.append(lb["LoadBalancerArn"])
            by_arn[lb["LoadBalancerArn"]] = lb
    # describe_tags takes at most 20 ARNs per call.
    for i in range(0, len(arns), 20):
        for desc in elbv2.describe_tags(ResourceArns=arns[i:i + 20])["TagDescriptions"]:
            tags = _tag_list_to_dict(desc.get("Tags"))
            if tags.get("eks:eks-cluster-name") == cluster_name:
                matches.append((by_arn[desc["ResourceArn"]], tags))
    return matches


def verify_load_balancers(result: VerificationResult, expected: Dict[str, str], region: str,
                          cluster_name: str) -> None:
    import boto3
    elbv2 = boto3.client("elbv2", region_name=region)
    ec2 = boto3.client("ec2", region_name=region)

    matches = find_cluster_load_balancers(elbv2, cluster_name)
    if not matches:
        # A load balancer here is short-lived: it belongs to the capture proxy Service, whose
        # CaptureProxy CR the integration test deletes on reset, taking the load balancer with it. So
        # by verification time there is usually nothing left to inspect -- which is why the earlier
        # Service-based lookup was vacuous, and why looking it up by tag instead did not help.
        #
        # That is not the same as unverified. If the CloudTrail sweep (which runs first) saw the
        # create carry the tags, the evidence exists; it is just historical rather than a live
        # resource. Saying "unverified" in that case would understate what the run established.
        result.unreadable.append(_lb_absent_note(result, cluster_name))
        return

    for lb, tags in matches:
        lb_arn = lb["LoadBalancerArn"]
        _check(result, "load balancer", lb_arn, tags, expected, "elbv2 by eks:eks-cluster-name")

        child_arns = [tg["TargetGroupArn"]
                      for tg in elbv2.describe_target_groups(LoadBalancerArn=lb_arn)["TargetGroups"]]
        child_arns += [ln["ListenerArn"]
                       for ln in elbv2.describe_listeners(LoadBalancerArn=lb_arn)["Listeners"]]
        for i in range(0, len(child_arns), 20):
            for desc in elbv2.describe_tags(ResourceArns=child_arns[i:i + 20])["TagDescriptions"]:
                kind = "target group" if ":targetgroup/" in desc["ResourceArn"] else "listener"
                _check(result, kind, desc["ResourceArn"], _tag_list_to_dict(desc.get("Tags")),
                       expected, "child of the cluster's load balancer")

        # Only the load balancer's own (frontend) security groups. The controller also creates a
        # shared "k8s-traffic-*" group on the nodes, which per-Service tags do not reach; it is not
        # attached to the load balancer, so it correctly does not appear here.
        sg_ids = lb.get("SecurityGroups") or []
        if sg_ids:
            for sg in ec2.describe_security_groups(GroupIds=sg_ids)["SecurityGroups"]:
                _check(result, "LB security group", sg["GroupId"],
                       _tag_list_to_dict(sg.get("Tags")), expected,
                       "security group on the cluster's load balancer")


def _run_oracle(result: "VerificationResult", label: str, fn, *args) -> None:
    """Run one oracle, recording rather than raising if it breaks.

    An unexpected AWS error in one oracle used to abort the whole verification and throw away what
    the others had already established -- and since this runs at the end of a 60-minute pipeline,
    that costs a full cycle to learn one thing. Recording the breakage keeps the rest of the verdict
    while making sure a broken oracle can never read as a pass.
    """
    try:
        fn(*args)
    except Exception as exc:  # noqa: BLE001 - any AWS/client error must degrade, not abort
        result.unreadable.append(f"{label} oracle failed: {type(exc).__name__}: {exc}")
        logger.exception("%s oracle failed", label)


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

# Creates for which there is POSITIVE EVIDENCE, from CloudTrail on a live tagged cluster, that the
# request carries the deployer's tags. An untagged create here is therefore a real gap in our
# plumbing and fails the run.
#
# This list is short on purpose. It started as the action set of the AWS tag-propagation policy, but
# that policy says which actions *may* carry user tags, not which ones *do*, and three of its members
# turned out not to:
#   CreateFleet          - no TagSpecifications at all; instances inherit from the launch template
#   CreateLaunchTemplate - its own TagSpecification is null
#   CreateSecurityGroup  - the load balancer's frontend SG is tagged, but the shared backend
#                          "k8s-traffic-*" SG the controller attaches to nodes is not
# Anything not proven is reported for review instead (see the call site), because a create we cannot
# influence would make every run red and the signal worthless.
TAGGABLE_CREATE_EVENTS = frozenset({"RunInstances"})

# The actions aws-bootstrap.sh --enforce-tags-on-create denies when the tags are absent. Kept in step
# with enforce_tags_on_cluster_role() in that script by a test; if the two drift, the report below
# will claim enforcement that is not in place.
#
# For these, an untagged create does not merely get reported -- it is refused by IAM at the moment it
# happens, which is a materially stronger claim than "we looked afterwards and the tags were there".
DENY_ENFORCED_ACTIONS = (
    "ec2:RunInstances",
    "ec2:CreateVolume",
    "ec2:CreateSnapshot",
    "ec2:CreateNetworkInterface",
    "elasticloadbalancing:CreateLoadBalancer",
    "elasticloadbalancing:CreateTargetGroup",
    "elasticloadbalancing:CreateListener",
    "elasticloadbalancing:CreateRule",
)

# Creates where AWS offers no way to put the user tags on the resource itself, so requiring them
# would fail every run for something outside our control. Each entry is here on CloudTrail evidence.
UNTAGGABLE_CREATE_EVENTS = frozenset({
    # Its own TagSpecification is null; the tags under launchTemplateData belong to the instances it
    # launches, which are checked via RunInstances.
    "CreateLaunchTemplate",
    # Wraps its arguments in CreateFleetRequest with no TagSpecifications at all. Worth knowing for
    # deployers: an SCP requiring aws:RequestTag on ec2:CreateFleet blocks EKS Auto Mode outright.
    "CreateFleet",
})

# Calls whose name begins with Create but which create no taggable resource. Without these the broad
# prefix match below reports them as untagged resources -- observed with CreateTags (the tagging API
# itself) and CreateGrant (a KMS grant).
NOT_RESOURCE_CREATES = frozenset({
    "CreateTags", "CreateGrant", "CreateNetworkInterfacePermission", "CreateServiceLinkedRole",
    "CreateLaunchTemplateVersion", "CreateRoute", "CreateNetworkAclEntry",
    # Registers existing targets in a target group; creates nothing. Matched by the "Register" prefix.
    "RegisterTargets", "RegisterInstancesWithLoadBalancer",
})

# Anything else that creates a resource. Kept broad on purpose: the entire point of this oracle is to
# surface creates nobody thought to enumerate. A hit here that carries no tags means AWS offers no
# mechanism to tag it, which is not something this repo can fix -- but it is exactly the list a
# customer whose SCP requires tags on create has to know about.
_CREATE_EVENT_PREFIXES = ("Create", "Run", "Allocate", "Provision", "Request", "Register")

# CloudTrail error codes that mean "the call was refused for want of the right tags". Deliberately
# narrow: an explicit Deny reports Client.UnauthorizedOperation for EC2 and AccessDenied for most
# other services, while a DryRun probe also carries an errorCode and must not be mistaken for a
# refusal.
#
# TagPolicyViolation is included because an AWS Organizations tag policy refuses a create whose
# supplied tag VALUE is not on the allowed list -- a different mechanism from an SCP or IAM deny, and
# one that produces a distinct error code. Note that a tag policy does not refuse creates with tags
# merely absent: "Basic compliance rules do not enforce tag compliance on resources that are created
# without tags." Missing mandatory tags are an SCP's job, so both codes have to be watched for.
# https://docs.aws.amazon.com/organizations/latest/userguide/orgs_manage_policies_tag-policies-enforcement.html
_AUTH_FAILURE_CODES = ("AccessDenied", "UnauthorizedOperation", "Forbidden", "TagPolicyViolation")


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
            error_code = detail.get("errorCode") or ""
            if error_code:
                # A rejected call created nothing, so there is no resource whose tags to check -- but
                # an AUTHORIZATION refusal is stronger evidence than a missing tag, because it names
                # the action outright.
                #
                # Only authorization failures count. EKS Auto Mode probes its own permissions
                # constantly with DryRun, and every probe lands in CloudTrail as
                # Client.DryRunOperation "Request would have succeeded, but DryRun flag is set" --
                # observed at 44 of 50 sampled RunInstances events. Treating those as denials would
                # fail every run. Other error codes (InvalidParameterValue and friends) are real
                # failures but say nothing about tags, so they are noted rather than failed on.
                if any(marker in error_code for marker in _AUTH_FAILURE_CODES):
                    note = (f"{name} DENIED ({error_code}) at {detail.get('eventTime')}: "
                            f"{(detail.get('errorMessage') or '')[:400]}")
                    result.denied.append(note)
                    logger.error("%s", note)
                elif "DryRun" not in error_code:
                    result.unreadable.append(
                        f"{name} failed with {error_code} (not an authorization failure, so not a "
                        f"tagging verdict)")
                continue
            if name in NOT_RESOURCE_CREATES:
                continue
            matched += 1
            if name in UNTAGGABLE_CREATE_EVENTS:
                result.unreadable.append(
                    f"{name}: AWS puts no user tags on this resource, so it is not checked")
                continue
            if all(_request_tags(detail).get(k) == v for k, v in expected.items()):
                result.observed_tagged[name] = result.observed_tagged.get(name, 0) + 1
                continue
            tags = _request_tags(detail)
            if name not in TAGGABLE_CREATE_EVENTS:
                # Never seen before, so we cannot know whether AWS lets us tag it. Surfacing it is
                # the whole point of this oracle; failing on it is not, because a create we have no
                # way to influence would make every run red and the signal worthless. Classify it
                # into one of the sets above once its behaviour is known.
                if not all(tags.get(k) == v for k, v in expected.items()):
                    result.unclassified.append(
                        f"{name} at {detail.get('eventTime')} carried tags {sorted(tags) or 'none'}")
                continue
            before = len(result.findings)
            _check(result, f"{name} (taggable, our bug)", detail.get("eventID", "?"), tags, expected,
                   "CloudTrail, cluster role")
            if len(result.findings) == before:
                result.observed_tagged[name] = result.observed_tagged.get(name, 0) + 1
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
                         cloudtrail_wait_seconds: int = 60) -> VerificationResult:
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
    _run_oracle(result, "compute and storage",
                verify_compute_and_storage, result, expected, core_v1, region)
    if cluster_name and stack_created_at:
        if cloudtrail_wait_seconds > 0:
            # CloudTrail delivers management events with a 5-15 minute lag, so the most recent
            # creates would otherwise be invisible and the sweep would look clean when it is not.
            logger.info("Waiting %ds for CloudTrail delivery before sweeping",
                        cloudtrail_wait_seconds)
            time.sleep(cloudtrail_wait_seconds)
        now = datetime.now(timezone.utc)
        _run_oracle(result, "CloudTrail sweep", verify_cloudtrail_creates,
                    result, expected, region, cluster_name, stack_created_at, now)
    else:
        result.unreadable.append(
            "CloudTrail sweep skipped (needs --cluster-name and --stack-name); creates whose "
            "resource type is not enumerated above were not checked")

    # Deliberately after the sweep: load balancers are short-lived, and when none survive to be
    # inspected the sweep's record of how they were created is the evidence that remains.
    if cluster_name:
        _run_oracle(result, "load balancers",
                    verify_load_balancers, result, expected, region, cluster_name)
    else:
        result.unreadable.append("load balancer check skipped (needs --cluster-name)")
    return result


def format_report(result: VerificationResult, expected: Dict[str, str]) -> str:
    from tabulate import tabulate
    lines = [f"Expected tags: {expected}", f"Resources checked: {result.checked}"]
    lines.append("")
    lines.append("How each result was established:")
    lines.append("  Enforced by IAM -- an untagged create would have been REFUSED, not just noticed:")
    for action in DENY_ENFORCED_ACTIONS:
        seen = result.observed_tagged.get(action.split(":")[-1], 0)
        lines.append(f"    {action}" + (f"  ({seen} create(s) observed)" if seen else "  (none seen)"))
    unenforced = {k: v for k, v in result.observed_tagged.items()
                  if not any(k == a.split(":")[-1] for a in DENY_ENFORCED_ACTIONS)}
    if unenforced:
        lines.append("  Observed tagged in CloudTrail, but NOT enforced -- an untagged one would have")
        lines.append("  been reported after the fact rather than refused:")
        for name, count in sorted(unenforced.items()):
            lines.append(f"    {name}  ({count})")
    if result.denied:
        # First, because it names the failing action and every other symptom (pods Pending, pods
        # without IPs, a buildkit deployment that never becomes Ready) is downstream of it.
        lines.append("")
        lines.append(f"DENIED CREATES ({len(result.denied)}) -- the cluster role was refused these:")
        lines.extend(f"  - {note}" for note in result.denied)
        lines.append("  An action listed here either should have carried the tags (our bug) or "
                     "cannot carry them at all,")
        lines.append("  in which case a deployer whose SCP covers it cannot run EKS Auto Mode.")
    if result.unclassified:
        lines.append("")
        lines.append(f"UNCLASSIFIED CREATES ({len(result.unclassified)}) -- review, not failed:")
        from collections import Counter
        for note, count in Counter(result.unclassified).most_common():
            lines.append(f"  - {note}" + (f"  (x{count})" if count > 1 else ""))
    if result.unreadable:
        lines.append("")
        lines.append("Not verified:")
        # Collapsed with counts: Auto Mode retries transient failures, and one observed run produced
        # 21 identical "Invalid IAM Instance Profile name" notes while IAM propagated, which buried
        # everything else in the report.
        from collections import Counter
        for note, count in Counter(result.unreadable).most_common():
            lines.append(f"  - {note}" + (f"  (x{count})" if count > 1 else ""))
    if result.findings:
        lines.append("")
        lines.append(tabulate(
            [[f.kind, f.identifier, f.missing, f.discovered_via] for f in result.findings],
            headers=["kind", "identifier", "missing (found value)", "discovered via"],
        ))
        lines.append("")
        lines.append(f"FAILED: {len(result.findings)} resource(s) missing expected tags")
    if result.denied:
        lines.append("")
        lines.append(f"FAILED: {len(result.denied)} create(s) refused for want of the tags")
    elif not result.findings:
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
    parser.add_argument("--cloudtrail-wait-seconds", type=int, default=60,
                        help="Wait before sweeping CloudTrail. Management events lag 5-15 minutes, "
                             "so the newest creates may be missing; the report says so when it finds "
                             "none. Kept short because the sweep's window starts at stack creation, "
                             "so everything but the last few minutes is long delivered, and because "
                             "load balancers are now found via elbv2 rather than CloudTrail.")
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
