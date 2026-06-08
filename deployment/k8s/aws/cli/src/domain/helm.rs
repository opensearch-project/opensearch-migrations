//! Helm install planning: image-override flags, TLS flags, and the stuck-
//! release recovery classifier.
//!
//! The chart's default `values.yaml` uses bare repos
//! (`migrations/migration_console`) that resolve to docker.io and 404; the CLI
//! must pass `--set images.<name>.repository=…` flags pointing at either the
//! public ECR or the operator's mirror. Building those flag vectors and the
//! `--tls-mode` flags is pure string logic, pinned by the unit tests below.

use crate::error::{Error, Result};

/// The helm release name and k8s namespace are BOTH pinned to "ma" — the chart
/// hardcodes that name in rendered resources and its post-install Job's
/// NAMESPACE env. The operator's stage only names the CFN stack / ECR repo /
/// state dir.
pub const RELEASE_NAME: &str = "ma";
pub const NAMESPACE: &str = "ma";
pub const CHART_NAME: &str = "migration-assistant";

/// The five chart image keys, paired with their public-ECR suffix. Two keys
/// (`migrationConsole`, `installer`) share the `console` image.
const PUBLIC_IMAGE_PAIRS: &[(&str, &str)] = &[
    (
        "captureProxy",
        "opensearch-migrations-traffic-capture-proxy",
    ),
    ("trafficReplayer", "opensearch-migrations-traffic-replayer"),
    (
        "reindexFromSnapshot",
        "opensearch-migrations-reindex-from-snapshot",
    ),
    ("migrationConsole", "opensearch-migrations-console"),
    ("installer", "opensearch-migrations-console"),
];

/// The five chart image keys, paired with the mirrored single-repo tag prefix.
const MIRRORED_IMAGE_PAIRS: &[(&str, &str)] = &[
    ("captureProxy", "migrations_capture_proxy"),
    ("trafficReplayer", "migrations_traffic_replayer"),
    ("reindexFromSnapshot", "migrations_reindex_from_snapshot"),
    ("migrationConsole", "migrations_migration_console"),
    ("installer", "migrations_migration_console"),
];

/// Build the `--set` flags pointing every image at
/// `public.ecr.aws/opensearchproject/...` at `version`. Used when
/// `MIRROR_IMAGES=N`. Returns a flat vector matching the helm CLI shape:
/// `["--set", "images.X.repository=...", "--set", "images.X.tag=..."]`.
pub fn public_image_flags(version: &str) -> Vec<String> {
    let mut flags = Vec::with_capacity(PUBLIC_IMAGE_PAIRS.len() * 4);
    for (name, suffix) in PUBLIC_IMAGE_PAIRS {
        flags.push("--set".into());
        flags.push(format!(
            "images.{name}.repository=public.ecr.aws/opensearchproject/{suffix}"
        ));
        flags.push("--set".into());
        flags.push(format!("images.{name}.tag={version}"));
    }
    flags
}

/// Build the `--set` flags pointing every image at the operator's private
/// `registry` with the `migrations_<name>_<version>` single-repo tag layout.
pub fn mirrored_image_flags(registry: &str, version: &str) -> Vec<String> {
    let mut flags = Vec::with_capacity(MIRRORED_IMAGE_PAIRS.len() * 4);
    for (name, tag_prefix) in MIRRORED_IMAGE_PAIRS {
        flags.push("--set".into());
        flags.push(format!("images.{name}.repository={registry}"));
        flags.push("--set".into());
        flags.push(format!("images.{name}.tag={tag_prefix}_{version}"));
    }
    flags
}

/// Build the `--set` flags for `--tls-mode` / `--pca-arn`.
///
/// * `none` / `self-signed` / empty → no flags (chart defaults handle them)
/// * `pca-existing` → requires `pca_arn`; enables the issuer + sets arn/region
/// * `pca-create` → enables issuer + acmpca-controller + create=true
pub fn tls_flags(mode: &str, pca_arn: &str, region: &str) -> Result<Vec<String>> {
    let mut flags = Vec::new();
    match mode {
        "" | "none" | "self-signed" => {}
        "pca-existing" => {
            if pca_arn.is_empty() {
                return Err(Error::die("--tls-mode pca-existing requires --pca-arn"));
            }
            flags.push("--set".into());
            flags.push("conditionalPackageInstalls.aws-privateca-issuer=true".into());
            flags.push("--set".into());
            flags.push(format!("awsPrivateCA.arn={pca_arn}"));
            flags.push("--set".into());
            flags.push(format!("awsPrivateCA.region={region}"));
        }
        "pca-create" => {
            flags.push("--set".into());
            flags.push("conditionalPackageInstalls.aws-privateca-issuer=true".into());
            flags.push("--set".into());
            flags.push("conditionalPackageInstalls.ack-acmpca-controller=true".into());
            flags.push("--set".into());
            flags.push("awsPrivateCA.create=true".into());
            flags.push("--set".into());
            flags.push(format!("awsPrivateCA.region={region}"));
        }
        other => {
            return Err(Error::die(format!(
                "unknown --tls-mode: {other} (expected: none, self-signed, pca-existing, pca-create)"
            )));
        }
    }
    Ok(flags)
}

/// What to do about a release found in a non-clean state — the decision tree in
/// `helm_recover_if_stuck`, separated from the side effects so it's testable.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Recovery {
    /// Healthy or absent — proceed straight to install/upgrade.
    Proceed,
    /// The install never finished — uninstall and start fresh.
    Uninstall,
    /// A stuck upgrade/failed release — the operator is offered rollback /
    /// uninstall / abort / reconcile (handled interactively upstream).
    OfferChoices,
    /// Mid-uninstall — wait briefly, then force uninstall.
    WaitThenUninstall,
    /// Unknown status — log and proceed anyway.
    ProceedUnknown,
}

/// Classify a helm release status string into a [`Recovery`] action.
pub fn classify_recovery(status: &str) -> Recovery {
    match status {
        "" | "deployed" | "superseded" => Recovery::Proceed,
        "pending-install" => Recovery::Uninstall,
        "pending-upgrade" | "failed" => Recovery::OfferChoices,
        "pending-rollback" => Recovery::Uninstall,
        "uninstalling" => Recovery::WaitThenUninstall,
        _ => Recovery::ProceedUnknown,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- public image flags ----

    #[test]
    fn public_flags_emit_five_images_twenty_elements() {
        let flags = public_image_flags("3.2.1");
        assert_eq!(flags.len(), 20, "5 images × (repo + tag) × 2 elements");
        let joined = flags.join("\n");
        assert!(joined.contains("images.captureProxy.repository=public.ecr.aws/opensearchproject/opensearch-migrations-traffic-capture-proxy"));
        assert!(joined.contains("images.trafficReplayer.repository=public.ecr.aws/opensearchproject/opensearch-migrations-traffic-replayer"));
        assert!(joined.contains("images.reindexFromSnapshot.repository=public.ecr.aws/opensearchproject/opensearch-migrations-reindex-from-snapshot"));
        assert!(joined.contains("images.migrationConsole.repository=public.ecr.aws/opensearchproject/opensearch-migrations-console"));
        assert!(joined.contains("images.installer.repository=public.ecr.aws/opensearchproject/opensearch-migrations-console"));
    }

    #[test]
    fn public_flags_tag_every_image() {
        let joined = public_image_flags("3.2.1").join("\n");
        for key in [
            "captureProxy",
            "trafficReplayer",
            "reindexFromSnapshot",
            "migrationConsole",
            "installer",
        ] {
            assert!(joined.contains(&format!("images.{key}.tag=3.2.1")));
        }
    }

    #[test]
    fn public_flags_handle_non_3x_version() {
        assert!(public_image_flags("2.9.0")
            .join("\n")
            .contains("images.migrationConsole.tag=2.9.0"));
    }

    // ---- mirrored image flags ----

    #[test]
    fn mirrored_flags_point_at_private_registry() {
        let registry = "629003556176.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-default";
        let joined = mirrored_image_flags(registry, "3.2.1").join("\n");
        for key in [
            "captureProxy",
            "trafficReplayer",
            "reindexFromSnapshot",
            "migrationConsole",
            "installer",
        ] {
            assert!(joined.contains(&format!("images.{key}.repository={registry}")));
        }
    }

    #[test]
    fn mirrored_flags_use_disambiguating_tags() {
        let joined = mirrored_image_flags("r", "3.2.1").join("\n");
        assert!(joined.contains("images.captureProxy.tag=migrations_capture_proxy_3.2.1"));
        assert!(joined.contains("images.trafficReplayer.tag=migrations_traffic_replayer_3.2.1"));
        assert!(joined
            .contains("images.reindexFromSnapshot.tag=migrations_reindex_from_snapshot_3.2.1"));
        assert!(joined.contains("images.migrationConsole.tag=migrations_migration_console_3.2.1"));
        assert!(joined.contains("images.installer.tag=migrations_migration_console_3.2.1"));
    }

    // ---- TLS flags ----

    #[test]
    fn tls_none_and_self_signed_emit_nothing() {
        assert!(tls_flags("", "", "us-east-1").unwrap().is_empty());
        assert!(tls_flags("none", "", "us-east-1").unwrap().is_empty());
        assert!(tls_flags("self-signed", "", "us-east-1")
            .unwrap()
            .is_empty());
    }

    #[test]
    fn tls_pca_existing_requires_arn() {
        assert!(tls_flags("pca-existing", "", "us-east-1").is_err());
        let flags = tls_flags("pca-existing", "arn:aws:acm-pca:::ca/x", "us-east-1").unwrap();
        let joined = flags.join("\n");
        assert!(joined.contains("conditionalPackageInstalls.aws-privateca-issuer=true"));
        assert!(joined.contains("awsPrivateCA.arn=arn:aws:acm-pca:::ca/x"));
        assert!(joined.contains("awsPrivateCA.region=us-east-1"));
    }

    #[test]
    fn tls_pca_create_enables_controller() {
        let joined = tls_flags("pca-create", "", "eu-west-1").unwrap().join("\n");
        assert!(joined.contains("conditionalPackageInstalls.aws-privateca-issuer=true"));
        assert!(joined.contains("conditionalPackageInstalls.ack-acmpca-controller=true"));
        assert!(joined.contains("awsPrivateCA.create=true"));
        assert!(joined.contains("awsPrivateCA.region=eu-west-1"));
    }

    #[test]
    fn tls_unknown_mode_errors() {
        assert!(tls_flags("bogus", "", "us-east-1").is_err());
    }

    // ---- stuck-release recovery classification ----

    #[test]
    fn recovery_classification() {
        assert_eq!(classify_recovery(""), Recovery::Proceed);
        assert_eq!(classify_recovery("deployed"), Recovery::Proceed);
        assert_eq!(classify_recovery("superseded"), Recovery::Proceed);
        assert_eq!(classify_recovery("pending-install"), Recovery::Uninstall);
        assert_eq!(classify_recovery("pending-upgrade"), Recovery::OfferChoices);
        assert_eq!(classify_recovery("failed"), Recovery::OfferChoices);
        assert_eq!(classify_recovery("pending-rollback"), Recovery::Uninstall);
        assert_eq!(
            classify_recovery("uninstalling"),
            Recovery::WaitThenUninstall
        );
        assert_eq!(
            classify_recovery("weird-future-status"),
            Recovery::ProceedUnknown
        );
    }
}
