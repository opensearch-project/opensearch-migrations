import { App, Aspects } from 'aws-cdk-lib';
import { createApp } from '../bin/createApp';
import { RemoveResourceTags } from '../lib/remove-resource-tags';
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
  Aspects: {
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
    process.env.MIGRATIONS_USER_AGENT = 'test-user-agent';

    const mockAddAspect = jest.fn();
    Aspects.of = jest.fn().mockReturnValue({ add: mockAddAspect });

    const app = createApp();

    // Verify App creation
    expect(App).toHaveBeenCalled();

    // Verify the tag removal aspect is applied, after the tagging aspects
    expect(mockAddAspect).toHaveBeenCalledWith(
      expect.any(RemoveResourceTags),
      { priority: RemoveResourceTags.PRIORITY }
    );

    // Verify StackComposer creation
    expect(StackComposer).toHaveBeenCalledWith(
      expect.any(Object),
      {
        migrationsUserAgent: 'test-user-agent',
        migrationsSolutionVersion: '1.0.0',
        env: { account: 'test-account', region: 'test-region' },
      }
    );

    // Verify app is returned
    expect(app).toBeDefined();
  });
});
