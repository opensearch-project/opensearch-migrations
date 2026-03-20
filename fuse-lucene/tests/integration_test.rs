/// Integration test using actual ES 7.10 snapshot test data from the Java test resources.
use std::path::Path;

use snapshot_fuse::metadata;
use snapshot_fuse::repo;

const TEST_SNAPSHOT_DIR: &str = concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../RFS/test-resources/snapshots/ES_7_10_BWC_Check"
);

#[test]
fn test_load_repo_metadata() {
    let repo_root = Path::new(TEST_SNAPSHOT_DIR);
    if !repo_root.exists() {
        eprintln!("Skipping test: test data not found at {:?}", repo_root);
        return;
    }

    let resolved = repo::load_repo(repo_root, "rfs-snapshot").unwrap();
    assert_eq!(resolved.snapshot_name, "rfs-snapshot");
    assert_eq!(resolved.snapshot_id, "KhcpVj8aRMek0oLMUSPHeg");
    assert!(!resolved.indices.is_empty());

    // Should have bwc_index_1
    let bwc = resolved.indices.get("bwc_index_1").unwrap();
    assert_eq!(bwc.id, "0edrmuSPR1CIr2B6BZbMJA");
    assert_eq!(bwc.num_shards, 1);
}

#[test]
fn test_parse_shard_metadata() {
    let repo_root = Path::new(TEST_SNAPSHOT_DIR);
    if !repo_root.exists() {
        eprintln!("Skipping test: test data not found at {:?}", repo_root);
        return;
    }

    // Read shard metadata for bwc_index_1, shard 0
    let shard_meta_path = repo_root
        .join("indices")
        .join("0edrmuSPR1CIr2B6BZbMJA")
        .join("0")
        .join("snap-KhcpVj8aRMek0oLMUSPHeg.dat");

    let raw = std::fs::read(&shard_meta_path).unwrap();
    let smile_value = metadata::load_shard_metadata(&raw).unwrap();
    let files = metadata::parse_shard_files(&smile_value).unwrap();

    assert!(!files.is_empty(), "should have at least one file");

    // Check that we have expected Lucene files
    let physical_names: Vec<&str> = files.iter().map(|f| f.physical_name.as_str()).collect();
    println!("Lucene files in shard: {:?}", physical_names);

    // Should have segments file
    assert!(physical_names.iter().any(|n| n.starts_with("segments_")),
        "should have a segments file");

    // Check file properties
    for f in &files {
        assert!(!f.blob_name.is_empty());
        assert!(!f.physical_name.is_empty());
        assert!(f.num_parts >= 1);
        if f.is_virtual {
            assert!(f.blob_name.starts_with("v__"));
            assert!(!f.meta_hash.is_empty());
        }
    }
}

#[test]
fn test_read_blob_data() {
    let repo_root = Path::new(TEST_SNAPSHOT_DIR);
    if !repo_root.exists() {
        return;
    }

    let shard_meta_path = repo_root
        .join("indices")
        .join("0edrmuSPR1CIr2B6BZbMJA")
        .join("0")
        .join("snap-KhcpVj8aRMek0oLMUSPHeg.dat");

    let raw = std::fs::read(&shard_meta_path).unwrap();
    let smile_value = metadata::load_shard_metadata(&raw).unwrap();
    let files = metadata::parse_shard_files(&smile_value).unwrap();

    // Find a non-virtual file and verify its blob exists
    let non_virtual: Vec<_> = files.iter().filter(|f| !f.is_virtual).collect();
    assert!(!non_virtual.is_empty());

    let file = &non_virtual[0];
    let blob_path = repo_root
        .join("indices")
        .join("0edrmuSPR1CIr2B6BZbMJA")
        .join("0")
        .join(file.part_name(0));

    assert!(blob_path.exists(), "blob file should exist: {:?}", blob_path);

    let blob_data = std::fs::read(&blob_path).unwrap();
    // For single-part files, blob size should match file length
    if file.num_parts == 1 {
        assert_eq!(blob_data.len() as u64, file.length,
            "blob size should match file length for {}", file.physical_name);
    }
}

#[test]
fn test_all_snapshot_versions() {
    let base = Path::new(concat!(env!("CARGO_MANIFEST_DIR"), "/../RFS/test-resources/snapshots"));
    let cases = [
        ("ES_7_10_BWC_Check", "rfs-snapshot"),
        ("ES_7_10_Updates_Deletes_w_Soft", "rfs_snapshot"),
        ("ES_7_10_Updates_Deletes_wo_Soft", "rfs_snapshot"),
        ("ES_6_8_Updates_Deletes_Native", "rfs_snapshot"),
        ("ES_6_8_Updates_Deletes_Merged", "rfs_snapshot"),
    ];

    for (dir, snap_name) in &cases {
        let repo_root = base.join(dir);
        if !repo_root.exists() {
            continue;
        }

        let resolved = repo::load_repo(&repo_root, snap_name)
            .unwrap_or_else(|e| panic!("Failed to load repo {}: {}", dir, e));
        assert!(!resolved.indices.is_empty(), "{} should have indices", dir);

        // Try to parse shard metadata for each index
        for (idx_name, idx_info) in &resolved.indices {
            for shard_id in 0..idx_info.num_shards {
                let shard_meta_path = repo_root
                    .join("indices")
                    .join(&idx_info.id)
                    .join(shard_id.to_string())
                    .join(format!("snap-{}.dat", resolved.snapshot_id));

                if !shard_meta_path.exists() {
                    continue;
                }

                let raw = std::fs::read(&shard_meta_path).unwrap();
                let smile_value = metadata::load_shard_metadata(&raw)
                    .unwrap_or_else(|e| panic!("Failed to parse shard metadata for {}/{}/{}: {}", dir, idx_name, shard_id, e));
                let files = metadata::parse_shard_files(&smile_value)
                    .unwrap_or_else(|e| panic!("Failed to parse files for {}/{}/{}: {}", dir, idx_name, shard_id, e));

                // Verify blob files exist for non-virtual files
                for f in &files {
                    if !f.is_virtual {
                        let blob_path = repo_root
                            .join("indices")
                            .join(&idx_info.id)
                            .join(shard_id.to_string())
                            .join(f.part_name(0));
                        assert!(blob_path.exists(),
                            "Blob missing for {}/{}/{}/{}: {:?}",
                            dir, idx_name, shard_id, f.physical_name, blob_path);
                    }
                }
            }
        }
    }
}
