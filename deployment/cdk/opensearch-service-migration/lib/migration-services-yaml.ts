import { ClusterAuth } from './common-utilities';
import * as yaml from 'yaml';

export class ClusterYaml {
    endpoint = '';
    version?: string;
    auth: ClusterAuth;

    constructor({endpoint, auth, version} : {endpoint: string, auth: ClusterAuth, version?: string}) {
        this.endpoint = endpoint;
        this.auth = auth;
        this.version = version;
    }
    toDict() {
        return {
            endpoint: this.endpoint,
            version: this.version,
            ...this.auth.toDict(),
            // TODO: figure out how version should be incorporated
            // https://opensearch.atlassian.net/browse/MIGRATIONS-1951
            // version: this.version?.version
        };
    }
}

export class MetricsSourceYaml {
    cloudwatch?: {
        aws_region?: string
        qualifier: string;
    } | null = null;
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
    scale = 5;
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

export class ECSReplayerYaml {
    ecs: ECSService;
    scale = 1;

    constructor() {
        this.ecs = new ECSService();
    }

    toDict() {
        return {
            ecs: this.ecs,
            scale: this.scale
        };
    }
}

export class FileSystemSnapshotYaml {
    repo_path = '';
}

export class S3SnapshotYaml {
    repo_uri = '';
    aws_region = '';
    role? = '';
}

export class SnapshotYaml {
    snapshot_name = '';
    otel_endpoint = '';
    snapshot_repo_name = '';
    s3?: S3SnapshotYaml;
    fs?: FileSystemSnapshotYaml;

    toDict() {
        return {
            snapshot_name: this.snapshot_name,
            otel_endpoint: this.otel_endpoint,
            snapshot_repo_name: this.snapshot_repo_name,
            // This conditinally includes the s3 and fs parameters if they're defined,
            // but does not add the keys otherwise
            ...(this.s3 && { s3: this.s3 }),
            ...(this.fs && { fs: this.fs })
        };
    }
}

// This component can be much more complicated (specified snapshot details, index/component/template allowlists, etc.)
// but for the time being, we are assuming that the snapshot is the one specified in SnapshotYaml.
export class MetadataMigrationYaml {
    from_snapshot = null;
    cluster_awareness_attributes = 1;
    otel_endpoint = '';
    source_cluster_version?: string;
}
export class KafkaYaml {
    broker_endpoints = '';
    msk?: string | null;
    standard?: string | null;
}

export class ClientOptions {
    user_agent_extra?: string;
}

export class ServicesYaml {
    source_cluster?: ClusterYaml;
    target_cluster: ClusterYaml;
    metrics_source: MetricsSourceYaml = new MetricsSourceYaml();
    backfill: RFSBackfillYaml | OSIBackfillYaml;
    snapshot?: SnapshotYaml;
    metadata_migration?: MetadataMigrationYaml;
    replayer?: ECSReplayerYaml;
    kafka?: KafkaYaml;
    client_options?: ClientOptions;

    stringify(): string {
        return yaml.stringify({
            source_cluster: this.source_cluster?.toDict(),
            target_cluster: this.target_cluster?.toDict(),
            metrics_source: this.metrics_source,
            backfill: this.backfill?.toDict(),
            snapshot: this.snapshot?.toDict(),
            metadata_migration: this.metadata_migration,
            replay: this.replayer?.toDict(),
            kafka: this.kafka,
            client_options: this.client_options
        },
        {
            'nullStr': ''
        })
    }
}


