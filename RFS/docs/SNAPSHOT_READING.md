# Snapshot Reading

## Snapshots

Elasticsearch Snapshots are a directory tree containing both data and metadata.  Each Elasticsearch Index has its own sub-directory, and each Elasticsearch Shard has its own sub-directory under the directory of its parent Elasticsearch Index.  The raw data for a given Elasticsearch Shard is stored in its corresponding Shard sub-directory as a collection of Lucene files, which Elasticsearch obfuscates.  Metadata files exist in the snapshot to provide details about the snapshot as a whole, the source cluster’s global metadata and settings, each index in the snapshot, and each shard in the snapshot.

Below is an example for the structure of an Elasticsearch 7.10 snapshot, along with a breakdown of its contents

```
/filesystem/path/repo/snapshots/
├── index-0 <-------------------------------------------- [1]
├── index.latest
├── indices
│   └── awflAUIvQr2QPOA1lEKvDg <------------------------- [2]
│       ├── 0 <------------------------------------------ [3]
│       │   ├── __0KmMz-ttQ0entKtem7qBww <--------------- [4]
│       │   ├── __Wnwa_QGrRKCK7I67ua2kHQ <--------------- [4]
│       │   ├── __XTMbVKTYRPSRGvZSoXbUJg <--------------- [4]
│       │   ├── ___zjEiWaVRPCIWorgLuLylA <--------------- [4]
│       │   ├── index-0
│       │   └── snap-_SZcZ7U1Q6S1FDh-9CE44w.dat <-------- [5]
│       ├── 1
│       │   ├── __U5HnEjTqT0egQiv7QM0Hog
│       │   ├── index-0
│       │   └── snap-_SZcZ7U1Q6S1FDh-9CE44w.dat
│       ├── 2
│       │   ├── __3W4tnXrgQraVDRpPMtODtw
│       │   ├── __4Vy6CzG2SOiLTGrotZKdCQ
│       │   ├── __JGKggveFSwuEUV7L9NxXxw
│       │   ├── __cL1vrRtqRri0X9fWQuJngg
│       │   ├── __fVENodAeRh6Lky2SHDJDoA
│       │   ├── __kfqKHmMHR5enj7J1CxrMXg
│       │   ├── __px6tCEWaRkG5tgw7Akl8Ww
│       │   ├── __wkJg68EqTuOU_i7LQhypqg
│       │   ├── index-0
│       │   └── snap-_SZcZ7U1Q6S1FDh-9CE44w.dat
│       └── meta-_SZcZ7U1Q6S1FDh-9CE44w.dat <------------- [6]
├── meta-_SZcZ7U1Q6S1FDh-9CE44w.dat  <-------------------- [7]
└── snap-_SZcZ7U1Q6S1FDh-9CE44w.dat <--------------------- [8]
```

1. **Repository Metadata File**: JSON encoded; contains a mapping between the snapshots within the repo and the Elasticsearch Indices/Shards stored within it
2. **Index Directory**: Contains all the data/metadata for a specific Elasticsearch Index
3. **Shard Directory**: Contains all the data/metadata for a specific Shard of an Elasticsearch Index
4. **Lucene Files**: Lucene Index files, lightly-obfuscated by the snapshotting process; large files from the source filesystem are split in multiple parts
5. **Shard Metadata File**: SMILE encoded; contains details about all the Lucene files in the shard and a mapping between their in-Snapshot representation and their original representation on the source machine they were pulled from (e.g. original file name, etc)
6. **Index Metadata File**: SMILE encoded; contains things like the index aliases, settings, mappings, number of shards, etc
7. **Global Metadata File**: SMILE encoded; contains things like the legacy, index, and component templates
8. **Snapshot Metadata File**: SMILE encoded; contains things like whether the snapshot succeeded, the number of shards, how many shards succeeded, the ES/OS version, the indices in the snapshot, etc

## Unpacking a Snapshot

After an RFS worker acquires a lease for a shard, that shard is downloaded from the source cluster, e.g.

```
│   └── awflAUIvQr2QPOA1lEKvDg
│       ├── 2
│       │   ├── __3W4tnXrgQraVDRpPMtODtw
│       │   ├── __4Vy6CzG2SOiLTGrotZKdCQ
│       │   ├── __JGKggveFSwuEUV7L9NxXxw
│       │   ├── __cL1vrRtqRri0X9fWQuJngg
│       │   ├── __fVENodAeRh6Lky2SHDJDoA
│       │   ├── __kfqKHmMHR5enj7J1CxrMXg
│       │   ├── __px6tCEWaRkG5tgw7Akl8Ww
│       │   ├── __wkJg68EqTuOU_i7LQhypqg
│       │   ├── index-0
│       │   └── snap-_SZcZ7U1Q6S1FDh-9CE44w.dat
```

it is unpacked into the target filesystem using the lucene library, this gives a directory tree that looks like this:

```
.
└── blog_2023  <------------ [1] 
    └── 2  <---------------- [2] 
        ├── _0.cfe  <------- [3] 
        ├── _0.cfs  <------- [4] 
        ├── _0.si   <------- [5] 
        ├── _0_1.liv <------ [6] 
        ├── _1.cfe
        ├── _1.cfs
        ├── _1.si
        └── segments_3 <---- [7] 
```
1. **Index Name**: The index name
2. **Shard Number**: The numeric identifier of the shard within the index
3. **Segment Entries File**: A "Compound File" which stores the file names, data offsets. and lengths for the Segment Data File
4. **Segment Data File**: A "Compound File" which stores the actual data for the individual virtual files, including the doc data
5. **Segment Info**: Stores metadata about a segment
6. **Live Documents**: Info about what documents are live
7. **Segments File**: Stores information about a commit point

[Docs on Lucene file formats](https://lucene.apache.org/core/10_1_0/core/org/apache/lucene/codecs/lucene101/package-summary.html#file-names)

### Lucene Index parsing

From here, we use the Lucene reader to open the shard, which constructs a `SegmentInfos` object out of the data in `segments_N` file (Segments File). This is the information as follows:

```
segments_3:
  _0(7.7.3):c3/1:
    diagnostics:
      - java.version: 15.0.1
      - java.vm.version: 15.0.1 9
      - lucene.version: 7.7.3
      - source: flush
      - os.arch: amd64
      - java.runtime.version: 15.0.1 9
      - os.version: 6.10.14-linuxkit
      - java.vendor: AdoptOpenJDK
      - os: Linux
      - timestamp: 1737044468413
    attributes:
      - Lucene50StoredFieldsFormat.mode: BEST_SPEED
    delGen: 1
  
  _1(7.7.3):c2:
    diagnostics:
      - java.version: 15.0.1
      - java.vm.version: 15.0.1 9
      - lucene.version: 7.7.3
      - source: flush
      - os.arch: amd64
      - java.runtime.version: 15.0.1 9
      - os.version: 6.10.14-linuxkit
      - java.vendor: AdoptOpenJDK
      - os: Linux
      - timestamp: 1737044538784
    attributes:
      - Lucene50StoredFieldsFormat.mode: BEST_SPEED
```

This gives lucene the context of the lucene version each segment was written as, along with other attributes as shown which help lucene read the segments.

Next, Lucene takes this information and reads the segments from each given name ("_0", "_1", etc) and puts them each into a `SegmentInfo` object which is then added to the top level `SegmentInfos` object.

This is done by reading the Segment Entries File, which gives information like the following which is used to read the Segment Data File:
```
entries:
    "_Lucene50_0.doc" : { offset: 46, length: 128 }
    "_Lucene50_0.tim" : { offset: 174, length: 436 }
    ...
```

This constructs `SegmentReaders` for each segment by extracting entries from the `.cfs` file with the appropriate offsets and lengths for individual components. We maintain an array of segments in the `SegmentInfos` object. To ensure a consistent order each time we read the documents, we sort by `segmentName` (e.g., `_0`, `_1`, `_2`, etc.). This consistency is crucial for enabling the functionality to resume migration of a shard from a previous checkpoint.

Regarding deleted documents, the `.liv` file is read to construct a `FixedBits` object for each segment that contains deleted documents. Lucene uses a bit array to represent deleted documents, employing bit masking for efficient retrieval.

When iterating through the documents, we use the `FixedBits` object to skip over deleted documents, ensuring that only non-deleted documents are ingested.

This process is feasible because each segment's `.cfe`/`.cfs` file is immutable, guaranteeing a consistent order and value of the documents within the segment, which are numbered from 0 to N-1. where N is the number of docs in the segment.

Based on the order of the SegmentReaders in the DirectoryReader, lucene creates the segmentBaseDocId for each segment, this is the sum of the number of docs in all previous segments.
We use this to determine a shard-global docId for each doc, which is what we use in the startingDocId when we checkpoint progress within the shard.

This allows us to limit the number of times we need to ingest each doc when a shard takes multiple rfs processes to complete.
