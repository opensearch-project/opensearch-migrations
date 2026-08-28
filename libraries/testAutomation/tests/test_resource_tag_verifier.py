"""Unit tests for the tag verifier's pure logic.

The AWS-facing paths need a live deployment, but the parts most likely to be wrong -- parsing a
multi-tag spec, deciding what counts as missing, and pulling resource IDs out of Kubernetes objects
without ever consulting a tag -- are all testable offline.
"""

from types import SimpleNamespace

import pytest

from testAutomation.resource_tag_verifier import (
    Finding,
    VerificationResult,
    _check,
    _tag_list_to_dict,
    collect_instance_ids,
    collect_load_balancer_hostnames,
    collect_pv_volume_ids,
    TAGGABLE_CREATE_EVENTS,
    _request_tags,
    format_report,
    is_create_event,
    parse_tag_spec,
)


class TestParseTagSpec:
    def test_parses_two_tags(self):
        # Two tags is the case that matters: a single tag cannot catch a bug that keeps only the
        # first entry of a comma-separated list.
        assert parse_tag_spec("MATestOwner=migrations-ci,MATestStage=esoscdc-p42") == {
            "MATestOwner": "migrations-ci",
            "MATestStage": "esoscdc-p42",
        }

    def test_parses_many_tags(self):
        assert len(parse_tag_spec("a=1,b=2,c=3,d=4")) == 4

    def test_tolerates_whitespace_and_blank_entries(self):
        assert parse_tag_spec(" a = 1 ,, b=2 ,") == {"a": "1", "b": "2"}

    def test_keeps_equals_signs_in_values(self):
        assert parse_tag_spec("k=a=b") == {"k": "a=b"}

    def test_allows_empty_value(self):
        assert parse_tag_spec("k=") == {"k": ""}

    def test_allows_keys_with_dots_and_slashes(self):
        assert parse_tag_spec("my.co/team=data platform") == {"my.co/team": "data platform"}

    @pytest.mark.parametrize("bad", ["novalue", "=v", " = "])
    def test_rejects_malformed(self, bad):
        with pytest.raises(ValueError):
            parse_tag_spec(bad)


class TestCheck:
    expected = {"A": "1", "B": "2"}

    def test_passes_when_all_present(self):
        r = VerificationResult()
        _check(r, "EC2 instance", "i-1", {"A": "1", "B": "2", "Extra": "x"}, self.expected, "test")
        assert r.ok and r.checked == 1

    def test_fails_when_one_tag_missing(self):
        # The single-tag blind spot: A alone present must still fail.
        r = VerificationResult()
        _check(r, "EC2 instance", "i-1", {"A": "1"}, self.expected, "test")
        assert not r.ok
        assert r.findings[0].missing == {"B": None}

    def test_fails_on_value_mismatch_and_reports_what_was_found(self):
        r = VerificationResult()
        _check(r, "EBS volume", "vol-1", {"A": "1", "B": "wrong"}, self.expected, "test")
        assert r.findings[0].missing == {"B": "wrong"}

    def test_fails_when_untagged(self):
        r = VerificationResult()
        _check(r, "load balancer", "arn:lb", {}, self.expected, "test")
        assert r.findings[0].missing == {"A": None, "B": None}


class TestKubernetesDerivedEnumeration:
    """These must never look at tags -- that is the whole point of using the cluster as the index."""

    def test_extracts_instance_ids_from_provider_id(self):
        core = SimpleNamespace(list_node=lambda: SimpleNamespace(items=[
            SimpleNamespace(spec=SimpleNamespace(provider_id="aws:///us-east-1a/i-0abc"),
                            metadata=SimpleNamespace(name="n1")),
            SimpleNamespace(spec=SimpleNamespace(provider_id="aws:///us-east-1b/i-0def"),
                            metadata=SimpleNamespace(name="n2")),
        ]))
        assert collect_instance_ids(core) == ["i-0abc", "i-0def"]

    def test_skips_non_aws_provider_ids(self):
        core = SimpleNamespace(list_node=lambda: SimpleNamespace(items=[
            SimpleNamespace(spec=SimpleNamespace(provider_id="gce://p/z/n"),
                            metadata=SimpleNamespace(name="gke-node")),
            SimpleNamespace(spec=SimpleNamespace(provider_id="aws:///us-east-1a/i-0abc"),
                            metadata=SimpleNamespace(name="n1")),
        ]))
        assert collect_instance_ids(core) == ["i-0abc"]

    def test_extracts_volume_ids_from_pv_csi_handle(self):
        core = SimpleNamespace(list_persistent_volume=lambda: SimpleNamespace(items=[
            SimpleNamespace(spec=SimpleNamespace(csi=SimpleNamespace(volume_handle="vol-0abc"))),
            # A non-CSI PV (hostPath, NFS) has no volume to tag.
            SimpleNamespace(spec=SimpleNamespace(csi=None)),
        ]))
        assert collect_pv_volume_ids(core) == ["vol-0abc"]

    def test_collects_only_loadbalancer_services_with_a_hostname(self):
        def svc(name, type_, ingress):
            return SimpleNamespace(
                spec=SimpleNamespace(type=type_),
                status=SimpleNamespace(load_balancer=SimpleNamespace(ingress=ingress)),
                metadata=SimpleNamespace(name=name, namespace="ma"),
            )
        core = SimpleNamespace(list_service_for_all_namespaces=lambda: SimpleNamespace(items=[
            svc("proxy", "LoadBalancer", [SimpleNamespace(hostname="a.elb.amazonaws.com", ip=None)]),
            svc("clusterip", "ClusterIP", None),
            # Provisioning not finished yet: no hostname to resolve.
            svc("pending", "LoadBalancer", []),
        ]))
        assert collect_load_balancer_hostnames(core) == ["a.elb.amazonaws.com"]


class TestReport:
    def test_tag_list_normalization(self):
        assert _tag_list_to_dict([{"Key": "A", "Value": "1"}, {"Key": "B"}]) == {"A": "1", "B": ""}
        assert _tag_list_to_dict(None) == {}

    def test_report_surfaces_unverified_notes_even_when_passing(self):
        r = VerificationResult(checked=3)
        r.unreadable.append("no LoadBalancer Services found; load balancer tags unverified")
        out = format_report(r, {"A": "1"})
        assert "PASSED" in out
        # A skipped category must be visible: otherwise "PASSED" overstates the coverage.
        assert "load balancer tags unverified" in out

    def test_report_lists_findings_and_fails(self):
        r = VerificationResult(checked=2, findings=[
            Finding("EC2 instance", "i-1", {"B": None}, "Node.spec.providerID")])
        out = format_report(r, {"A": "1", "B": "2"})
        assert "FAILED" in out and "i-1" in out


class TestCloudTrailOracle:
    """The CloudTrail sweep's pure parts: which events count, and where the tags hide."""

    def test_taggable_set_matches_the_iam_policy_we_attach(self):
        # If AutoModeTagPropagationPolicy in eks-infra.ts grows or loses an action, this set has to
        # move with it -- otherwise an untagged create gets filed under "no AWS mechanism" and looks
        # like someone else's problem instead of our bug.
        assert TAGGABLE_CREATE_EVENTS == {
            "CreateFleet", "RunInstances", "CreateLaunchTemplate",
            "CreateVolume", "CreateSnapshot",
            "CreateNetworkInterface",
            "CreateLoadBalancer", "CreateTargetGroup", "CreateListener", "CreateRule",
            "CreateSecurityGroup",
        }

    @pytest.mark.parametrize("name", [
        "RunInstances", "CreateVolume", "CreateLoadBalancer",
        # Deliberately broad: the point is to catch creates nobody enumerated.
        "CreateSomethingBrandNew", "AllocateAddress", "ProvisionByoipCidr", "RegisterTargets",
    ])
    def test_recognizes_creates(self, name):
        assert is_create_event(name)

    @pytest.mark.parametrize("name", [
        "DeleteVolume", "TerminateInstances", "ModifyInstanceAttribute", "AssumeRole", "TagResource",
    ])
    def test_ignores_non_creates(self, name):
        assert not is_create_event(name)

    def test_extracts_ec2_tag_specification_set_with_lowercase_keys(self):
        # CloudTrail lowercases EC2 request parameters, unlike the describe APIs.
        detail = {"requestParameters": {"tagSpecificationSet": {"items": [
            {"resourceType": "instance",
             "tags": [{"key": "MATestOwner", "value": "migrations-ci"},
                      {"key": "MATestStage", "value": "esoscdc-p1"}]}]}}}
        assert _request_tags(detail) == {"MATestOwner": "migrations-ci",
                                         "MATestStage": "esoscdc-p1"}

    def test_extracts_elbv2_style_tags(self):
        detail = {"requestParameters": {"tags": [{"key": "A", "value": "1"}]}}
        assert _request_tags(detail) == {"A": "1"}

    def test_accepts_capitalized_spelling_too(self):
        detail = {"requestParameters": {"tags": [{"Key": "A", "Value": "1"}]}}
        assert _request_tags(detail) == {"A": "1"}

    def test_untagged_create_yields_no_tags(self):
        assert _request_tags({"requestParameters": {}}) == {}
        assert _request_tags({}) == {}

    def test_tolerates_junk_shapes(self):
        # CloudTrail request parameters are not schema-guaranteed; a crash here would mask real
        # findings in the rest of the sweep.
        assert _request_tags({"requestParameters": {"tags": ["not-a-dict", None]}}) == {}
        assert _request_tags({"requestParameters": {"tagSpecificationSet": {"items": [None]}}}) == {}


class TestDeniedCreates:
    """A refused create is the strongest signal available: it names the failing action."""

    def test_denied_creates_fail_the_run_even_with_no_tag_findings(self):
        r = VerificationResult(checked=5)
        r.denied.append("CreateNetworkInterface DENIED (AccessDenied) at ...: explicit deny")
        assert not r.ok

    def test_report_leads_with_denials(self):
        r = VerificationResult(checked=5)
        r.denied.append("CreateNetworkInterface DENIED (AccessDenied) at T: explicit deny in policy")
        out = format_report(r, {"A": "1"})
        assert "DENIED CREATES (1)" in out
        assert "CreateNetworkInterface" in out
        assert "FAILED" in out
        # A denial must not be reported as a pass just because no resource was found untagged.
        assert "PASSED" not in out

    def test_clean_run_still_passes(self):
        assert VerificationResult(checked=3).ok
        assert "PASSED" in format_report(VerificationResult(checked=3), {"A": "1"})
