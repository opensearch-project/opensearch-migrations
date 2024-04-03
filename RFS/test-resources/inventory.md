## Test Resources

This directory tree contains a number of different resources for use in testing of RFS.  A breakdown is as follows.

### Snapshots

#### ES_6_8_Single
An Elasticsearch 6.8 snapshot repo containing a single snapshot, `global_state_snapshot`.  Contains two indices (`posts_2023_02_25`, `posts_2024_01_01`), each with few documents in them.  Contains a template, `posts_index_template`.

#### ES_7_10_Single
An Elasticsearch 7.10 snapshot repo containing a single snapshot (`global_state_snapshot`).  Contains two indices (`posts_2023_02_25`, `posts_2024_01_01`), each with few documents in them.  Contains an index template, `posts_index_template`, and a composite template, `posts_template`.

#### ES_7_10_Double
An Elasticsearch 7.10 snapshot repo containing a two snapshots (`global_state_snapshot`, `global_state_snapshot_2`).  `global_state_snapshot` contains two indices (`posts_2023_02_25`, `posts_2024_01_01`), each with few documents in them, an index template, `posts_index_template`, and a composite template, `posts_template`.  `global_state_snapshot_2` is the same, except it doesn't have the index `posts_2023_02_25` in it.

