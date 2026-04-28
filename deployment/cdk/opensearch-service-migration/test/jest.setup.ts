
jest.mock('node:child_process', () => {
  const child_process = jest.requireActual('node:child_process');

  // Define the list of expected Docker images as per CI.yml
  const expectedDockerImages = [
    'mirror.gcr.io/apache/kafka:3.9.1',
    'migrations/capture_proxy_es:latest',
    'migrations/capture_proxy:latest',
    'migrations/elasticsearch_searchguard:latest',
    'migrations/migration_console:latest',
    'migrations/reindex_from_snapshot:latest',
    'migrations/traffic_replayer:latest',
    'migrations/otel_collector:latest',
    'opensearchproject/opensearch:2',
  ];

  const handleDockerCall = (subcommand: string, image: string) => {
    if (!expectedDockerImages.includes(image)) {
      throw new Error(`Unsupported image: ${image}`);
    }
    if (subcommand === 'inspect') {
      return Buffer.from('sha256:a6cdbbcf16c510f8e24c6b993');
    }
    if (subcommand === 'pull') {
      return Buffer.from('');
    }
    throw new Error(`Unsupported docker subcommand: ${subcommand}`);
  };

  return {
    execFileSync: jest.fn().mockImplementation((file: string, args: readonly string[] = []) => {
      if (file !== 'docker') {
        throw new Error(`Command not supported: ${file} ${args.join(' ')}`);
      }
      // docker inspect --format=... <image>  or  docker pull <image>
      const subcommand = args[0];
      const image = args[args.length - 1];
      return handleDockerCall(subcommand, image);
    }),
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
    // Uncomment and replace above to use the actual implementations, requires ./buildDockerImages.sh to be run locally
    // execFileSync: child_process.execFileSync,
    // execSync: child_process.execSync,
    spawnSync: child_process.spawnSync,
  };
});
