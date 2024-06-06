import { ClusterYaml, ServicesYaml } from "../lib/migration-services-yaml"

test('Test default servicesYaml can be stringified', () => {
    const servicesYaml = new ServicesYaml();
    expect(servicesYaml.metrics_source).toBeDefined();
    expect(servicesYaml.metrics_source.type).toBe("cloudwatch");
    const yaml = servicesYaml.stringify();
    expect(yaml).toBe("metrics_source:\n  type: cloudwatch\n");
})

test('Test servicesYaml with cluster can be stringified', () => {
    let servicesYaml = new ServicesYaml();
    const cluster: ClusterYaml = { 'endpoint': 'https://abc.com', 'no_auth': '' };
    servicesYaml.target_cluster = cluster;

    expect(servicesYaml.target_cluster).toBeDefined();
    const yaml = servicesYaml.stringify();
    expect(yaml).toBe(`target_cluster:\n  endpoint: ${cluster.endpoint}\n  no_auth: ""\nmetrics_source:\n  type: cloudwatch\n`);
})

test('Test servicesYaml with cluster can be stringified', () => {
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
    expect(yaml).toBe(`${sourceClusterYaml}target_cluster:\n  endpoint: ${targetCluster.endpoint}\n  no_auth: ""\nmetrics_source:\n  type: cloudwatch\n`);
})
