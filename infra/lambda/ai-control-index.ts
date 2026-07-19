import type { SNSEvent } from 'aws-lambda';
import { createAiControlHandler, ControlChange } from './ai-control';

const tableName = process.env.CONTROL_TABLE_NAME;
if (!tableName) throw new Error('CONTROL_TABLE_NAME is required');
const handler = createAiControlHandler(tableName);
export async function main(event: SNSEvent | ControlChange) { await handler(event); }
