/// FUSE filesystem that presents virtual Lucene files from ES/OS snapshot blobs.
///
/// Uses fuse3's async Filesystem trait (`&self` + `async fn`) so the tokio
/// multi-threaded runtime can serve many FUSE read requests concurrently —
/// critical when the underlying I/O goes through mount-s3.
use crate::metadata::{self, ShardFileEntry};
use crate::repo::ResolvedRepo;
use bytes::Bytes;
use fuse3::raw::prelude::*;
use fuse3::raw::reply::{DirectoryEntry, ReplyDirectory};
use fuse3::{Errno, Inode, MountOptions, Result, Timestamp};
use futures_util::stream;
use log::{debug, error, info};
use std::collections::HashMap;
use std::ffi::{OsStr, OsString};
use std::num::NonZeroU32;
use std::path::{Path, PathBuf};
use std::sync::RwLock;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncSeekExt};

const TTL: Duration = Duration::from_secs(3600);
const BLOCK_SIZE: u32 = 512;
const ZERO_TS: Timestamp = Timestamp { sec: 0, nsec: 0 };

#[derive(Debug, Clone)]
enum InodeEntry {
    Root,
    IndexDir { name: String },
    ShardDir { index_name: String, shard_id: u32 },
    LuceneFile {
        index_name: String,
        shard_id: u32,
        file_entry: ShardFileEntry,
    },
}

pub struct SnapshotFs {
    repo_root: PathBuf,
    resolved: ResolvedRepo,
    inodes: RwLock<Vec<InodeEntry>>,
    shard_file_inodes: RwLock<HashMap<(String, u32), Vec<u64>>>,
    loaded_shards: RwLock<HashMap<(String, u32), bool>>,
}

impl SnapshotFs {
    pub fn new(repo_root: PathBuf, resolved: ResolvedRepo) -> Self {
        let mut inodes = vec![InodeEntry::Root]; // inode 1 (index 0)

        let mut sorted_indices: Vec<_> = resolved.indices.keys().cloned().collect();
        sorted_indices.sort();
        for name in &sorted_indices {
            inodes.push(InodeEntry::IndexDir { name: name.clone() });
        }
        for name in &sorted_indices {
            let idx = &resolved.indices[name];
            for shard_id in 0..idx.num_shards {
                inodes.push(InodeEntry::ShardDir {
                    index_name: name.clone(),
                    shard_id: shard_id as u32,
                });
            }
        }

        SnapshotFs {
            repo_root,
            resolved,
            inodes: RwLock::new(inodes),
            shard_file_inodes: RwLock::new(HashMap::new()),
            loaded_shards: RwLock::new(HashMap::new()),
        }
    }

    pub async fn mount(self, mountpoint: &Path) -> std::io::Result<()> {
        let mut opts = MountOptions::default();
        opts.fs_name("snapshot-fuse")
            .allow_other(true)
            .default_permissions(true)
            .read_only(true);

        info!("Mounting snapshot FUSE at {:?}", mountpoint);
        let handle = Session::new(opts)
            .mount(self, mountpoint)
            .await?;

        // Wait for SIGTERM/SIGINT then unmount cleanly
        tokio::signal::ctrl_c().await.ok();
        info!("Received shutdown signal, unmounting");
        handle.unmount().await?;
        Ok(())
    }

    fn ino_to_idx(ino: Inode) -> usize {
        (ino - 1) as usize
    }
    fn idx_to_ino(idx: usize) -> Inode {
        (idx + 1) as Inode
    }

    fn get_entry(&self, ino: Inode) -> Option<InodeEntry> {
        self.inodes.read().unwrap().get(Self::ino_to_idx(ino)).cloned()
    }

    fn find_child_inode(&self, parent: Inode, name: &str) -> Option<Inode> {
        let parent_entry = {
            let inodes = self.inodes.read().unwrap();
            inodes.get(Self::ino_to_idx(parent))?.clone()
        };
        match &parent_entry {
            InodeEntry::Root => {
                let inodes = self.inodes.read().unwrap();
                for (i, e) in inodes.iter().enumerate() {
                    if let InodeEntry::IndexDir { name: n } = e {
                        if n == name { return Some(Self::idx_to_ino(i)); }
                    }
                }
                None
            }
            InodeEntry::IndexDir { name: idx_name } => {
                if let Ok(shard_id) = name.parse::<u32>() {
                    let inodes = self.inodes.read().unwrap();
                    for (i, e) in inodes.iter().enumerate() {
                        if let InodeEntry::ShardDir { index_name, shard_id: sid } = e {
                            if index_name == idx_name && *sid == shard_id {
                                return Some(Self::idx_to_ino(i));
                            }
                        }
                    }
                }
                None
            }
            InodeEntry::ShardDir { index_name, shard_id } => {
                self.ensure_shard_loaded(&index_name, *shard_id);
                let inodes = self.inodes.read().unwrap();
                for (i, e) in inodes.iter().enumerate() {
                    if let InodeEntry::LuceneFile { index_name: iname, shard_id: sid, file_entry } = e {
                        if iname == index_name && *sid == *shard_id && file_entry.physical_name == name {
                            return Some(Self::idx_to_ino(i));
                        }
                    }
                }
                None
            }
            _ => None,
        }
    }

    fn ensure_shard_loaded(&self, index_name: &str, shard_id: u32) {
        let key = (index_name.to_string(), shard_id);
        {
            let loaded = self.loaded_shards.read().unwrap();
            if loaded.contains_key(&key) { return; }
        }

        let index_info = match self.resolved.indices.get(index_name) {
            Some(info) => info,
            None => return,
        };

        let shard_meta_path = self.repo_root
            .join("indices").join(&index_info.id)
            .join(shard_id.to_string())
            .join(format!("snap-{}.dat", self.resolved.snapshot_id));

        info!("Loading shard metadata: {:?}", shard_meta_path);

        let raw = match std::fs::read(&shard_meta_path) {
            Ok(data) => data,
            Err(e) => { error!("Failed to read shard metadata {:?}: {}", shard_meta_path, e); return; }
        };
        let smile_value = match metadata::load_shard_metadata(&raw) {
            Ok(v) => v,
            Err(e) => { error!("Failed to parse shard metadata {:?}: {}", shard_meta_path, e); return; }
        };
        let files = match metadata::parse_shard_files(&smile_value) {
            Ok(f) => f,
            Err(e) => { error!("Failed to parse shard files {:?}: {}", shard_meta_path, e); return; }
        };

        let mut inodes = self.inodes.write().unwrap();
        let mut shard_files = self.shard_file_inodes.write().unwrap();
        let mut file_inos = Vec::new();
        for file_entry in files {
            let ino_idx = inodes.len();
            inodes.push(InodeEntry::LuceneFile {
                index_name: index_name.to_string(),
                shard_id,
                file_entry,
            });
            file_inos.push(Self::idx_to_ino(ino_idx));
        }
        shard_files.insert(key.clone(), file_inos);
        drop(inodes);
        drop(shard_files);

        self.loaded_shards.write().unwrap().insert(key, true);
    }

    fn make_dir_attr(&self, ino: Inode) -> FileAttr {
        FileAttr {
            ino,
            size: 0,
            blocks: 0,
            atime: ZERO_TS,
            mtime: ZERO_TS,
            ctime: ZERO_TS,
            kind: FileType::Directory,
            perm: 0o555,
            nlink: 2,
            uid: 0,
            gid: 0,
            rdev: 0,
            blksize: BLOCK_SIZE,
        }
    }

    fn make_file_attr(&self, ino: Inode, size: u64) -> FileAttr {
        FileAttr {
            ino,
            size,
            blocks: (size + BLOCK_SIZE as u64 - 1) / BLOCK_SIZE as u64,
            atime: ZERO_TS,
            mtime: ZERO_TS,
            ctime: ZERO_TS,
            kind: FileType::RegularFile,
            perm: 0o444,
            nlink: 1,
            uid: 0,
            gid: 0,
            rdev: 0,
            blksize: BLOCK_SIZE,
        }
    }

    /// Read file data using async I/O — allows concurrent reads across tokio tasks.
    async fn read_file_data(&self, entry: &ShardFileEntry, index_name: &str, shard_id: u32, offset: u64, size: u32) -> Vec<u8> {
        let index_info = match self.resolved.indices.get(index_name) {
            Some(info) => info,
            None => return vec![],
        };

        if entry.is_virtual {
            let start = offset as usize;
            if start >= entry.meta_hash.len() { return vec![]; }
            let end = std::cmp::min(start + size as usize, entry.meta_hash.len());
            return entry.meta_hash[start..end].to_vec();
        }

        let blob_dir = self.repo_root
            .join("indices").join(&index_info.id)
            .join(shard_id.to_string());

        let mut result = Vec::with_capacity(size as usize);
        let mut remaining = size as u64;
        let mut file_offset = offset;

        let part_size = if entry.part_size == u64::MAX { entry.length } else { entry.part_size };

        while remaining > 0 && file_offset < entry.length {
            let part_idx = file_offset / part_size;
            let offset_in_part = file_offset % part_size;
            let blob_path = blob_dir.join(entry.part_name(part_idx));

            let this_part_len = if part_idx == entry.num_parts - 1 {
                entry.length - part_idx * part_size
            } else {
                part_size
            };
            let available = this_part_len.saturating_sub(offset_in_part);
            let to_read = std::cmp::min(remaining, available) as usize;

            match tokio::fs::File::open(&blob_path).await {
                Ok(mut f) => {
                    if let Err(e) = f.seek(std::io::SeekFrom::Start(offset_in_part)).await {
                        error!("Failed to seek in blob {:?}: {}", blob_path, e);
                        break;
                    }
                    let mut buf = vec![0u8; to_read];
                    match f.read_exact(&mut buf).await {
                        Ok(_) => result.extend_from_slice(&buf),
                        Err(e) => { error!("Failed to read blob {:?}: {}", blob_path, e); break; }
                    }
                }
                Err(e) => { error!("Failed to open blob {:?}: {}", blob_path, e); break; }
            }

            file_offset += to_read as u64;
            remaining -= to_read as u64;
        }

        result
    }

    fn attr_for_entry(&self, ino: Inode, entry: &InodeEntry) -> FileAttr {
        match entry {
            InodeEntry::Root | InodeEntry::IndexDir { .. } | InodeEntry::ShardDir { .. } => {
                self.make_dir_attr(ino)
            }
            InodeEntry::LuceneFile { file_entry, .. } => {
                self.make_file_attr(ino, file_entry.length)
            }
        }
    }
}

impl Filesystem for SnapshotFs {
    async fn init(&self, _req: Request) -> Result<ReplyInit> {
        Ok(ReplyInit {
            max_write: NonZeroU32::new(1024 * 1024).unwrap(),
        })
    }

    async fn destroy(&self, _req: Request) {}

    async fn lookup(&self, _req: Request, parent: Inode, name: &OsStr) -> Result<ReplyEntry> {
        let name_str = name.to_string_lossy();
        debug!("lookup: parent={}, name={}", parent, name_str);

        let ino = self.find_child_inode(parent, &name_str)
            .ok_or_else(|| Errno::from(libc::ENOENT))?;
        let entry = self.get_entry(ino)
            .ok_or_else(|| Errno::from(libc::ENOENT))?;

        Ok(ReplyEntry {
            ttl: TTL,
            attr: self.attr_for_entry(ino, &entry),
            generation: 0,
        })
    }

    async fn getattr(
        &self,
        _req: Request,
        inode: Inode,
        _fh: Option<u64>,
        _flags: u32,
    ) -> Result<ReplyAttr> {
        debug!("getattr: ino={}", inode);
        let entry = self.get_entry(inode)
            .ok_or_else(|| Errno::from(libc::ENOENT))?;
        Ok(ReplyAttr {
            ttl: TTL,
            attr: self.attr_for_entry(inode, &entry),
        })
    }

    async fn read(
        &self,
        _req: Request,
        inode: Inode,
        _fh: u64,
        offset: u64,
        size: u32,
    ) -> Result<ReplyData> {
        debug!("read: ino={}, offset={}, size={}", inode, offset, size);

        let entry = self.get_entry(inode)
            .ok_or_else(|| Errno::from(libc::ENOENT))?;

        match entry {
            InodeEntry::LuceneFile { index_name, shard_id, file_entry } => {
                if offset >= file_entry.length {
                    return Ok(ReplyData { data: Bytes::new() });
                }
                let data = self.read_file_data(&file_entry, &index_name, shard_id, offset, size).await;
                Ok(ReplyData { data: Bytes::from(data) })
            }
            _ => Err(Errno::from(libc::EISDIR)),
        }
    }

    async fn readdir(
        &self,
        _req: Request,
        inode: Inode,
        _fh: u64,
        offset: i64,
    ) -> Result<ReplyDirectory<impl futures_util::Stream<Item = fuse3::Result<DirectoryEntry>> + Send>> {
        debug!("readdir: ino={}, offset={}", inode, offset);

        let entry = self.get_entry(inode)
            .ok_or_else(|| Errno::from(libc::ENOENT))?;

        let mut entries: Vec<(Inode, FileType, String)> = vec![
            (inode, FileType::Directory, ".".to_string()),
            (1, FileType::Directory, "..".to_string()),
        ];

        match entry {
            InodeEntry::Root => {
                let inodes = self.inodes.read().unwrap();
                for (i, e) in inodes.iter().enumerate() {
                    if let InodeEntry::IndexDir { name } = e {
                        entries.push((Self::idx_to_ino(i), FileType::Directory, name.clone()));
                    }
                }
            }
            InodeEntry::IndexDir { name } => {
                let inodes = self.inodes.read().unwrap();
                for (i, e) in inodes.iter().enumerate() {
                    if let InodeEntry::ShardDir { index_name, shard_id } = e {
                        if index_name == &name {
                            entries.push((Self::idx_to_ino(i), FileType::Directory, shard_id.to_string()));
                        }
                    }
                }
            }
            InodeEntry::ShardDir { index_name, shard_id } => {
                self.ensure_shard_loaded(&index_name, shard_id);
                let inodes = self.inodes.read().unwrap();
                for (i, e) in inodes.iter().enumerate() {
                    if let InodeEntry::LuceneFile { index_name: iname, shard_id: s, file_entry } = e {
                        if iname == &index_name && *s == shard_id {
                            entries.push((Self::idx_to_ino(i), FileType::RegularFile, file_entry.physical_name.clone()));
                        }
                    }
                }
            }
            _ => return Err(Errno::from(libc::ENOTDIR)),
        }

        let dir_entries: Vec<fuse3::Result<DirectoryEntry>> = entries
            .into_iter()
            .enumerate()
            .skip(offset as usize)
            .map(|(i, (ino, kind, name))| {
                Ok(DirectoryEntry {
                    inode: ino,
                    kind,
                    name: OsString::from(name),
                    offset: (i + 1) as i64,
                })
            })
            .collect();

        Ok(ReplyDirectory {
            entries: stream::iter(dir_entries),
        })
    }

    /// Accept setxattr as a no-op — required for SELinux relabeling in K8s.
    async fn setxattr(
        &self,
        _req: Request,
        _inode: Inode,
        _name: &OsStr,
        _value: &[u8],
        _flags: u32,
        _position: u32,
    ) -> Result<()> {
        Ok(())
    }
}
