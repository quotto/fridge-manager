#!/usr/bin/env node
import { App } from 'aws-cdk-lib';
import { getEnvironmentConfig, supportedEnvironments } from '../lib/environment-config';
import { FoundationStack } from '../lib/foundation-stack';

const app = new App();

for (const environment of supportedEnvironments) {
  const config = getEnvironmentConfig(environment);
  const account = process.env[config.accountEnvVar];

  new FoundationStack(app, config.stackName, {
    config,
    env: account ? { account, region: config.region } : { region: config.region },
    description: `fridge-manager ${environment} foundation resources`,
  });
}

app.synth();
