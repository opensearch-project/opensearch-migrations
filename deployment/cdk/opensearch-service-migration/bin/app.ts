#!/usr/bin/env node
import 'source-map-support/register';
import { createApp } from './createApp';

const app = createApp();
app.synth();
