import { ClusterYaml, RFSBackfillYaml, ServicesYaml } from "../lib/migration-services-yaml"

test('Test default servicesYaml can be stringified', () => {
    const servicesYaml = new ServicesYaml();
    expect(servicesYaml.metrics_source).toBeDefined();
    expect(Object.keys(servicesYaml.metrics_source)).toContain("cloudwatch");
    const yaml = servicesYaml.stringify();
    expect(yaml).toBe("metrics_source:\n  cloudwatch:\n");
})

test('Test servicesYaml with target cluster can be stringified', () => {
    let servicesYaml = new ServicesYaml();
    const cluster: ClusterYaml = { 'endpoint': 'https://abc.com', 'no_auth': '' };
    servicesYaml.target_cluster = cluster;

    expect(servicesYaml.target_cluster).toBeDefined();
    const yaml = servicesYaml.stringify();
    expect(yaml).toBe(`target_cluster:\n  endpoint: ${cluster.endpoint}\n  no_auth: ""\nmetrics_source:\n  cloudwatch:\n`);
})

test('Test servicesYaml with source and target cluster can be stringified', () => {
    let servicesYaml = new ServicesYaml();
    const targetCluster: ClusterYaml = { 'endpoint': 'https://abc.com', 'no_auth': '' };
    servicesYaml.target_cluster = targetCluster;
    const sourceClusterUser = "abc";
    const sourceClusterPassword = "XXXXX";
    const sourceCluster: ClusterYaml = { 'endpoint': 'https://xyz.com:9200', 'basic_auth': { user: sourceClusterUser, password: sourceClusterPassword } };
    servicesYaml.source_cluster = sourceCluster;

    expect(servicesYaml.target_cluster).toBeDefined();
    expect(servicesYaml.source_cluster).toBeDefined();
    const yaml = servicesYaml.stringify();
    const sourceClusterYaml = `source_cluster:\n  endpoint: ${sourceCluster.endpoint}\n  basic_auth:\n    user: ${sourceClusterUser}\n    password: ${sourceClusterPassword}\n`
    expect(yaml).toBe(`${sourceClusterYaml}target_cluster:\n  endpoint: ${targetCluster.endpoint}\n  no_auth: ""\nmetrics_source:\n  cloudwatch:\n`);
})

test('Test servicesYaml with rfs backfill can be stringified', () => {
    const clusterName = "migration-cluster-name";
    const serviceName = "rfs-service-name";
    const region = "us-east-1"
    let servicesYaml = new ServicesYaml();
    let rfsBackfillYaml = new RFSBackfillYaml();
    rfsBackfillYaml.ecs.cluster_name = clusterName;
    rfsBackfillYaml.ecs.service_name = serviceName;
    rfsBackfillYaml.ecs.aws_region = region;
    servicesYaml.backfill = rfsBackfillYaml;


    expect(servicesYaml.backfill).toBeDefined();
    expect(servicesYaml.backfill).toBeDefined();
    expect(servicesYaml.backfill instanceof RFSBackfillYaml).toBeTruthy();
    const yaml = servicesYaml.stringify();
    expect(yaml).toBe(`metrics_source:\n  cloudwatch:\nbackfill:\n  reindex_from_snapshot:\n    ecs:\n      cluster_name: ${clusterName}\n      service_name: ${serviceName}\n      aws_region: ${region}\n`);
})

test('Test servicesYaml without backfill does not include backend section', () => {
    let servicesYaml = new ServicesYaml();
    const yaml = servicesYaml.stringify();
    expect(yaml).toBe(`metrics_source:\n  cloudwatch:\n`);
})