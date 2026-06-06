//! Image-mirror destination layout and retry policy.
//!
//! Port of the pure logic in `lib/crane.sh`: where a mirrored image lands in
//! the operator's ECR (`_dst_for` / `_ecr_repo_for`), the classification of
//! crane errors into retryable vs fatal (`_crane_copy_retry`'s case-arms), and
//! the MA-team image source/destination mapping. The actual `crane copy`
//! invocation goes through a [`CommandRunner`] so retry behavior is testable
//! without a real binary.

use crate::runner::CommandRunner;

/// Destination URL for a `crane copy` — `<ecr-host>/mirrored/<original-image>`.
///
/// ECR repository names allow `/`, so the full source path (including its own
/// registry host) is preserved verbatim under a `mirrored/` prefix. Mirrors
/// `_dst_for`.
pub fn dst_for(src: &str, ecr_host: &str) -> String {
    format!("{ecr_host}/mirrored/{src}")
}

/// ECR repository name (no host, no tag) for a source image — `_ecr_repo_for`.
///
/// Strips the `:tag` suffix and prepends `mirrored/`. A source without a tag is
/// handled gracefully (the whole string becomes the repo path).
pub fn ecr_repo_for(src: &str) -> String {
    let no_tag = match src.rsplit_once(':') {
        // Only strip if the part after ':' looks like a tag (no '/'), so we
        // don't mangle a port in a hypothetical `host:5000/img` (none today,
        // but matches the bash `${src%:*}` intent on the last colon).
        Some((head, tail)) if !tail.contains('/') => head,
        _ => src,
    };
    format!("mirrored/{no_tag}")
}

/// Whether a crane error message is unambiguously fatal — retrying it cannot
/// help, so the retry loop should bail immediately. Mirrors the case-arms in
/// `_crane_copy_retry`, returning the matching diagnostic phrase the bash CLI
/// logged so callers can surface the same wording.
pub fn fatal_reason(stderr: &str) -> Option<&'static str> {
    if stderr.contains("NAME_UNKNOWN") || stderr.contains("repository does not exist") {
        Some("ECR repo missing")
    } else if stderr.contains("UNAUTHORIZED")
        || stderr.contains("DENIED")
        || stderr.contains("authorization token has expired")
    {
        Some("auth failure")
    } else if stderr.contains("MANIFEST_UNKNOWN") || stderr.contains("manifest unknown") {
        Some("source manifest does not exist")
    } else {
        None
    }
}

/// The five MA-team images, as `(chart_build_name, public_suffix)` — the exact
/// pairs `_crane_mirror_ma_images` mirrors into the single-repo layout.
pub const MA_IMAGE_PAIRS: &[(&str, &str)] = &[
    ("capture_proxy", "traffic-capture-proxy"),
    ("traffic_replayer", "traffic-replayer"),
    ("reindex_from_snapshot", "reindex-from-snapshot"),
    ("migration_console", "console"),
];

/// Source/destination for one MA-team image mirror.
///
/// With `ma_images_source = None`, the source is the public ECR for `ma_ver`;
/// with `Some(src)`, the source is `<src>:migrations_<name>_latest`. The
/// destination always uses the single-repo disambiguating-tag layout the chart
/// expects. Mirrors the loop body of `_crane_mirror_ma_images`.
pub fn ma_image_copy(
    build_name: &str,
    public_suffix: &str,
    registry: &str,
    ma_ver: &str,
    ma_images_source: Option<&str>,
) -> (String, String) {
    let src = match ma_images_source {
        Some(s) => format!("{s}:migrations_{build_name}_latest"),
        None => format!(
            "public.ecr.aws/opensearchproject/opensearch-migrations-{public_suffix}:{ma_ver}"
        ),
    };
    let dst = format!("{registry}:migrations_{build_name}_{ma_ver}");
    (src, dst)
}

/// Outcome of [`copy_with_retry`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CopyResult {
    /// Succeeded on the given 1-based attempt number.
    Ok { attempt: u32 },
    /// Bailed early on a fatal error, with the diagnostic phrase.
    Fatal { reason: &'static str },
    /// Exhausted all attempts with retryable failures.
    Exhausted { attempts: u32 },
}

/// Run `crane copy <src> <dst>` with exponential backoff, classifying fatal
/// errors as no-retry. Mirrors `_crane_copy_retry`.
///
/// `sleep` is injected so tests run instantly; production passes a real sleep.
/// `attempts` defaults to 5 in the bash CLI (`CRANE_RETRY_ATTEMPTS`).
pub fn copy_with_retry<R, S>(
    runner: &R,
    src: &str,
    dst: &str,
    attempts: u32,
    initial_backoff_secs: u64,
    mut sleep: S,
) -> CopyResult
where
    R: CommandRunner,
    S: FnMut(u64),
{
    let mut backoff = initial_backoff_secs;
    for attempt in 1..=attempts {
        let out = runner.run("crane", &["copy", src, dst]);
        if out.success() {
            return CopyResult::Ok { attempt };
        }
        if let Some(reason) = fatal_reason(&out.stderr) {
            return CopyResult::Fatal { reason };
        }
        if attempt < attempts {
            sleep(backoff);
            backoff = backoff.saturating_mul(2);
        }
    }
    CopyResult::Exhausted { attempts }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runner::MockRunner;

    // ---- mirror destination layout ----

    #[test]
    fn dst_for_layout() {
        assert_eq!(
            dst_for(
                "quay.io/strimzi/kafka:0.50.1-kafka-4.0.0",
                "629003556176.dkr.ecr.us-east-1.amazonaws.com"
            ),
            "629003556176.dkr.ecr.us-east-1.amazonaws.com/mirrored/quay.io/strimzi/kafka:0.50.1-kafka-4.0.0"
        );
        assert_eq!(
            dst_for(
                "registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.15.0",
                "r.example.com"
            ),
            "r.example.com/mirrored/registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.15.0"
        );
        assert_eq!(
            dst_for(
                "public.ecr.aws/aws-controllers-k8s/cloudwatch-controller:1.4.2",
                "r.example.com"
            ),
            "r.example.com/mirrored/public.ecr.aws/aws-controllers-k8s/cloudwatch-controller:1.4.2"
        );
    }

    #[test]
    fn ecr_repo_for_strips_tag_and_prepends_mirrored() {
        assert_eq!(
            ecr_repo_for("quay.io/strimzi/kafka:0.50.1-kafka-4.0.0"),
            "mirrored/quay.io/strimzi/kafka"
        );
        assert_eq!(
            ecr_repo_for("registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.15.0"),
            "mirrored/registry.k8s.io/kube-state-metrics/kube-state-metrics"
        );
        assert_eq!(ecr_repo_for("busybox:latest"), "mirrored/busybox");
    }

    #[test]
    fn dst_and_repo_agree() {
        let src = "quay.io/strimzi/kafka:0.50.1-kafka-4.0.0";
        let dst = dst_for(src, "r.example.com");
        let repo = ecr_repo_for(src);
        let tag = src.rsplit_once(':').unwrap().1;
        assert_eq!(dst, format!("r.example.com/{repo}:{tag}"));
    }

    // ---- copy retry + fatal-error classification ----

    #[test]
    fn retry_succeeds_first_try() {
        let r = MockRunner::new().stub("crane", &["copy"], 0, "Copied");
        let res = copy_with_retry(&r, "src", "dst", 5, 0, |_| {});
        assert_eq!(res, CopyResult::Ok { attempt: 1 });
        assert_eq!(r.calls_to("crane").len(), 1);
    }

    #[test]
    fn retry_succeeds_after_two_transient_failures() {
        // Stateful stub: fail twice, then succeed. MockRunner stubs are static,
        // so drive attempt counting via a custom runner.
        struct FailThenPass {
            calls: std::sync::Mutex<u32>,
            fail: u32,
        }
        impl CommandRunner for FailThenPass {
            fn run(&self, _p: &str, _a: &[&str]) -> crate::runner::Output {
                let mut n = self.calls.lock().unwrap();
                *n += 1;
                if *n <= self.fail {
                    crate::runner::Output {
                        status: 1,
                        stdout: String::new(),
                        stderr: "transient".into(),
                    }
                } else {
                    crate::runner::Output {
                        status: 0,
                        stdout: "Copied".into(),
                        stderr: String::new(),
                    }
                }
            }
            fn has_command(&self, _p: &str) -> bool {
                true
            }
        }
        let r = FailThenPass {
            calls: std::sync::Mutex::new(0),
            fail: 2,
        };
        let mut slept = Vec::new();
        let res = copy_with_retry(&r, "quay.io/x:1", "r/x:1", 5, 1, |s| slept.push(s));
        assert_eq!(res, CopyResult::Ok { attempt: 3 });
        // Backoff applied between attempts 1→2 and 2→3: 1s then 2s.
        assert_eq!(slept, vec![1, 2]);
    }

    #[test]
    fn retry_exhausts_on_persistent_transient() {
        let r = MockRunner::new().stub("crane", &["copy"], 1, "");
        let res = copy_with_retry(&r, "src", "dst", 3, 0, |_| {});
        assert_eq!(res, CopyResult::Exhausted { attempts: 3 });
        assert_eq!(r.calls_to("crane").len(), 3);
    }

    #[test]
    fn retry_bails_on_name_unknown() {
        let r = MockRunner::new().stub_stderr(
            "crane",
            &["copy"],
            1,
            "NAME_UNKNOWN: The repository does not exist",
        );
        let res = copy_with_retry(&r, "src", "dst", 5, 0, |_| {});
        assert_eq!(
            res,
            CopyResult::Fatal {
                reason: "ECR repo missing"
            }
        );
        assert_eq!(r.calls_to("crane").len(), 1, "must not retry a fatal error");
    }

    #[test]
    fn retry_bails_on_auth_denied() {
        let r = MockRunner::new().stub_stderr(
            "crane",
            &["copy"],
            1,
            "DENIED: Your authorization token has expired. Reauthenticate and try again.",
        );
        assert_eq!(
            copy_with_retry(&r, "src", "dst", 5, 0, |_| {}),
            CopyResult::Fatal {
                reason: "auth failure"
            }
        );
    }

    #[test]
    fn retry_bails_on_unauthorized() {
        let r = MockRunner::new().stub_stderr(
            "crane",
            &["copy"],
            1,
            "UNAUTHORIZED: authentication required",
        );
        assert_eq!(
            copy_with_retry(&r, "src", "dst", 5, 0, |_| {}),
            CopyResult::Fatal {
                reason: "auth failure"
            }
        );
    }

    #[test]
    fn retry_bails_on_manifest_unknown() {
        let r = MockRunner::new().stub_stderr(
            "crane",
            &["copy"],
            1,
            "MANIFEST_UNKNOWN: manifest unknown",
        );
        assert_eq!(
            copy_with_retry(&r, "src", "dst", 5, 0, |_| {}),
            CopyResult::Fatal {
                reason: "source manifest does not exist"
            }
        );
    }

    #[test]
    fn retry_attempts_one_means_no_retry() {
        let r = MockRunner::new().stub("crane", &["copy"], 1, "");
        assert_eq!(
            copy_with_retry(&r, "src", "dst", 1, 0, |_| {}),
            CopyResult::Exhausted { attempts: 1 }
        );
        assert_eq!(r.calls_to("crane").len(), 1);
    }

    // ---- MA image source/dest mapping ----

    #[test]
    fn ma_image_copy_public_source() {
        let (src, dst) = ma_image_copy(
            "capture_proxy",
            "traffic-capture-proxy",
            "r/repo",
            "3.2.1",
            None,
        );
        assert_eq!(
            src,
            "public.ecr.aws/opensearchproject/opensearch-migrations-traffic-capture-proxy:3.2.1"
        );
        assert_eq!(dst, "r/repo:migrations_capture_proxy_3.2.1");
    }

    #[test]
    fn ma_image_copy_custom_source() {
        let (src, dst) = ma_image_copy(
            "migration_console",
            "console",
            "r/repo",
            "3.2.1",
            Some("other.ecr"),
        );
        assert_eq!(src, "other.ecr:migrations_migration_console_latest");
        assert_eq!(dst, "r/repo:migrations_migration_console_3.2.1");
    }
}
