//! CloudFormation output parsing and deploy-parameter planning.
//!
//! Port of the pure parts of `lib/cfn.sh`. The opensearch-migrations stacks
//! publish a single output, `MigrationsExportString` — a long blob of bash
//! `export VAR=VALUE; …` clauses. We expand that into flat `KEY=VALUE` pairs so
//! the rest of the CLI sees a tidy map, exactly as `cfn_outputs` /
//! `_cfn_extract_exports` / `_cfn_pick` did. All contracts from
//! output parsing and the VPC-endpoint parameter mapping are unit-tested.

/// Parse the `aws cloudformation describe-stacks ... --output text` payload
/// into flat `KEY=VALUE` lines.
///
/// The `text` output is tab-separated `OutputKey<TAB>OutputValue` rows. We:
///   1. emit every raw `OutputKey=OutputValue` (back-compat with flat outputs);
///   2. additionally expand the `MigrationsExportString` value's `export`
///      clauses into their own `KEY=VALUE` lines.
///
/// Returns the lines in emission order. An empty/blank payload yields no lines.
pub fn parse_outputs(raw: &str) -> Vec<String> {
    let mut out = Vec::new();
    if raw.trim().is_empty() {
        return out;
    }
    for row in raw.lines() {
        if row.trim().is_empty() {
            continue;
        }
        // Real `--output text` joins columns with tabs; the last column is the
        // value (which may itself contain `=`). Split into key + value on the
        // FIRST tab and treat the rest as the value.
        let (key, value) = match row.split_once('\t') {
            Some((k, v)) => (k.trim(), v),
            None => (row.trim(), ""),
        };
        // Pass A: raw OutputKey=OutputValue.
        out.push(format!("{key}={value}"));
        // Pass B: if this is the export blob, expand it.
        if key == "MigrationsExportString" {
            out.extend(extract_exports(value));
        }
    }
    out
}

/// Expand a string of `export VAR=VALUE; …` clauses into `VAR=VALUE` lines.
///
/// Tolerates leading whitespace, a missing `export ` prefix, and `=` inside
/// values (URLs, ARNs). Malformed clauses (no `KEY=`) are dropped. Mirrors
/// `_cfn_extract_exports`.
pub fn extract_exports(blob: &str) -> Vec<String> {
    let mut out = Vec::new();
    for clause in blob.split(';') {
        let clause = clause.trim_start();
        let clause = clause.strip_prefix("export ").unwrap_or(clause);
        let clause = clause.trim();
        if clause.is_empty() {
            continue;
        }
        // Must look like KEY=... where KEY is [A-Za-z_][A-Za-z0-9_]*.
        if let Some((key, _)) = clause.split_once('=') {
            if is_env_key(key) {
                out.push(clause.to_string());
            }
        }
    }
    out
}

fn is_env_key(key: &str) -> bool {
    let mut chars = key.chars();
    match chars.next() {
        Some(c) if c == '_' || c.is_ascii_alphabetic() => {}
        _ => return false,
    }
    chars.all(|c| c == '_' || c.is_ascii_alphanumeric())
}

/// Look up a single key's value from parsed `KEY=VALUE` lines — `cfn_output_value`.
pub fn output_value<'a>(outputs: &'a [String], key: &str) -> Option<&'a str> {
    let prefix = format!("{key}=");
    outputs.iter().find_map(|line| line.strip_prefix(&prefix))
}

/// Return the first key in `keys` that resolves to a non-empty value — the
/// `_cfn_pick` fallback chain (tolerates template renames across releases).
pub fn pick<'a>(outputs: &'a [String], keys: &[&str]) -> Option<&'a str> {
    for key in keys {
        if let Some(v) = output_value(outputs, key) {
            if !v.is_empty() {
                return Some(v);
            }
        }
    }
    None
}

/// CFN template variant selected by `--deploy-create-vpc-cfn` (default) or
/// `--deploy-import-vpc-cfn`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TemplateVariant {
    CreateVpc,
    ImportVpc,
}

impl TemplateVariant {
    /// State string used by the bash CLI (`CFN_TEMPLATE_VARIANT`).
    pub fn as_state(self) -> &'static str {
        match self {
            TemplateVariant::CreateVpc => "create-vpc",
            TemplateVariant::ImportVpc => "import-vpc",
        }
    }

    /// Parse the state string; unknown values are an error for the caller.
    pub fn from_state(s: &str) -> Option<Self> {
        match s {
            "create-vpc" => Some(TemplateVariant::CreateVpc),
            "import-vpc" => Some(TemplateVariant::ImportVpc),
            _ => None,
        }
    }

    /// The template filename fetched as a release artifact.
    pub fn template_name(self) -> &'static str {
        match self {
            TemplateVariant::CreateVpc => "Migration-Assistant-Infra-Create-VPC-eks.template.json",
            TemplateVariant::ImportVpc => "Migration-Assistant-Infra-Import-VPC-eks.template.json",
        }
    }
}

/// Map a single `--create-vpc-endpoints` token to its `Create*Endpoint=true`
/// CFN parameter. Returns `None` for the special `s3` case (handled by the
/// caller, which also needs route-table IDs) and for unknown tokens.
///
/// Mirrors the case-arm mapping in `_cfn_import_vpc_endpoint_params`.
pub fn endpoint_param(token: &str) -> EndpointParam {
    match token {
        "s3" => EndpointParam::S3Gateway,
        "ecr" => EndpointParam::Param("CreateECREndpoint=true"),
        "ecrDocker" => EndpointParam::Param("CreateECRDockerEndpoint=true"),
        "cloudwatchLogs" => EndpointParam::Param("CreateCloudWatchLogsEndpoint=true"),
        "efs" => EndpointParam::Param("CreateEFSEndpoint=true"),
        "sts" => EndpointParam::Param("CreateSTSEndpoint=true"),
        "eksAuth" => EndpointParam::Param("CreateEKSAuthEndpoint=true"),
        "" => EndpointParam::Unknown,
        _ => EndpointParam::Unknown,
    }
}

/// Classification of one `--create-vpc-endpoints` token.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EndpointParam {
    /// A simple `CreateXEndpoint=true` parameter.
    Param(&'static str),
    /// The S3 gateway endpoint — needs `CreateS3Endpoint=true` plus
    /// `S3EndpointRouteTableIds=<csv>` resolved from subnets by the caller.
    S3Gateway,
    /// Unknown / empty token — caller emits a warning and skips it.
    Unknown,
}

#[cfg(test)]
mod tests {
    use super::*;

    // A sanitized real-world stack payload: the single MigrationsExportString
    // output whose value is a blob of `export VAR=...` clauses.
    const REAL_EXPORT: &str = "export MIGRATIONS_APP_REGISTRY_ARN=arn:aws:servicecatalog:us-east-1:629003556176:/applications/0152ij6laz8tjhttvf5rg0jrql; export MIGRATIONS_USER_AGENT=AwsSolution/SO0290/Unknown; export MIGRATIONS_EKS_CLUSTER_NAME=migration-eks-cluster-default-us-east-1; export MIGRATIONS_ECR_REGISTRY=629003556176.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-default-us-east-1; export AWS_ACCOUNT=629003556176; export AWS_CFN_REGION=us-east-1; export VPC_ID=vpc-0bc345db0dee70e9e; export EKS_CLUSTER_SECURITY_GROUP=sg-07cc74efd34551fb1; export SNAPSHOT_ROLE=arn:aws:iam::629003556176:role/migration-eks-cluster-default-us-east-1-snapshot-role; export STAGE=default";

    fn real_payload_row() -> String {
        format!("MigrationsExportString\t{REAL_EXPORT}")
    }

    // ---- export-blob expansion ----

    #[test]
    fn extract_parses_semicolon_joined_exports() {
        let out = extract_exports("export A=one; export B=two; export C=three");
        assert!(out.contains(&"A=one".to_string()));
        assert!(out.contains(&"B=two".to_string()));
        assert!(out.contains(&"C=three".to_string()));
    }

    #[test]
    fn extract_preserves_equals_in_values() {
        let out = extract_exports("export ARN=arn:aws:iam::1:role/x=y; export URL=https://h/p?q=r");
        assert!(out.iter().any(|l| l == "ARN=arn:aws:iam::1:role/x=y"));
        assert!(out.iter().any(|l| l == "URL=https://h/p?q=r"));
    }

    #[test]
    fn extract_tolerates_whitespace_and_missing_export() {
        let out = extract_exports("   export A=1;   B=2; export C=3");
        assert!(out.contains(&"A=1".to_string()));
        assert!(out.contains(&"B=2".to_string()));
        assert!(out.contains(&"C=3".to_string()));
    }

    #[test]
    fn extract_drops_malformed_entries() {
        let out = extract_exports("   ; ; bogus line; export OK=yes; ");
        assert!(out.contains(&"OK=yes".to_string()));
        assert!(!out.iter().any(|l| l.starts_with("bogus")));
    }

    // ---- parse_outputs full-cycle ----

    #[test]
    fn parse_emits_raw_key_and_every_export() {
        let out = parse_outputs(&real_payload_row());
        assert!(out.iter().any(|l| l.starts_with("MigrationsExportString=")));
        assert!(out.contains(
            &"MIGRATIONS_EKS_CLUSTER_NAME=migration-eks-cluster-default-us-east-1".to_string()
        ));
        assert!(out.contains(
            &"MIGRATIONS_ECR_REGISTRY=629003556176.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-default-us-east-1"
                .to_string()
        ));
        assert!(out.contains(&"AWS_ACCOUNT=629003556176".to_string()));
        assert!(out.contains(&"AWS_CFN_REGION=us-east-1".to_string()));
        assert!(out.contains(&"VPC_ID=vpc-0bc345db0dee70e9e".to_string()));
        assert!(out.contains(&"STAGE=default".to_string()));
    }

    #[test]
    fn output_value_retrieves_single_export() {
        let out = parse_outputs(&real_payload_row());
        assert_eq!(
            output_value(&out, "MIGRATIONS_EKS_CLUSTER_NAME"),
            Some("migration-eks-cluster-default-us-east-1")
        );
        assert_eq!(
            output_value(&out, "MIGRATIONS_ECR_REGISTRY"),
            Some("629003556176.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-default-us-east-1")
        );
        assert_eq!(output_value(&out, "DOES_NOT_EXIST"), None);
    }

    // ---- key fallback chain ----

    #[test]
    fn pick_first_resolving_key() {
        let outputs = vec![
            "MIGRATIONS_EKS_CLUSTER_NAME=cluster-A".to_string(),
            "EKSClusterName=cluster-B".to_string(),
        ];
        assert_eq!(
            pick(&outputs, &["MIGRATIONS_EKS_CLUSTER_NAME", "EKSClusterName"]),
            Some("cluster-A")
        );

        let outputs = vec!["EKSClusterName=cluster-B".to_string()];
        assert_eq!(
            pick(&outputs, &["MIGRATIONS_EKS_CLUSTER_NAME", "EKSClusterName"]),
            Some("cluster-B")
        );
    }

    #[test]
    fn pick_none_when_no_match() {
        let outputs = vec!["OTHER=foo".to_string()];
        assert_eq!(pick(&outputs, &["A", "B", "C"]), None);
    }

    // ---- end-to-end helm/crane lookup chains against the real shape ----

    #[test]
    fn helm_lookup_chain_finds_cluster_name() {
        let out = parse_outputs(&real_payload_row());
        assert_eq!(
            pick(&out, &["MIGRATIONS_EKS_CLUSTER_NAME", "EKSClusterName"]),
            Some("migration-eks-cluster-default-us-east-1")
        );
    }

    #[test]
    fn crane_lookup_chain_finds_registry() {
        let out = parse_outputs(&real_payload_row());
        assert_eq!(
            pick(&out, &["MIGRATIONS_ECR_REGISTRY", "ECRRegistry"]),
            Some("629003556176.dkr.ecr.us-east-1.amazonaws.com/migration-ecr-default-us-east-1")
        );
    }

    #[test]
    fn parse_handles_empty_payload() {
        assert!(parse_outputs("").is_empty());
        assert!(parse_outputs("   \n  ").is_empty());
    }

    // ---- VPC endpoint param mapping ----

    #[test]
    fn endpoint_param_mapping() {
        assert_eq!(
            endpoint_param("ecr"),
            EndpointParam::Param("CreateECREndpoint=true")
        );
        assert_eq!(
            endpoint_param("ecrDocker"),
            EndpointParam::Param("CreateECRDockerEndpoint=true")
        );
        assert_eq!(
            endpoint_param("cloudwatchLogs"),
            EndpointParam::Param("CreateCloudWatchLogsEndpoint=true")
        );
        assert_eq!(
            endpoint_param("efs"),
            EndpointParam::Param("CreateEFSEndpoint=true")
        );
        assert_eq!(
            endpoint_param("sts"),
            EndpointParam::Param("CreateSTSEndpoint=true")
        );
        assert_eq!(
            endpoint_param("eksAuth"),
            EndpointParam::Param("CreateEKSAuthEndpoint=true")
        );
        assert_eq!(endpoint_param("s3"), EndpointParam::S3Gateway);
        assert_eq!(endpoint_param("bogus"), EndpointParam::Unknown);
        assert_eq!(endpoint_param(""), EndpointParam::Unknown);
    }

    #[test]
    fn template_variant_round_trip() {
        assert_eq!(
            TemplateVariant::from_state("create-vpc"),
            Some(TemplateVariant::CreateVpc)
        );
        assert_eq!(
            TemplateVariant::from_state("import-vpc"),
            Some(TemplateVariant::ImportVpc)
        );
        assert_eq!(TemplateVariant::from_state("nope"), None);
        assert_eq!(TemplateVariant::CreateVpc.as_state(), "create-vpc");
        assert!(TemplateVariant::ImportVpc
            .template_name()
            .contains("Import-VPC"));
    }
}
