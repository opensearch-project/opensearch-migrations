
jest.mock('node:child_process', () => {
  const child_process = jest.requireActual('node:child_process');

  // Define the list of expected Docker images as per CI.yml
  const expectedDockerImages = [
    'docker.io/apache/kafka:3.9.1',
    'migrations/capture_proxy_es:latest',
    'migrations/capture_proxy:latest',
    'migrations/elasticsearch_searchguard:latest',
    'migrations/migration_console:latest',
    'migrations/reindex_from_snapshot:latest',
    'migrations/traffic_replayer:latest',
    'migrations/otel_collector:latest',
    'opensearchproject/opensearch:2',
  ];

  return {
    execSync: jest.fn().mockImplementation((command: string) => {
      if (expectedDockerImages.some(expectedImage => command.endsWith(expectedImage))) {
        if (command.startsWith('docker inspect')) {
          return Buffer.from("sha256:a6cdbbcf16c510f8e24c6b993");
        }
        if (command.startsWith('docker pull')) {
          return Buffer.from("");
        }
      }
      throw new Error(`Command not supported: ${command}`);
    }),
    // Uncomment and replace above to use the actual execSync, requires ./buildDockerImages.sh to be run locally
    // execSync: child_process.execSync,
    spawnSync: child_process.spawnSync,
  };
});