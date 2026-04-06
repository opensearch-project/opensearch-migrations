/// FUSE filesystem that presents virtual Lucene files from ES/OS snapshot blobs.
///
/// Uses fuser 0.17's multi-threaded dispatch (`&self` + `n_threads` + `clone_fd`)
/// so multiple FUSE read requests are served concurrently — critical when the
/// underlying I/O goes through mount-s3.
use crate::metadata::{self, ShardFileEntry};
use crate::repo::ResolvedRepo;
use fuser::{
    Config, Errno, FileAttr, FileType, Filesystem, INodeNo, MountOption, ReplyAttr, ReplyData,
    ReplyDirectory, ReplyEmpty, ReplyEntry, Request, SessionACL,
};
use log::{debug, error, info};
use std::collections::HashMap;
use std::ffi::OsStr;
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::sync::RwLock;
use std::time::{Duration, UNIX_EPOCH};

const TTL: Duration = Duration::from_secs(3600);
const BLOCK_SIZE: u32 = 512;

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
    /// Cache of open file handles to avoid repeated open/close on S3-backed blobs.
    /// Key is the blob path, value is the open File handle.
    blob_cache: std::sync::Mutex<HashMap<PathBuf, fs::File>>,
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
            blob_cache: std::sync::Mutex::new(HashMap::new()),
        }
    }

    /// Mount with multi-threaded FUSE dispatch.
    /// Each thread gets its own /dev/fuse fd via clone_fd, enabling true parallel
    /// request processing — multiple concurrent reads from the bulk-loader are
    /// served simultaneously instead of being serialized.
    pub fn mount(self, mountpoint: &Path, num_threads: usize) -> io::Result<()> {
        let mut config = Config::default();
        config.mount_options = vec![
            MountOption::FSName("snapshot-fuse".to_string()),
            MountOption::DefaultPermissions,
            MountOption::CUSTOM("seclabel".to_string()),
        ];
        config.acl = SessionACL::All;
        config.n_threads = Some(num_threads);
        config.clone_fd = true;
        info!("Mounting snapshot FUSE at {:?} with {} threads", mountpoint, num_threads);
        fuser::mount2(self, mountpoint, &config)
    }

    fn ino(idx: usize) -> INodeNo { INodeNo((idx + 1) as u64) }
    fn idx(ino: INodeNo) -> usize { (u64::from(ino) - 1) as usize }

    fn get_entry(&self, ino: INodeNo) -> Option<InodeEntry> {
        self.inodes.read().unwrap().get(Self::idx(ino)).cloned()
    }

    fn find_child_inode(&self, parent: INodeNo, name: &str) -> Option<INodeNo> {
        let parent_entry = {
            let inodes = self.inodes.read().unwrap();
            inodes.get(Self::idx(parent))?.clone()
        };
        match &parent_entry {
            InodeEntry::Root => {
                let inodes = self.inodes.read().unwrap();
                for (i, e) in inodes.iter().enumerate() {
                    if let InodeEntry::IndexDir { name: n } = e {
                        if n == name { return Some(Self::ino(i)); }
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
                                return Some(Self::ino(i));
                            }
                        }
                    }
                }
                None
            }
            InodeEntry::ShardDir { index_name, shard_id } => {
                self.ensure_shard_loaded(index_name, *shard_id);
                let inodes = self.inodes.read().unwrap();
                for (i, e) in inodes.iter().enumerate() {
                    if let InodeEntry::LuceneFile { index_name: iname, shard_id: sid, file_entry } = e {
                        if iname == index_name && *sid == *shard_id && file_entry.physical_name == name {
                            return Some(Self::ino(i));
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

        let raw = match fs::read(&shard_meta_path) {
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
        let mut loaded = self.loaded_shards.write().unwrap();

        // Re-check under write lock to avoid duplicate entries from concurrent threads
        if loaded.contains_key(&key) { return; }

        let mut file_inos = Vec::new();
        for file_entry in files {
            let ino_idx = inodes.len();
            inodes.push(InodeEntry::LuceneFile {
                index_name: index_name.to_string(),
                shard_id,
                file_entry,
            });
            file_inos.push(u64::from(Self::ino(ino_idx)));
        }
        shard_files.insert(key.clone(), file_inos);
        loaded.insert(key, true);
    }

    fn make_dir_attr(&self, ino: INodeNo) -> FileAttr {
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

    fn make_file_attr(&self, ino: INodeNo, size: u64) -> FileAttr {
        FileAttr {
            ino,
            size,
            blocks: size.div_ceil(BLOCK_SIZE as u64),
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

    fn read_file_data(&self, entry: &ShardFileEntry, index_name: &str, shard_id: u32, offset: u64, size: u32) -> Vec<u8> {
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

            // Get or open a cached file handle. We use pread (read_at) which is
            // thread-safe on the same fd — no seek needed, no mutex contention.
            let file = {
                let cache = self.blob_cache.lock().unwrap();
                cache.get(&blob_path).map(|f| f.try_clone().ok()).flatten()
            };
            let file = match file {
                Some(f) => f,
                None => {
                    match fs::File::open(&blob_path) {
                        Ok(f) => {
                            let cloned = f.try_clone().ok();
                            self.blob_cache.lock().unwrap().insert(blob_path.clone(), f);
                            match cloned {
                                Some(c) => c,
                                None => { error!("Failed to clone fd for {:?}", blob_path); break; }
                            }
                        }
                        Err(e) => { error!("Failed to open blob {:?}: {}", blob_path, e); break; }
                    }
                }
            };

            use std::os::unix::fs::FileExt;
            let mut buf = vec![0u8; to_read];
            match file.read_at(&mut buf, offset_in_part) {
                Ok(n) => result.extend_from_slice(&buf[..n]),
                Err(e) => { error!("Failed to read blob {:?}: {}", blob_path, e); break; }
            }

            file_offset += to_read as u64;
            remaining -= to_read as u64;
        }

        result
    }

    fn attr_for_entry(&self, ino: INodeNo, entry: &InodeEntry) -> FileAttr {
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
    fn lookup(&self, _req: &Request, parent: INodeNo, name: &OsStr, reply: ReplyEntry) {
        let name_str = name.to_string_lossy();
        debug!("lookup: parent={:?}, name={}", parent, name_str);

        match self.find_child_inode(parent, &name_str) {
            Some(ino) => {
                let entry = self.get_entry(ino).unwrap();
                reply.entry(&TTL, &self.attr_for_entry(ino, &entry), fuser::Generation(0));
            }
            None => reply.error(Errno::ENOENT),
        }
    }

    fn getattr(&self, _req: &Request, ino: INodeNo, _fh: Option<fuser::FileHandle>, reply: ReplyAttr) {
        debug!("getattr: ino={:?}", ino);
        match self.get_entry(ino) {
            Some(entry) => reply.attr(&TTL, &self.attr_for_entry(ino, &entry)),
            None => reply.error(Errno::ENOENT),
        }
    }

    fn readdir(&self, _req: &Request, ino: INodeNo, _fh: fuser::FileHandle, offset: u64, mut reply: ReplyDirectory) {
        debug!("readdir: ino={:?}, offset={}", ino, offset);

        let entry = match self.get_entry(ino) {
            Some(e) => e,
            None => { reply.error(Errno::ENOENT); return; }
        };

        let mut entries: Vec<(INodeNo, FileType, String)> = vec![
            (ino, FileType::Directory, ".".to_string()),
            (INodeNo(1), FileType::Directory, "..".to_string()),
        ];

        match entry {
            InodeEntry::Root => {
                let inodes = self.inodes.read().unwrap();
                for (i, e) in inodes.iter().enumerate() {
                    if let InodeEntry::IndexDir { name } = e {
                        entries.push((Self::ino(i), FileType::Directory, name.clone()));
                    }
                }
            }
            InodeEntry::IndexDir { name } => {
                let inodes = self.inodes.read().unwrap();
                for (i, e) in inodes.iter().enumerate() {
                    if let InodeEntry::ShardDir { index_name, shard_id } = e {
                        if index_name == &name {
                            entries.push((Self::ino(i), FileType::Directory, shard_id.to_string()));
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
                            entries.push((Self::ino(i), FileType::RegularFile, file_entry.physical_name.clone()));
                        }
                    }
                }
            }
            _ => { reply.error(Errno::ENOTDIR); return; }
        }

        for (i, (ino, kind, name)) in entries.iter().enumerate().skip(offset as usize) {
            if reply.add(*ino, (i + 1) as u64, *kind, name) {
                break;
            }
        }
        reply.ok();
    }

    fn read(&self, _req: &Request, ino: INodeNo, _fh: fuser::FileHandle, offset: u64, size: u32, _flags: fuser::OpenFlags, _lock_owner: Option<fuser::LockOwner>, reply: ReplyData) {
        debug!("read: ino={:?}, offset={}, size={}", ino, offset, size);

        let entry = match self.get_entry(ino) {
            Some(e) => e,
            None => { reply.error(Errno::ENOENT); return; }
        };

        match entry {
            InodeEntry::LuceneFile { index_name, shard_id, file_entry } => {
                if offset >= file_entry.length {
                    reply.data(&[]);
                    return;
                }
                let data = self.read_file_data(&file_entry, &index_name, shard_id, offset, size);
                reply.data(&data);
            }
            _ => reply.error(Errno::EISDIR),
        }
    }

    /// Accept setxattr as a no-op — required for SELinux relabeling in K8s.
    fn setxattr(&self, _req: &Request, _ino: INodeNo, _name: &OsStr, _value: &[u8], _flags: i32, _position: u32, reply: ReplyEmpty) {
        reply.ok();
    }

    /// Return empty data for getxattr — no xattrs stored.
    fn getxattr(&self, _req: &Request, _ino: INodeNo, _name: &OsStr, _size: u32, reply: fuser::ReplyXattr) {
        reply.error(Errno::ENODATA);
    }

    /// Return empty list for listxattr.
    fn listxattr(&self, _req: &Request, _ino: INodeNo, _size: u32, reply: fuser::ReplyXattr) {
        reply.size(0);
    }
}
