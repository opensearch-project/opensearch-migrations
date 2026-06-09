//! AWS ECR + STS access through the AWS SDK: resolve the caller's identity,
//! create the image-mirror repository, and fetch an ECR push/pull credential.

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
        let region = config.region().map(|r| r.to_string()).unwrap_or_default();
        let client = aws_sdk_sts::Client::new(&config);
        let resp = client
            .get_caller_identity()
            .send()
            .await
            .map_err(|e| format!("sts:GetCallerIdentity: {e}"))?;
        let account = resp
            .account()
            .filter(|s| !s.is_empty())
            .ok_or_else(|| "STS returned no account".to_string())?
            .to_string();
        let arn = resp
            .arn()
            .filter(|s| !s.is_empty())
            .ok_or_else(|| "STS returned no ARN".to_string())?
            .to_string();
        Ok(AwsIdentity {
            account,
            arn,
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

/// Get an ECR authorization token. Returns `(username, password)` decoded
/// from the base64 token. The token is account-global (not registry-specific).
pub fn get_ecr_credentials(region: &str) -> std::result::Result<(String, String), String> {
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
        // The token is base64 of "AWS:<password>".
        let decoded =
            String::from_utf8(base64_decode(token).map_err(|e| format!("base64 decode: {e}"))?)
                .map_err(|e| format!("utf8: {e}"))?;
        let (user, pass) = decoded
            .split_once(':')
            .ok_or_else(|| "unexpected token format".to_string())?;
        Ok((user.to_string(), pass.to_string()))
    })
}

/// Map one standard-alphabet base64 character to its 6-bit value. `=` padding
/// and line breaks return `None` (skip); anything else is an error.
fn base64_sextet(c: u8) -> std::result::Result<Option<u8>, &'static str> {
    match c {
        b'A'..=b'Z' => Ok(Some(c - b'A')),
        b'a'..=b'z' => Ok(Some(c - b'a' + 26)),
        b'0'..=b'9' => Ok(Some(c - b'0' + 52)),
        b'+' => Ok(Some(62)),
        b'/' => Ok(Some(63)),
        b'=' | b'\n' | b'\r' => Ok(None),
        _ => Err("invalid base64 character"),
    }
}

/// Decode standard-alphabet base64 into bytes, without pulling in a base64
/// crate. Each character contributes 6 bits to a running buffer; whenever the
/// buffer holds a full byte it is emitted. Padding and line breaks are skipped.
fn base64_decode(s: &str) -> std::result::Result<Vec<u8>, &'static str> {
    let mut out = Vec::with_capacity(s.len() * 3 / 4);
    let mut buffer: u32 = 0; // most-significant pending bits, left-aligned
    let mut pending_bits = 0; // how many bits in `buffer` are valid
    for &c in s.as_bytes() {
        let Some(sextet) = base64_sextet(c)? else {
            continue;
        };
        buffer = (buffer << 6) | u32::from(sextet);
        pending_bits += 6;
        if pending_bits >= 8 {
            pending_bits -= 8;
            out.push((buffer >> pending_bits) as u8);
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

    #[test]
    fn base64_decode_empty_is_empty() {
        assert_eq!(base64_decode("").unwrap(), b"");
    }

    #[test]
    fn base64_decode_handles_each_padding_length() {
        // 0, 1, and 2 padding chars cover all three trailing-group widths.
        assert_eq!(base64_decode("Zm9vYmFy").unwrap(), b"foobar"); // none
        assert_eq!(base64_decode("Zm9vYmE=").unwrap(), b"fooba"); //  "="
        assert_eq!(base64_decode("Zm9v").unwrap(), b"foo"); //       (exact)
        assert_eq!(base64_decode("Zm8=").unwrap(), b"fo"); //        "="
        assert_eq!(base64_decode("Zg==").unwrap(), b"f"); //         "=="
    }

    #[test]
    fn base64_decode_ignores_line_breaks() {
        // Wrapped tokens stay decodable when newlines split the payload.
        assert_eq!(base64_decode("aGVs\nbG8=\n").unwrap(), b"hello");
    }

    #[test]
    fn base64_decode_round_trips_all_byte_values() {
        // Decoding must be the exact inverse of encoding for every byte value,
        // which exercises all 64 alphabet slots (including `+`/`/`) and every
        // 6-bit/8-bit boundary alignment.
        let all: Vec<u8> = (0u8..=255).collect();
        let encoded = base64_encode(&all);
        assert_eq!(base64_decode(&encoded).unwrap(), all);
    }

    #[test]
    fn base64_decode_rejects_invalid_characters() {
        assert!(base64_decode("not valid!").is_err());
    }

    /// Reference encoder used only to round-trip against [`base64_decode`].
    fn base64_encode(bytes: &[u8]) -> String {
        const ALPHABET: &[u8; 64] =
            b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let mut out = String::new();
        for chunk in bytes.chunks(3) {
            let b0 = chunk[0] as usize;
            let b1 = chunk.get(1).copied().unwrap_or(0) as usize;
            let b2 = chunk.get(2).copied().unwrap_or(0) as usize;
            out.push(ALPHABET[b0 >> 2] as char);
            out.push(ALPHABET[((b0 & 0b11) << 4) | (b1 >> 4)] as char);
            out.push(if chunk.len() > 1 {
                ALPHABET[((b1 & 0b1111) << 2) | (b2 >> 6)] as char
            } else {
                '='
            });
            out.push(if chunk.len() > 2 {
                ALPHABET[b2 & 0b111111] as char
            } else {
                '='
            });
        }
        out
    }
}
