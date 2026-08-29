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
    _run_oracle,
    _lb_absent_note,
    _tag_list_to_dict,
    collect_instance_ids,
    find_cluster_load_balancers,
    collect_pv_volume_ids,
    TAGGABLE_CREATE_EVENTS,
    UNTAGGABLE_CREATE_EVENTS,
    NOT_RESOURCE_CREATES,
    DENY_ENFORCED_ACTIONS,
    _AUTH_FAILURE_CODES,
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

    def test_only_proven_creates_are_enforced(self):
        # Deliberately just RunInstances: it is the only create observed on a live tagged cluster to
        # carry the deployer's tags in its request. CreateFleet, CreateLaunchTemplate and
        # CreateSecurityGroup were each in this set and each produced a false failure.
        assert TAGGABLE_CREATE_EVENTS == {"RunInstances"}

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


class TestDryRunIsNotADenial:
    """Auto Mode probes its permissions with DryRun constantly; those must not read as refusals.

    Observed in the migrations test account: 44 of 50 sampled RunInstances CloudTrail events were
    Client.DryRunOperation. Counting those as denials would fail every single run.
    """

    def test_dryrun_is_not_an_auth_failure(self):
        assert not any(m in "Client.DryRunOperation" for m in _AUTH_FAILURE_CODES)

    @pytest.mark.parametrize("code", [
        "AccessDenied",
        "Client.UnauthorizedOperation",   # what EC2 returns for an explicit Deny
        "UnauthorizedOperation",
        "Client.AccessDenied",
        # An Organizations tag policy refusing a non-compliant tag VALUE reports its own code.
        "TagPolicyViolation",
    ])
    def test_real_refusals_are_recognized(self, code):
        assert any(m in code for m in _AUTH_FAILURE_CODES)

    @pytest.mark.parametrize("code", [
        "Client.DryRunOperation", "Client.InvalidParameterValue",
        "Client.InsufficientInstanceCapacity", "RequestLimitExceeded",
    ])
    def test_non_authorization_errors_are_not_refusals(self, code):
        assert not any(m in code for m in _AUTH_FAILURE_CODES)


def test_test_runner_uses_no_relative_imports():
    """test_runner.py is executed as a script, so it has no parent package.

    A relative import there fails at runtime with "attempted relative import with no known parent
    package" -- and only on the code path that triggers it, so it survives both unit tests (which
    import the package normally) and a successful migration run. That is exactly how it escaped:
    build #470's migration passed and then died on the verification hook.
    """
    from pathlib import Path
    src = (Path(__file__).parent.parent / "testAutomation" / "test_runner.py").read_text()
    offenders = [ln.strip() for ln in src.splitlines()
                 if ln.strip().startswith("from .") or ln.strip().startswith("import .")]
    assert offenders == []


class TestUntaggableExemptions:
    def test_launch_template_is_exempt_not_a_failure(self):
        # Its own TagSpecification is null in CloudTrail; the user tags in launchTemplateData are for
        # the instances it launches. Treating it as our bug made a live smoke test report FAILED.
        assert "CreateLaunchTemplate" in UNTAGGABLE_CREATE_EVENTS
        assert "CreateLaunchTemplate" not in TAGGABLE_CREATE_EVENTS

    def test_exempt_and_enforceable_sets_are_disjoint(self):
        assert not (UNTAGGABLE_CREATE_EVENTS & TAGGABLE_CREATE_EVENTS)

    def test_repeated_notes_are_collapsed_with_a_count(self):
        r = VerificationResult(checked=1)
        for _ in range(21):
            r.unreadable.append("RunInstances failed with Client.InvalidParameterValue")
        out = format_report(r, {"A": "1"})
        assert "(x21)" in out
        assert out.count("RunInstances failed with Client.InvalidParameterValue") == 1


class TestClassificationFromLiveEvidence:
    """Every entry here was moved on evidence from a live cluster, not on reasoning."""

    def test_create_fleet_is_exempt(self):
        # CreateFleet carries no TagSpecifications; its instances inherit tags from the launch
        # template. The instances were verifiably tagged, so an untagged CreateFleet is normal.
        assert "CreateFleet" in UNTAGGABLE_CREATE_EVENTS
        assert "CreateFleet" not in TAGGABLE_CREATE_EVENTS

    @pytest.mark.parametrize("name", ["CreateTags", "CreateGrant"])
    def test_non_resource_creates_are_excluded(self, name):
        # Both were reported as untagged resources by the broad Create* prefix match.
        assert name in NOT_RESOURCE_CREATES

    def test_the_three_sets_do_not_overlap(self):
        assert not (TAGGABLE_CREATE_EVENTS & UNTAGGABLE_CREATE_EVENTS)
        assert not (TAGGABLE_CREATE_EVENTS & NOT_RESOURCE_CREATES)
        assert not (UNTAGGABLE_CREATE_EVENTS & NOT_RESOURCE_CREATES)

    def test_unclassified_creates_are_reported_but_do_not_fail(self):
        r = VerificationResult(checked=1)
        r.unclassified.append("CreateSomethingNew at T carried tags none")
        assert r.ok, "an unknown create must not fail the run"
        out = format_report(r, {"A": "1"})
        assert "UNCLASSIFIED CREATES (1)" in out and "CreateSomethingNew" in out


class TestOracleResilience:
    """One oracle breaking must not discard the others -- this runs at the end of a 60-min pipeline."""

    def test_broken_oracle_is_recorded_not_raised(self):
        r = VerificationResult(checked=2)

        def boom():
            raise RuntimeError("InvalidLaunchTemplateId.NotFound")

        _run_oracle(r, "compute and storage", boom)
        assert any("compute and storage oracle failed" in n for n in r.unreadable)
        assert "RuntimeError" in " ".join(r.unreadable)

    def test_broken_oracle_cannot_read_as_a_clean_pass(self):
        r = VerificationResult(checked=2)
        _run_oracle(r, "CloudTrail sweep", lambda: (_ for _ in ()).throw(ValueError("nope")))
        out = format_report(r, {"A": "1"})
        # It may still say PASSED for what it checked, but the breakage must be visible.
        assert "CloudTrail sweep oracle failed" in out

    def test_successful_oracle_records_nothing(self):
        r = VerificationResult()
        _run_oracle(r, "x", lambda: None)
        assert r.unreadable == []


class TestLoadBalancerDiscovery:
    """Found via the AWS-set eks:eks-cluster-name tag, not via the Service.

    The capture proxy Service is owned by a CaptureProxy CR that the integration test's reset
    deletes, so by verification time the Service and its load balancer are garbage-collected. The
    Service-based lookup therefore reported "no LoadBalancer Services found" on every run and the
    check was silently vacuous.
    """

    class FakeElbv2:
        def __init__(self, lbs, tags):
            self._lbs, self._tags = lbs, tags

        def get_paginator(self, _name):
            outer = self

            class P:
                def paginate(self):
                    return [{"LoadBalancers": outer._lbs}]
            return P()

        def describe_tags(self, ResourceArns):
            return {"TagDescriptions": [{"ResourceArn": a,
                                         "Tags": [{"Key": k, "Value": v}
                                                  for k, v in self._tags.get(a, {}).items()]}
                                        for a in ResourceArns]}

    def test_selects_only_this_clusters_load_balancers(self):
        lbs = [{"LoadBalancerArn": "arn:ours"}, {"LoadBalancerArn": "arn:someone-elses"}]
        tags = {"arn:ours": {"eks:eks-cluster-name": "my-cluster", "MATestOwner": "x"},
                "arn:someone-elses": {"eks:eks-cluster-name": "other-cluster"}}
        found = find_cluster_load_balancers(self.FakeElbv2(lbs, tags), "my-cluster")
        assert [lb["LoadBalancerArn"] for lb, _ in found] == ["arn:ours"]

    def test_returns_the_tags_so_they_are_not_re_fetched(self):
        lbs = [{"LoadBalancerArn": "arn:ours"}]
        tags = {"arn:ours": {"eks:eks-cluster-name": "c", "MATestOwner": "migrations-ci"}}
        (_, got), = find_cluster_load_balancers(self.FakeElbv2(lbs, tags), "c")
        assert got["MATestOwner"] == "migrations-ci"

    def test_no_match_is_not_an_error(self):
        assert find_cluster_load_balancers(self.FakeElbv2([], {}), "c") == []


class TestEnforcementReport:
    """The report must not claim enforcement the IAM policy does not actually provide."""

    def test_deny_list_matches_the_bootstrap_policy(self):
        """DENY_ENFORCED_ACTIONS must equal what enforce_tags_on_cluster_role() denies.

        If they drift, the report tells the reader an untagged create would have been refused when
        nothing would have stopped it -- the worst possible failure for this tool, because it
        overstates the strength of a passing run.
        """
        import re
        from pathlib import Path
        repo = Path(__file__).parent.parent.parent.parent
        script = (repo / "deployment/k8s/aws/aws-bootstrap.sh").read_text()
        body = script[script.index("enforce_tags_on_cluster_role() {"):]
        body = body[:body.index('\n}\n')]
        in_policy = set(re.findall(r'"((?:ec2|elasticloadbalancing):Create[A-Za-z]+|ec2:RunInstances)"',
                                   body))
        assert in_policy == set(DENY_ENFORCED_ACTIONS), (
            f"only in policy: {in_policy - set(DENY_ENFORCED_ACTIONS)}; "
            f"only in report: {set(DENY_ENFORCED_ACTIONS) - in_policy}")

    def test_report_separates_enforced_from_merely_observed(self):
        r = VerificationResult(checked=3)
        r.observed_tagged["RunInstances"] = 4       # enforced
        r.observed_tagged["CreateSecurityGroup"] = 1  # observed only
        out = format_report(r, {"A": "1"})
        assert "Enforced by IAM" in out
        assert "ec2:RunInstances  (4 create(s) observed)" in out
        assert "NOT enforced" in out
        assert "CreateSecurityGroup  (1)" in out

    def test_enforced_actions_with_nothing_seen_are_marked(self):
        out = format_report(VerificationResult(checked=1), {"A": "1"})
        assert "(none seen)" in out


class TestEphemeralLoadBalancerReporting:
    """A load balancer that no longer exists is not the same as one that was never verified.

    The capture proxy's load balancer is deleted with its CaptureProxy CR during the test's reset, so
    by verification time there is usually nothing live to inspect. Build #476 reported both
    "CreateLoadBalancer (1 create(s) observed)" and "load balancer tags unverified" -- contradictory,
    and the second half understated what the run had established.
    """

    def test_cloudtrail_evidence_replaces_the_unverified_claim(self):
        r = VerificationResult()
        r.observed_tagged["CreateLoadBalancer"] = 1
        note = _lb_absent_note(r, "my-cluster")
        assert "unverified" not in note
        assert "CloudTrail recorded 1 created WITH the expected tags" in note

    def test_no_evidence_anywhere_still_says_unverified(self):
        note = _lb_absent_note(VerificationResult(), "my-cluster")
        assert "unverified" in note
        assert "my-cluster" in note
