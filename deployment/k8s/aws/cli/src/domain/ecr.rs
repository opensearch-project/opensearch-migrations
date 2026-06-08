//! Native AWS ECR + STS operations — replaces `aws ecr` and `aws sts` CLI
//! calls with the AWS SDK so crane and the aws-cli binary are not required.

/// AWS identity resolved from STS.
pub struct AwsIdentity {
    pub account: String,
    pub arn: String,
    pub region: String,
}

/// Call `sts:GetCallerIdentity` natively and return account + ARN. Region is
/// resolved from the SDK config (env / profile / IMDS).
pub fn get_caller_identity() -> std::result::Result<AwsIdentity, String> {
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("tokio init: {e}"))?;
    rt.block_on(async {
        let config = aws_config::defaults(aws_config::BehaviorVersion::latest())
            .load()
            .await;
        let region = config
            .region()
            .map(|r| r.to_string())
            .unwrap_or_default();
        let client = aws_sdk_sts::Client::new(&config);
        let resp = client
            .get_caller_identity()
            .send()
            .await
            .map_err(|e| format!("sts:GetCallerIdentity: {e}"))?;
        Ok(AwsIdentity {
            account: resp.account().unwrap_or("").to_string(),
            arn: resp.arn().unwrap_or("").to_string(),
            region,
        })
    })
}

/// Idempotently create an ECR repository. Errors from
/// `RepositoryAlreadyExistsException` are silently ignored.
pub fn create_repository(repo_name: &str, region: &str) -> std::result::Result<(), String> {
    let region_owned = region.to_string();
    let repo_owned = repo_name.to_string();
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("tokio init: {e}"))?;
    rt.block_on(async move {
        let config = aws_config::defaults(aws_config::BehaviorVersion::latest())
            .region(aws_sdk_ecr::config::Region::new(region_owned))
            .load()
            .await;
        let client = aws_sdk_ecr::Client::new(&config);
        match client
            .create_repository()
            .repository_name(&repo_owned)
            .image_scanning_configuration(
                aws_sdk_ecr::types::ImageScanningConfiguration::builder()
                    .scan_on_push(false)
                    .build(),
            )
            .send()
            .await
        {
            Ok(_) => Ok(()),
            Err(e) => {
                let msg = e.to_string();
                if msg.contains("RepositoryAlreadyExistsException")
                    || msg.contains("already exists")
                {
                    Ok(())
                } else {
                    Err(format!("ecr:CreateRepository: {e}"))
                }
            }
        }
    })
}

/// Get an ECR authorization token (base64 `user:password`) for the given
/// registry host. Returns `(username, password)` decoded from the token.
pub fn get_ecr_credentials(
    _registry_host: &str,
    region: &str,
) -> std::result::Result<(String, String), String> {
    let region_owned = region.to_string();
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("tokio init: {e}"))?;
    rt.block_on(async move {
        let config = aws_config::defaults(aws_config::BehaviorVersion::latest())
            .region(aws_sdk_ecr::config::Region::new(region_owned))
            .load()
            .await;
        let client = aws_sdk_ecr::Client::new(&config);
        let resp = client
            .get_authorization_token()
            .send()
            .await
            .map_err(|e| format!("ecr:GetAuthorizationToken: {e}"))?;
        let token = resp
            .authorization_data()
            .first()
            .and_then(|d| d.authorization_token())
            .ok_or_else(|| "no authorization token returned".to_string())?;
        // Token is base64("AWS:<password>").
        let decoded = String::from_utf8(
            base64_decode(token).map_err(|e| format!("base64 decode: {e}"))?,
        )
        .map_err(|e| format!("utf8: {e}"))?;
        let (user, pass) = decoded
            .split_once(':')
            .ok_or_else(|| "unexpected token format".to_string())?;
        Ok((user.to_string(), pass.to_string()))
    })
}

/// Minimal base64 decoder (standard alphabet) so we don't need an extra crate.
fn base64_decode(s: &str) -> std::result::Result<Vec<u8>, &'static str> {
    const TABLE: &[u8; 128] = b"\x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\
                                  \x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\
                                  \x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\x40\x3e\x40\x40\x40\x3f\
                                  \x34\x35\x36\x37\x38\x39\x3a\x3b\x3c\x3d\x40\x40\x40\x40\x40\x40\
                                  \x40\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\
                                  \x0f\x10\x11\x12\x13\x14\x15\x16\x17\x18\x19\x40\x40\x40\x40\x40\
                                  \x40\x1a\x1b\x1c\x1d\x1e\x1f\x20\x21\x22\x23\x24\x25\x26\x27\x28\
                                  \x29\x2a\x2b\x2c\x2d\x2e\x2f\x30\x31\x32\x33\x40\x40\x40\x40\x40";
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len() * 3 / 4);
    let mut i = 0;
    while i < bytes.len() {
        let b = bytes[i];
        if b == b'=' || b == b'\n' || b == b'\r' {
            i += 1;
            continue;
        }
        if b as usize >= 128 || TABLE[b as usize] == 0x40 {
            return Err("invalid base64 character");
        }
        // Collect up to 4 valid chars.
        let mut chunk = [0u8; 4];
        let mut n = 0;
        let mut j = i;
        while j < bytes.len() && n < 4 {
            let c = bytes[j];
            if c == b'=' {
                break;
            }
            if c == b'\n' || c == b'\r' {
                j += 1;
                continue;
            }
            if c as usize >= 128 || TABLE[c as usize] == 0x40 {
                return Err("invalid base64 character");
            }
            chunk[n] = TABLE[c as usize];
            n += 1;
            j += 1;
        }
        i = j;
        // Skip padding.
        while i < bytes.len() && (bytes[i] == b'=' || bytes[i] == b'\n' || bytes[i] == b'\r') {
            i += 1;
        }
        match n {
            4 => {
                out.push((chunk[0] << 2) | (chunk[1] >> 4));
                out.push((chunk[1] << 4) | (chunk[2] >> 2));
                out.push((chunk[2] << 6) | chunk[3]);
            }
            3 => {
                out.push((chunk[0] << 2) | (chunk[1] >> 4));
                out.push((chunk[1] << 4) | (chunk[2] >> 2));
            }
            2 => {
                out.push((chunk[0] << 2) | (chunk[1] >> 4));
            }
            _ => {}
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64_decode_round_trip() {
        // "AWS:password" base64-encoded.
        let encoded = "QVdTOnBhc3N3b3Jk";
        let decoded = String::from_utf8(base64_decode(encoded).unwrap()).unwrap();
        assert_eq!(decoded, "AWS:password");
    }

    #[test]
    fn base64_decode_with_padding() {
        // "hello" = "aGVsbG8="
        let decoded = base64_decode("aGVsbG8=").unwrap();
        assert_eq!(decoded, b"hello");
    }
}
