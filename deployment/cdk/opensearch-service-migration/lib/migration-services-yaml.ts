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

export class S3SnapshotYaml {
    snapshot_name: string = '';
    s3_repo_uri: string = '';
    s3_region: string = '';

    toDict() {
        return {
            snapshot_name: this.snapshot_name,
            s3_repo_uri: this.s3_repo_uri,
            s3_region: this.s3_region
        };
    }
}

export class ServicesYaml {
    source_cluster: ClusterYaml;
    target_cluster: ClusterYaml;
    metrics_source: MetricsSourceYaml = new MetricsSourceYaml();
    backfill: RFSBackfillYaml | OSIBackfillYaml;
    snapshot?: S3SnapshotYaml;

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


