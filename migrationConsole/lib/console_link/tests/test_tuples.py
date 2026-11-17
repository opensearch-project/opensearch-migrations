import base64
import datetime as dt
import gzip
import io
import json
import os

import boto3
import pytest
from moto import mock_aws

from console_link.middleware import tuples as t

UTC = dt.timezone.utc


def _replay(name="capture-proxy-target-replay1", source="source", target="target", bucket="tuple-bucket",
            prefix="tuples/", region="us-east-2", endpoint=""):
    return {"metadata": {"name": name},
            "spec": {"sourceLabel": source, "targetLabel": target, "tupleS3Bucket": bucket,
                     "tupleS3Prefix": prefix, "tupleS3Region": region, "tupleS3Endpoint": endpoint}}


def _gz(*lines):
    return gzip.compress("".join(json.dumps(line) + "\n" for line in lines).encode())


def _tuple(n):
    return {"connectionId": f"c{n}", "sourceRequest": {"Request-URI": f"/doc/{n}"}}


# Selection

def test_select_the_only_replayer_without_a_selector():
    assert t.select_traffic_replay([_replay()])["metadata"]["name"] == "capture-proxy-target-replay1"


def test_select_by_name_source_or_target():
    items = [_replay("r-a", target="a"), _replay("r-b", target="b")]
    assert t.select_traffic_replay(items, replayer="r-b")["metadata"]["name"] == "r-b"
    assert t.select_traffic_replay(items, target="a")["metadata"]["name"] == "r-a"


def test_several_replayers_need_a_selector():
    with pytest.raises(t.TupleSelectionError, match="choose one with --replayer.*r-a, r-b"):
        t.select_traffic_replay([_replay("r-a"), _replay("r-b")])


def test_no_match_lists_the_replayers():
    with pytest.raises(t.TupleSelectionError, match="Replayers: r-a"):
        t.select_traffic_replay([_replay("r-a")], replayer="nope")
    with pytest.raises(t.TupleSelectionError, match="No traffic replayers are deployed"):
        t.select_traffic_replay([])


def test_a_replayer_without_a_tuple_bucket_is_reported():
    with pytest.raises(t.TupleSelectionError, match="no tupleS3Bucket"):
        t.tuple_location(_replay(bucket=""))


def test_location_normalizes_the_prefix():
    assert t.tuple_location(_replay(prefix="tuples")).prefix == "tuples/"


def test_keys_belong_only_to_their_own_replayer():
    loc = t.tuple_location(_replay(name="r"))
    assert t._belongs_to_replayer("tuples/r-544c4f8f6b-2jj4j_4d6f/2026/10/14/05/tuples-1.log.gz", loc)
    assert not t._belongs_to_replayer("tuples/r-b-544c4f8f6b-2jj4j_4d6f/2026/x.log.gz", loc)


# Reading

def test_binary_bodies_that_are_text_are_decoded():
    text = base64.b64encode(b'{"a": 1}').decode()
    parsed = t.parse_tuple(json.dumps({"sourceResponse": {"payload": {"inlinedBinaryBody": text}}}))
    assert parsed["sourceResponse"]["payload"] == {"inlinedTextBody": '{"a": 1}'}
    binary = base64.b64encode(b"\xff\xfe").decode()
    parsed = t.parse_tuple(json.dumps({"targetResponses": [{"payload": {"inlinedBinaryBody": binary}}]}))
    assert parsed["targetResponses"][0]["payload"] == {"inlinedBinaryBody": binary}


def test_reads_plain_and_gzipped_files():
    assert [x["connectionId"] for x in t.read_tuples(io.BytesIO(_gz(_tuple(1), _tuple(2))))] == ["c1", "c2"]
    plain = io.BytesIO((json.dumps(_tuple(3)) + "\n\n").encode())
    assert [x["connectionId"] for x in t.read_tuples(plain)] == ["c3"]


class _FakeSource:
    def __init__(self, files):
        self.files = files  # key -> (last_modified, bytes)

    def objects(self):
        return [t.TupleObject(key=k, last_modified=m) for k, (m, _) in self.files.items()]

    def open(self, obj):
        return io.BytesIO(self.files[obj.key][1])


def test_newest_tuples_keeps_the_most_recent_in_order():
    base = dt.datetime(2026, 10, 14, 5, 0, tzinfo=UTC)
    source = _FakeSource({
        "a": (base, _gz(_tuple(1), _tuple(2))),
        "b": (base + dt.timedelta(minutes=1), _gz(_tuple(3), _tuple(4), _tuple(5))),
    })
    assert [x["connectionId"] for x in t.newest_tuples(source, limit=4)] == ["c2", "c3", "c4", "c5"]
    assert [x["connectionId"] for x in t.newest_tuples(source, since=base + dt.timedelta(seconds=30))] \
        == ["c3", "c4", "c5"]


def test_parse_since():
    now = dt.datetime(2026, 10, 14, 5, 0, tzinfo=UTC)
    assert t.parse_since("15m", now) == now - dt.timedelta(minutes=15)
    assert t.parse_since(None) is None
    with pytest.raises(ValueError):
        t.parse_since("soon")


# Sources

def test_reads_from_the_artifacts_mount_when_the_bucket_is_mounted(tmp_path, monkeypatch):
    monkeypatch.setenv("REPO_ARTIFACTS_BUCKET", "s3://tuple-bucket")
    pod_dir = tmp_path / "tuples" / "capture-proxy-target-replay1-544c4f8f6b-2jj4j_4d6f" / "2026" / "10"
    pod_dir.mkdir(parents=True)
    (pod_dir / "tuples-1.log.gz").write_bytes(_gz(_tuple(1)))
    other = tmp_path / "tuples" / "other-replayer-1a2b3c-xyz_9" / "2026"
    other.mkdir(parents=True)
    (other / "tuples-1.log.gz").write_bytes(_gz(_tuple(9)))
    source = t.tuple_source(t.tuple_location(_replay()), mount=str(tmp_path))
    assert isinstance(source, t._MountedSource)
    assert [x["connectionId"] for x in t.newest_tuples(source)] == ["c1"]


def test_falls_back_to_s3_when_the_bucket_isnt_mounted(tmp_path, monkeypatch):
    monkeypatch.setenv("REPO_ARTIFACTS_BUCKET", "s3://some-other-bucket")
    assert isinstance(t.tuple_source(t.tuple_location(_replay()), mount=str(tmp_path)), t._S3Source)


@mock_aws
def test_reads_from_s3():
    os.environ.setdefault("AWS_DEFAULT_REGION", "us-east-2")
    client = boto3.client("s3", region_name="us-east-2")
    client.create_bucket(Bucket="tuple-bucket", CreateBucketConfiguration={"LocationConstraint": "us-east-2"})
    client.put_object(Bucket="tuple-bucket",
                      Key="tuples/capture-proxy-target-replay1-544c4f8f6b-2jj4j_4d6f/2026/10/14/05/t-1.log.gz",
                      Body=_gz(_tuple(1), _tuple(2)))
    client.put_object(Bucket="tuple-bucket", Key="tuples/other-1a2b-xyz_1/2026/t.log.gz", Body=_gz(_tuple(9)))
    source = t.tuple_source(t.tuple_location(_replay()), s3_client=client)
    assert [x["connectionId"] for x in t.newest_tuples(source)] == ["c1", "c2"]
