//! Native OCI image copy between registries.
//!
//! Uses `oci-distribution` to pull all layers from the source registry and
//! push them to the destination, with per-host credential injection.
//! Authentication is provided by the caller so this module stays testable.

use oci_distribution::{
    client::{Client, ClientConfig, ClientProtocol, Config},
    manifest::{IMAGE_LAYER_GZIP_MEDIA_TYPE, IMAGE_LAYER_MEDIA_TYPE},
    secrets::RegistryAuth,
    Reference,
};

/// Auth credential for a single registry host.
pub struct RegistryCred {
    pub registry: String,
    pub username: String,
    pub password: String,
}

fn make_client() -> Client {
    Client::new(ClientConfig {
        protocol: ClientProtocol::HttpsExcept(vec![]),
        ..Default::default()
    })
}

fn auth_for(host: &str, creds: &[RegistryCred]) -> RegistryAuth {
    for c in creds {
        if c.registry == host {
            return RegistryAuth::Basic(c.username.clone(), c.password.clone());
        }
    }
    RegistryAuth::Anonymous
}

/// Copy a single OCI image from `src` to `dst`.
///
/// Returns `Ok(())` on success or an `Err(String)` with the error message.
/// `creds` provides per-registry credentials; anonymous auth is used for
/// any host not listed.
pub fn copy_image(src: &str, dst: &str, creds: &[RegistryCred]) -> Result<(), String> {
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("tokio init: {e}"))?;

    rt.block_on(async move {
        let src_ref = src
            .parse::<Reference>()
            .map_err(|e| format!("invalid src ref '{src}': {e}"))?;
        let dst_ref = dst
            .parse::<Reference>()
            .map_err(|e| format!("invalid dst ref '{dst}': {e}"))?;

        let client = make_client();

        let src_auth = auth_for(src_ref.registry(), creds);
        let dst_auth = auth_for(dst_ref.registry(), creds);

        // Accepted media types: OCI image layers (uncompressed + gzip).
        let accepted = vec![
            IMAGE_LAYER_MEDIA_TYPE,
            IMAGE_LAYER_GZIP_MEDIA_TYPE,
            // Also accept docker layer types so we can copy docker.io images.
            "application/vnd.docker.image.rootfs.diff.tar.gzip",
            "application/vnd.docker.image.rootfs.diff.tar",
        ];

        // Pull manifest + config (to get the manifest + config media type).
        let (manifest, _digest, config_str) = client
            .pull_manifest_and_config(&src_ref, &src_auth)
            .await
            .map_err(|e| format!("pull manifest from {src}: {e}"))?;

        // Pull all layers.
        let image_data = client
            .pull(&src_ref, &src_auth, accepted)
            .await
            .map_err(|e| format!("pull layers from {src}: {e}"))?;

        // Reconstruct the Config struct for push.
        let config = Config::new(
            config_str.into_bytes(),
            manifest.config.media_type.clone(),
            manifest.annotations.clone(),
        );

        // Push to destination.
        client
            .push(
                &dst_ref,
                &image_data.layers,
                config,
                &dst_auth,
                Some(manifest),
            )
            .await
            .map_err(|e| format!("push to {dst}: {e}"))?;

        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn auth_for_returns_cred_when_matching() {
        let creds = vec![RegistryCred {
            registry: "123.dkr.ecr.us-east-1.amazonaws.com".into(),
            username: "AWS".into(),
            password: "tok".into(),
        }];
        let auth = auth_for("123.dkr.ecr.us-east-1.amazonaws.com", &creds);
        assert!(matches!(auth, RegistryAuth::Basic(u, p) if u == "AWS" && p == "tok"));
    }

    #[test]
    fn auth_for_returns_anonymous_for_unknown_host() {
        let creds: Vec<RegistryCred> = vec![];
        assert_eq!(auth_for("quay.io", &creds), RegistryAuth::Anonymous);
    }
}
