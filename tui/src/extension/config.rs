// extension/config.rs — ConfigStore + SecretStore.
//
// Mirrors:
//   workflow.stores.WorkflowConfigStore   (K8s ConfigMap-backed)
//   workflow.stores.SecretStore           (K8s Secret-backed, label-prefixed)
//   environment.Environment.from_yaml     (services.yaml on disk)
//
// The TUI's wizard becomes a YAML-document editor that talks to ConfigStore.
// validate_and_find_secrets() lets the wizard surface schema errors AND
// auto-detect secret references the user must populate.

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

use super::ServiceResult;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ConfigDoc {
    /// Raw YAML text. The TUI lets the user edit this directly; validation
    /// runs on commit. We intentionally do NOT model the schema in Rust —
    /// the schema source-of-truth is Cerberus on the Python side, and we'd
    /// drift.
    pub yaml: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ValidationReport {
    pub ok: bool,
    pub errors: Vec<String>,            // human-readable; preserve schema-engine order
    pub referenced_secrets: Vec<String>, // names the user must populate
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ConfigSource {
    /// services.yaml on local filesystem (legacy).
    Yaml(String),
    /// K8s ConfigMap in the migration namespace (workflow CLI).
    Kubernetes { namespace: String, name: String },
}

#[async_trait]
pub trait ConfigStore: Send + Sync {
    /// Read the active config. None = no config configured yet.
    async fn load(&self) -> ServiceResult<Option<ConfigDoc>>;

    /// Validate without persisting. Maps to validate_and_find_secrets().
    async fn validate(&self, doc: &ConfigDoc) -> ServiceResult<ValidationReport>;

    /// Persist after validation passes. Returns the (possibly normalized)
    /// doc as written.
    async fn save(&self, doc: &ConfigDoc) -> ServiceResult<ConfigDoc>;

    /// Sample workflow YAMLs (orchestrationSpecs/.../samples/*.wf.yaml).
    async fn list_samples(&self) -> ServiceResult<Vec<NamedSample>>;
    async fn load_sample(&self, name: &str) -> ServiceResult<ConfigDoc>;

    /// Where this store is reading/writing — for the TUI status line.
    fn source(&self) -> ConfigSource;
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct NamedSample {
    pub name: String,
    pub description: String,
}

/// HTTP-Basic credentials are the only real shape today; the trait still
/// hides the auth kind so adding more (e.g. SigV4, mTLS) doesn't churn
/// the TUI.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum SecretMaterial {
    BasicAuth { username: String, password: String },
}

#[async_trait]
pub trait SecretStore: Send + Sync {
    async fn list(&self) -> ServiceResult<Vec<String>>;
    async fn create(&self, name: &str, material: &SecretMaterial) -> ServiceResult<()>;
    async fn update(&self, name: &str, material: &SecretMaterial) -> ServiceResult<()>;
    async fn delete(&self, name: &str) -> ServiceResult<()>;
    async fn exists(&self, name: &str) -> ServiceResult<bool>;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn config_source_serde_roundtrip() {
        for src in [
            ConfigSource::Yaml("/config/migration_services.yaml".into()),
            ConfigSource::Kubernetes { namespace: "ma".into(), name: "default".into() },
        ] {
            let s = serde_json::to_string(&src).unwrap();
            let d: ConfigSource = serde_json::from_str(&s).unwrap();
            assert_eq!(src, d);
        }
    }

    #[test]
    fn validation_report_carries_secrets() {
        let r = ValidationReport {
            ok: true,
            errors: vec![],
            referenced_secrets: vec!["target-creds".into(), "source-creds".into()],
        };
        let s = serde_json::to_string(&r).unwrap();
        let d: ValidationReport = serde_json::from_str(&s).unwrap();
        assert_eq!(r, d);
    }
}
