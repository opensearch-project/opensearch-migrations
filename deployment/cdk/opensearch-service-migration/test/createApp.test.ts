import { App, Tags } from 'aws-cdk-lib';
import { createApp } from '../bin/createApp';
import { StackComposer } from '../lib/stack-composer';

jest.mock('node:fs', () => ({
  readFileSync: jest.fn().mockReturnValue('1.0.0\n'),
}));

jest.mock('aws-cdk-lib', () => ({
  App: jest.fn().mockImplementation(() => ({
    node: {
      tryGetContext: jest.fn(),
    },
  })),
  Tags: {
    of: jest.fn().mockReturnValue({
      add: jest.fn(),
    }),
  },
  Stack: jest.fn().mockImplementation(),
}));

jest.mock('../lib/stack-composer');

describe('createApp', () => {
  const originalEnv = process.env;

  beforeEach(() => {
    jest.clearAllMocks();
    process.env = { ...originalEnv };
  });

  afterAll(() => {
    process.env = originalEnv;
  });

  it('should create an App instance with correct configuration', () => {
    // Set up environment variables
    process.env.CDK_DEFAULT_ACCOUNT = 'test-account';
    process.env.CDK_DEFAULT_REGION = 'test-region';
    process.env.MIGRATIONS_APP_REGISTRY_ARN = 'test-arn';
    process.env.MIGRATIONS_USER_AGENT = 'test-user-agent';

    const consoleSpy = jest.spyOn(console, 'info').mockImplementation();
    const mockAddTag = jest.fn();
    Tags.of = jest.fn().mockReturnValue({ add: mockAddTag });

    const app = createApp();

    // Verify App creation
    expect(App).toHaveBeenCalled();

    // Verify tag addition
    expect(mockAddTag).toHaveBeenCalledWith('migration_deployment', '1.0.0');

    // Verify StackComposer creation
    expect(StackComposer).toHaveBeenCalledWith(
      expect.any(Object),
      {
        migrationsAppRegistryARN: 'test-arn',
        migrationsUserAgent: 'test-user-agent',
        migrationsSolutionVersion: '1.0.0',
        env: { account: 'test-account', region: 'test-region' },
      }
    );

    // Verify console log
    expect(consoleSpy).toHaveBeenCalledWith(
      expect.stringContaining('App Registry mode is enabled for CFN stack tracking')
    );

    // Verify app is returned
    expect(app).toBeDefined();

    consoleSpy.mockRestore();
  });
});
