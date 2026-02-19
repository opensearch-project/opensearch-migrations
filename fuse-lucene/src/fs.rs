/// FUSE filesystem that presents virtual Lucene files from ES/OS snapshot blobs.
///
/// Directory structure presented:
///   /mount_point/<indexName>/<shardId>/<luceneFile>
///
/// Reads are served lazily from the underlying blob files on the S3 FUSE mount.
use crate::metadata::{self, ShardFileEntry};
use crate::repo::ResolvedRepo;
use fuser::{
    FileAttr, FileType, Filesystem, ReplyAttr, ReplyData, ReplyDirectory, ReplyEntry,
    Request, MountOption,
};
use libc::{ENOENT, EISDIR, ENOTDIR};
use log::{debug, error, info};
use std::collections::HashMap;
use std::ffi::OsStr;
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::RwLock;
use std::time::{Duration, UNIX_EPOCH};

const TTL: Duration = Duration::from_secs(3600);
const BLOCK_SIZE: u32 = 512;

/// Inode allocation scheme:
/// 1 = root (/)
/// 2..N = index directories
/// N+1.. = shard directories
/// M+1.. = files within shards
///
/// We use a flat inode map for simplicity.

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
    /// inode → entry mapping
    inodes: RwLock<Vec<InodeEntry>>,
    /// (index_name, shard_id) → list of inode numbers for files in that shard
    shard_file_inodes: RwLock<HashMap<(String, u32), Vec<u64>>>,
    /// Track which shards have been loaded
    loaded_shards: RwLock<HashMap<(String, u32), bool>>,
}

impl SnapshotFs {
    pub fn new(repo_root: PathBuf, resolved: ResolvedRepo) -> Self {
        // Pre-allocate inodes for root and index directories
        let mut inodes = vec![InodeEntry::Root]; // inode 1 (index 0)

        // Add index directories
        let mut sorted_indices: Vec<_> = resolved.indices.keys().cloned().collect();
        sorted_indices.sort();
        for name in &sorted_indices {
            inodes.push(InodeEntry::IndexDir { name: name.clone() });
        }

        // Add shard directories
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

    pub fn mount(self, mountpoint: &Path) -> io::Result<()> {
        let options = vec![
            MountOption::RO,
            MountOption::FSName("snapshot-fuse".to_string()),
            MountOption::AllowOther,
            MountOption::DefaultPermissions,
        ];
        info!("Mounting snapshot FUSE at {:?}", mountpoint);
        fuser::mount2(self, mountpoint, &options)
            .map_err(|e| io::Error::new(io::ErrorKind::Other, e))
    }

    fn inode_to_index(ino: u64) -> usize {
        (ino - 1) as usize
    }

    fn index_to_inode(idx: usize) -> u64 {
        (idx + 1) as u64
    }

    fn get_entry(&self, ino: u64) -> Option<InodeEntry> {
        let inodes = self.inodes.read().unwrap();
        let idx = Self::inode_to_index(ino);
        inodes.get(idx).cloned()
    }

    fn find_child_inode(&self, parent_ino: u64, name: &str) -> Option<u64> {
        let parent_entry = {
            let inodes = self.inodes.read().unwrap();
            inodes.get(Self::inode_to_index(parent_ino))?.clone()
        };

        match &parent_entry {
            InodeEntry::Root => {
                let inodes = self.inodes.read().unwrap();
                for (i, entry) in inodes.iter().enumerate() {
                    if let InodeEntry::IndexDir { name: n } = entry {
                        if n == name {
                            return Some(Self::index_to_inode(i));
                        }
                    }
                }
                None
            }
            InodeEntry::IndexDir { name: idx_name } => {
                if let Ok(shard_id) = name.parse::<u32>() {
                    let inodes = self.inodes.read().unwrap();
                    for (i, entry) in inodes.iter().enumerate() {
                        if let InodeEntry::ShardDir { index_name, shard_id: sid } = entry {
                            if index_name == idx_name && *sid == shard_id {
                                return Some(Self::index_to_inode(i));
                            }
                        }
                    }
                }
                None
            }
            InodeEntry::ShardDir { index_name, shard_id } => {
                self.ensure_shard_loaded(index_name, *shard_id);
                let inodes = self.inodes.read().unwrap();
                for (i, entry) in inodes.iter().enumerate() {
                    if let InodeEntry::LuceneFile { index_name: iname, shard_id: sid, file_entry } = entry {
                        if iname == index_name && *sid == *shard_id && file_entry.physical_name == name {
                            return Some(Self::index_to_inode(i));
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

        // Check if already loaded
        {
            let loaded = self.loaded_shards.read().unwrap();
            if loaded.contains_key(&key) {
                return;
            }
        }

        // Load shard metadata
        let index_info = match self.resolved.indices.get(index_name) {
            Some(info) => info,
            None => return,
        };

        let shard_meta_path = self.repo_root
            .join("indices")
            .join(&index_info.id)
            .join(shard_id.to_string())
            .join(format!("snap-{}.dat", self.resolved.snapshot_id));

        info!("Loading shard metadata: {:?}", shard_meta_path);

        let raw = match fs::read(&shard_meta_path) {
            Ok(data) => data,
            Err(e) => {
                error!("Failed to read shard metadata {:?}: {}", shard_meta_path, e);
                return;
            }
        };

        let smile_value = match metadata::load_shard_metadata(&raw) {
            Ok(v) => v,
            Err(e) => {
                error!("Failed to parse shard metadata {:?}: {}", shard_meta_path, e);
                return;
            }
        };

        let files = match metadata::parse_shard_files(&smile_value) {
            Ok(f) => f,
            Err(e) => {
                error!("Failed to parse shard files {:?}: {}", shard_meta_path, e);
                return;
            }
        };

        // Add file inodes
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
            file_inos.push(Self::index_to_inode(ino_idx));
        }

        shard_files.insert(key.clone(), file_inos);
        drop(inodes);
        drop(shard_files);

        let mut loaded = self.loaded_shards.write().unwrap();
        loaded.insert(key, true);
    }

    fn make_dir_attr(&self, ino: u64) -> FileAttr {
        FileAttr {
            ino,
            size: 0,
            blocks: 0,
            atime: UNIX_EPOCH,
            mtime: UNIX_EPOCH,
            ctime: UNIX_EPOCH,
            crtime: UNIX_EPOCH,
            kind: FileType::Directory,
            perm: 0o555,
            nlink: 2,
            uid: 0,
            gid: 0,
            rdev: 0,
            blksize: BLOCK_SIZE,
            flags: 0,
        }
    }

    fn make_file_attr(&self, ino: u64, size: u64) -> FileAttr {
        FileAttr {
            ino,
            size,
            blocks: (size + BLOCK_SIZE as u64 - 1) / BLOCK_SIZE as u64,
            atime: UNIX_EPOCH,
            mtime: UNIX_EPOCH,
            ctime: UNIX_EPOCH,
            crtime: UNIX_EPOCH,
            kind: FileType::RegularFile,
            perm: 0o444,
            nlink: 1,
            uid: 0,
            gid: 0,
            rdev: 0,
            blksize: BLOCK_SIZE,
            flags: 0,
        }
    }

    /// Read file data from blob(s) for a given offset and size.
    fn read_file_data(&self, entry: &ShardFileEntry, index_name: &str, shard_id: u32, offset: u64, size: u32) -> Vec<u8> {
        let index_info = match self.resolved.indices.get(index_name) {
            Some(info) => info,
            None => return vec![],
        };

        if entry.is_virtual {
            // Virtual file: content is just meta_hash bytes
            let start = offset as usize;
            if start >= entry.meta_hash.len() {
                return vec![];
            }
            let end = std::cmp::min(start + size as usize, entry.meta_hash.len());
            return entry.meta_hash[start..end].to_vec();
        }

        let blob_dir = self.repo_root
            .join("indices")
            .join(&index_info.id)
            .join(shard_id.to_string());

        let mut result = Vec::with_capacity(size as usize);
        let mut remaining = size as u64;
        let mut file_offset = offset;

        // Determine which part(s) to read from
        let part_size = if entry.part_size == u64::MAX {
            entry.length
        } else {
            entry.part_size
        };

        while remaining > 0 && file_offset < entry.length {
            let part_idx = file_offset / part_size;
            let offset_in_part = file_offset % part_size;
            let part_name = entry.part_name(part_idx);
            let blob_path = blob_dir.join(&part_name);

            // Calculate how much to read from this part
            let this_part_len = if part_idx == entry.num_parts - 1 {
                // Last part may be shorter
                entry.length - part_idx * part_size
            } else {
                part_size
            };
            let available = this_part_len.saturating_sub(offset_in_part);
            let to_read = std::cmp::min(remaining, available) as usize;

            match fs::File::open(&blob_path) {
                Ok(mut f) => {
                    use std::io::Seek;
                    if let Err(e) = f.seek(std::io::SeekFrom::Start(offset_in_part)) {
                        error!("Failed to seek in blob {:?}: {}", blob_path, e);
                        break;
                    }
                    let mut buf = vec![0u8; to_read];
                    match f.read_exact(&mut buf) {
                        Ok(()) => result.extend_from_slice(&buf),
                        Err(e) => {
                            error!("Failed to read blob {:?}: {}", blob_path, e);
                            break;
                        }
                    }
                }
                Err(e) => {
                    error!("Failed to open blob {:?}: {}", blob_path, e);
                    break;
                }
            }

            file_offset += to_read as u64;
            remaining -= to_read as u64;
        }

        result
    }
}

use std::io;

impl Filesystem for SnapshotFs {
    fn lookup(&mut self, _req: &Request, parent: u64, name: &OsStr, reply: ReplyEntry) {
        let name_str = name.to_string_lossy();
        debug!("lookup: parent={}, name={}", parent, name_str);

        match self.find_child_inode(parent, &name_str) {
            Some(ino) => {
                let entry = self.get_entry(ino).unwrap();
                let attr = match &entry {
                    InodeEntry::Root | InodeEntry::IndexDir { .. } | InodeEntry::ShardDir { .. } => {
                        self.make_dir_attr(ino)
                    }
                    InodeEntry::LuceneFile { file_entry, .. } => {
                        self.make_file_attr(ino, file_entry.length)
                    }
                };
                reply.entry(&TTL, &attr, 0);
            }
            None => reply.error(ENOENT),
        }
    }

    fn getattr(&mut self, _req: &Request, ino: u64, _fh: Option<u64>, reply: ReplyAttr) {
        debug!("getattr: ino={}", ino);
        match self.get_entry(ino) {
            Some(entry) => {
                let attr = match &entry {
                    InodeEntry::Root | InodeEntry::IndexDir { .. } | InodeEntry::ShardDir { .. } => {
                        self.make_dir_attr(ino)
                    }
                    InodeEntry::LuceneFile { file_entry, .. } => {
                        self.make_file_attr(ino, file_entry.length)
                    }
                };
                reply.attr(&TTL, &attr);
            }
            None => reply.error(ENOENT),
        }
    }

    fn readdir(&mut self, _req: &Request, ino: u64, _fh: u64, offset: i64, mut reply: ReplyDirectory) {
        debug!("readdir: ino={}, offset={}", ino, offset);

        let entry = match self.get_entry(ino) {
            Some(e) => e,
            None => { reply.error(ENOENT); return; }
        };

        let mut entries: Vec<(u64, FileType, String)> = vec![
            (ino, FileType::Directory, ".".to_string()),
            (1, FileType::Directory, "..".to_string()),
        ];

        match entry {
            InodeEntry::Root => {
                let inodes = self.inodes.read().unwrap();
                for (i, e) in inodes.iter().enumerate() {
                    if let InodeEntry::IndexDir { name } = e {
                        entries.push((Self::index_to_inode(i), FileType::Directory, name.clone()));
                    }
                }
            }
            InodeEntry::IndexDir { name } => {
                let inodes = self.inodes.read().unwrap();
                for (i, e) in inodes.iter().enumerate() {
                    if let InodeEntry::ShardDir { index_name, shard_id } = e {
                        if index_name == &name {
                            entries.push((Self::index_to_inode(i), FileType::Directory, shard_id.to_string()));
                        }
                    }
                }
            }
            InodeEntry::ShardDir { index_name, shard_id } => {
                let idx_name = index_name.clone();
                let sid = shard_id;
                self.ensure_shard_loaded(&idx_name, sid);
                let inodes = self.inodes.read().unwrap();
                for (i, e) in inodes.iter().enumerate() {
                    if let InodeEntry::LuceneFile { index_name: iname, shard_id: s, file_entry } = e {
                        if iname == &idx_name && *s == sid {
                            entries.push((Self::index_to_inode(i), FileType::RegularFile, file_entry.physical_name.clone()));
                        }
                    }
                }
            }
            _ => { reply.error(ENOTDIR); return; }
        }

        for (i, (ino, kind, name)) in entries.iter().enumerate().skip(offset as usize) {
            if reply.add(*ino, (i + 1) as i64, *kind, name) {
                break; // buffer full
            }
        }
        reply.ok();
    }

    fn read(&mut self, _req: &Request, ino: u64, _fh: u64, offset: i64, size: u32, _flags: i32, _lock_owner: Option<u64>, reply: ReplyData) {
        debug!("read: ino={}, offset={}, size={}", ino, offset, size);

        let entry = match self.get_entry(ino) {
            Some(e) => e,
            None => { reply.error(ENOENT); return; }
        };

        match entry {
            InodeEntry::LuceneFile { index_name, shard_id, file_entry } => {
                if offset as u64 >= file_entry.length {
                    reply.data(&[]);
                    return;
                }
                let data = self.read_file_data(&file_entry, &index_name, shard_id, offset as u64, size);
                reply.data(&data);
            }
            InodeEntry::Root | InodeEntry::IndexDir { .. } | InodeEntry::ShardDir { .. } => {
                reply.error(EISDIR);
            }
        }
    }
}
