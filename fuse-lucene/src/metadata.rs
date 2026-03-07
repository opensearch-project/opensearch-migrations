/// Snapshot metadata loading: handles Lucene codec headers and DEFLATE compression.
/// Mirrors Java's SnapshotMetadataLoader.processMetadataBytes().
use crate::smile::{SmileParser, SmileValue};
use flate2::read::DeflateDecoder;
use std::io::{self, Read};

const DFL_HEADER: &[u8] = b"DFL\0";
const LUCENE_CODEC_MAGIC: u32 = 0x3FD76C17;

/// Process raw shard metadata bytes into a SmileValue.
/// Handles both DEFLATE-compressed and Lucene codec header formats.
pub fn load_shard_metadata(raw: &[u8]) -> io::Result<SmileValue> {
    let smile_data = extract_smile_data(raw)?;
    SmileParser::parse(&smile_data)
}

fn extract_smile_data(raw: &[u8]) -> io::Result<Vec<u8>> {
    if let Some(pos) = find_dfl_header(raw) {
        // DEFLATE compressed (OpenSearch format)
        let start = pos + DFL_HEADER.len();
        let mut decoder = DeflateDecoder::new(&raw[start..]);
        let mut decompressed = Vec::new();
        decoder.read_to_end(&mut decompressed)?;
        Ok(decompressed)
    } else {
        // Lucene codec header format
        let offset = skip_codec_header(raw)?;
        // Strip footer (last 16 bytes: 4 magic + 4 algo + 8 checksum)
        let end = if raw.len() >= offset + 16 {
            raw.len() - 16
        } else {
            raw.len()
        };
        Ok(raw[offset..end].to_vec())
    }
}

fn find_dfl_header(data: &[u8]) -> Option<usize> {
    data.windows(DFL_HEADER.len())
        .position(|w| w == DFL_HEADER)
}

fn skip_codec_header(data: &[u8]) -> io::Result<usize> {
    if data.len() < 8 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "data too short for codec header"));
    }

    let magic = u32::from_be_bytes([data[0], data[1], data[2], data[3]]);
    if magic != LUCENE_CODEC_MAGIC {
        return Err(io::Error::new(io::ErrorKind::InvalidData,
            format!("invalid codec magic: 0x{:08X}", magic)));
    }

    let mut pos = 4;

    // Codec name: VInt length + UTF-8 string (Lucene's DataOutput.writeString)
    let (name_len, vint_bytes) = read_lucene_vint(&data[pos..])?;
    pos += vint_bytes + name_len as usize;

    // Version: 4 bytes (big-endian int)
    pos += 4;

    if pos > data.len() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "codec header extends past data"));
    }

    Ok(pos)
}

/// Read a Lucene VInt (variable-length integer, MSB=1 means more bytes follow).
/// Returns (value, bytes_consumed).
fn read_lucene_vint(data: &[u8]) -> io::Result<(u32, usize)> {
    let mut value: u32 = 0;
    let mut shift = 0;
    for (i, &b) in data.iter().enumerate() {
        value |= ((b & 0x7F) as u32) << shift;
        if b & 0x80 == 0 {
            return Ok((value, i + 1));
        }
        shift += 7;
        if shift > 28 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "VInt too long"));
        }
    }
    Err(io::Error::new(io::ErrorKind::UnexpectedEof, "truncated VInt"))
}

/// Parsed shard file info — what we need for the FUSE layer.
#[derive(Debug, Clone)]
pub struct ShardFileEntry {
    /// Blob name (e.g., "__0KmMz-ttQ0entKtem7qBww")
    pub blob_name: String,
    /// Lucene filename (e.g., "_0.cfs")
    pub physical_name: String,
    /// Total file length in bytes
    pub length: u64,
    /// Part size (for multi-part files)
    pub part_size: u64,
    /// Number of parts
    pub num_parts: u64,
    /// Whether this is a virtual file (name starts with "v__")
    pub is_virtual: bool,
    /// Meta hash bytes (content for virtual files)
    pub meta_hash: Vec<u8>,
}

impl ShardFileEntry {
    /// Get the blob name for a specific part
    pub fn part_name(&self, part: u64) -> String {
        if self.num_parts > 1 {
            format!("{}.part{}", self.blob_name, part)
        } else {
            self.blob_name.clone()
        }
    }
}

/// Parse shard metadata SMILE value into file entries.
pub fn parse_shard_files(root: &SmileValue) -> io::Result<Vec<ShardFileEntry>> {
    let files = root.get("files")
        .and_then(|v| v.as_array())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing 'files' array"))?;

    let mut entries = Vec::with_capacity(files.len());
    for file in files {
        let name = file.get("name")
            .and_then(|v| v.as_str())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing file 'name'"))?
            .to_string();

        let physical_name = file.get("physical_name")
            .and_then(|v| v.as_str())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing 'physical_name'"))?
            .to_string();

        let length = file.get("length")
            .and_then(|v| v.as_i64())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing 'length'"))? as u64;

        let part_size = file.get("part_size")
            .and_then(|v| v.as_i64())
            .unwrap_or(i64::MAX) as u64;

        let meta_hash = file.get("meta_hash")
            .and_then(|v| v.as_bytes())
            .unwrap_or(&[])
            .to_vec();

        let is_virtual = name.starts_with("v__");

        // Calculate number of parts (same logic as Java)
        let part_bytes = if part_size != u64::MAX { part_size } else { u64::MAX };
        let mut num_parts = if part_bytes == u64::MAX {
            1
        } else {
            let mut n = length / part_bytes;
            if length % part_bytes > 0 {
                n += 1;
            }
            if n == 0 { 1 } else { n }
        };
        if num_parts == 0 {
            num_parts = 1;
        }

        entries.push(ShardFileEntry {
            blob_name: name,
            physical_name,
            length,
            part_size,
            num_parts,
            is_virtual,
            meta_hash,
        });
    }

    Ok(entries)
}
