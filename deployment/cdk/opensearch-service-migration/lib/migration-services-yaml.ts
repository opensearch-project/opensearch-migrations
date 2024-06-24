import * as yaml from 'yaml';

export class ClusterYaml {
    endpoint: string = '';
    no_auth?: string | null;
    basic_auth?: object | null;
}

export class MetricsSourceYaml {
    cloudwatch? : object | null = null;
}

export class ECSService {
    cluster_name: string | undefined;
    service_name: string | undefined;
    aws_region: string | undefined;
    constructor() {
        this.cluster_name = undefined;
        this.service_name = undefined;
        this.aws_region = undefined;
    }
}

export class RFSBackfillYaml {
    ecs: ECSService;
    constructor() {
        this.ecs = new ECSService();
    }

    toDict() {
        return {
            reindex_from_snapshot: {ecs: this.ecs}
        };
    }
}

export class OSIBackfillYaml {
    toDict() {
        return {
            opensearch_ingestion: null
        };
    }
}

export class FileSystemSnapshotYaml {
    repo_path: string = '';
}

export class S3SnapshotYaml {
    repo_uri: string = '';
    aws_region: string = '';
}

export class SnapshotYaml {
    snapshot_name: string = '';
    s3?: S3SnapshotYaml;
    fs?: FileSystemSnapshotYaml;

    toDict() {
        return {
            snapshot_name: this.snapshot_name,
            // This conditinally includes the s3 and fs parameters if they're defined,
            // but does not add the keys otherwise
            ...(this.s3 && { s3: this.s3 }),
            ...(this.fs && { fs: this.fs })
        };
    }
}

export class ServicesYaml {
    source_cluster: ClusterYaml;
    target_cluster: ClusterYaml;
    metrics_source: MetricsSourceYaml = new MetricsSourceYaml();
    backfill: RFSBackfillYaml | OSIBackfillYaml;
    snapshot?: SnapshotYaml;

    stringify(): string {
        return yaml.stringify({
            source_cluster: this.source_cluster,
            target_cluster: this.target_cluster,
            metrics_source: this.metrics_source,
            backfill: this.backfill?.toDict(),
            snapshot: this.snapshot?.toDict()
        },
        {
            'nullStr': ''
        })
    }
}


