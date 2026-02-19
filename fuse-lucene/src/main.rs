use clap::Parser;
use log::info;
use std::path::PathBuf;

#[derive(Parser)]
#[command(name = "snapshot-fuse", about = "FUSE layer for ES/OS snapshot blob → Lucene file translation")]
struct Args {
    /// Path to the snapshot repo root (e.g., /mnt/s3/path/to/repo)
    #[arg(long)]
    repo_root: PathBuf,

    /// Snapshot name to serve
    #[arg(long)]
    snapshot_name: String,

    /// Mount point for the virtual Lucene filesystem
    #[arg(long)]
    mount_point: PathBuf,
}

fn main() -> std::io::Result<()> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    let args = Args::parse();

    info!("Loading repo metadata from {:?}", args.repo_root);
    let resolved = snapshot_fuse::repo::load_repo(&args.repo_root, &args.snapshot_name)?;
    info!("Resolved snapshot '{}' (id={}): {} indices",
        resolved.snapshot_name, resolved.snapshot_id, resolved.indices.len());

    for (name, idx) in &resolved.indices {
        info!("  Index '{}' (id={}, {} shards)", name, idx.id, idx.num_shards);
    }

    // Create mount point if it doesn't exist
    std::fs::create_dir_all(&args.mount_point)?;

    let filesystem = snapshot_fuse::fs::SnapshotFs::new(args.repo_root, resolved);
    info!("Starting FUSE mount at {:?}", args.mount_point);
    filesystem.mount(&args.mount_point)
}
