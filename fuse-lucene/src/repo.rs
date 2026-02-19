/// Repo-level metadata reader: parses the JSON index-N file to discover
/// snapshots, indices, and shard mappings.
use serde::Deserialize;
use std::collections::HashMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

#[derive(Debug, Deserialize)]
pub struct RepoMetadata {
    pub snapshots: Vec<SnapshotEntry>,
    pub indices: HashMap<String, IndexEntry>,
    #[serde(default)]
    pub index_metadata_identifiers: HashMap<String, String>,
}

#[derive(Debug, Deserialize)]
pub struct SnapshotEntry {
    pub name: String,
    pub uuid: String,
    #[serde(default)]
    pub index_metadata_lookup: Option<HashMap<String, String>>,
}

#[derive(Debug, Deserialize)]
pub struct IndexEntry {
    pub id: String,
    #[serde(default)]
    pub snapshots: Vec<String>,
    #[serde(default)]
    pub shard_generations: Vec<String>,
}

/// Resolved view of a snapshot's indices and shards.
#[derive(Debug)]
pub struct ResolvedRepo {
    pub snapshot_id: String,
    pub snapshot_name: String,
    /// Map: index_name → ResolvedIndex
    pub indices: HashMap<String, ResolvedIndex>,
}

#[derive(Debug)]
pub struct ResolvedIndex {
    pub name: String,
    pub id: String,
    pub num_shards: usize,
}

/// Find the highest index-N file in the repo root.
fn find_index_file(repo_root: &Path) -> io::Result<PathBuf> {
    let mut best: Option<(u64, PathBuf)> = None;
    for entry in fs::read_dir(repo_root)? {
        let entry = entry?;
        let name = entry.file_name().to_string_lossy().to_string();
        if let Some(n_str) = name.strip_prefix("index-") {
            if let Ok(n) = n_str.parse::<u64>() {
                if best.as_ref().map_or(true, |(best_n, _)| n > *best_n) {
                    best = Some((n, entry.path()));
                }
            }
        }
    }
    best.map(|(_, p)| p)
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no index-N file found in repo root"))
}

/// Load and resolve repo metadata for a specific snapshot.
pub fn load_repo(repo_root: &Path, snapshot_name: &str) -> io::Result<ResolvedRepo> {
    let index_file = find_index_file(repo_root)?;
    let data = fs::read_to_string(&index_file)?;
    let meta: RepoMetadata = serde_json::from_str(&data)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;

    let snapshot = meta.snapshots.iter()
        .find(|s| s.name == snapshot_name)
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound,
            format!("snapshot '{}' not found", snapshot_name)))?;

    // Build index map: find which indices belong to this snapshot
    let mut indices = HashMap::new();

    if let Some(ref lookup) = snapshot.index_metadata_lookup {
        // ES 7.x+ format: index_metadata_lookup maps indexId → metadataKey
        for index_id in lookup.keys() {
            for (index_name, index_entry) in &meta.indices {
                if &index_entry.id == index_id {
                    let num_shards = if index_entry.shard_generations.is_empty() {
                        count_shard_dirs(repo_root, &index_entry.id)
                    } else {
                        index_entry.shard_generations.len()
                    };
                    indices.insert(index_name.clone(), ResolvedIndex {
                        name: index_name.clone(),
                        id: index_entry.id.clone(),
                        num_shards,
                    });
                }
            }
        }
    } else {
        // Older format: use snapshots list in each index
        for (index_name, index_entry) in &meta.indices {
            if index_entry.snapshots.contains(&snapshot.uuid) {
                let num_shards = if index_entry.shard_generations.is_empty() {
                    count_shard_dirs(repo_root, &index_entry.id)
                } else {
                    index_entry.shard_generations.len()
                };
                indices.insert(index_name.clone(), ResolvedIndex {
                    name: index_name.clone(),
                    id: index_entry.id.clone(),
                    num_shards,
                });
            }
        }
    }

    Ok(ResolvedRepo {
        snapshot_id: snapshot.uuid.clone(),
        snapshot_name: snapshot_name.to_string(),
        indices,
    })
}

/// Count shard directories under indices/<indexId>/ for older formats without shard_generations.
fn count_shard_dirs(repo_root: &Path, index_id: &str) -> usize {
    let index_dir = repo_root.join("indices").join(index_id);
    match fs::read_dir(&index_dir) {
        Ok(entries) => entries
            .filter_map(|e| e.ok())
            .filter(|e| {
                e.file_type().map(|ft| ft.is_dir()).unwrap_or(false)
                    && e.file_name().to_string_lossy().parse::<u32>().is_ok()
            })
            .count(),
        Err(_) => 0,
    }
}
