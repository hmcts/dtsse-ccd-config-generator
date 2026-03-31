// Exercises generated CCD TypeScript bindings against a live CCD stack.
import Axios, { AxiosInstance } from 'axios';
import { createCcdClient, type CcdClientConfig, type CcdTransport } from '@hmcts/ccd-event-runtime';

import { caseBindings } from './generated/ccd/E2E/event-contracts';
import type { CaseworkerAddNoteDto } from './generated/ccd/E2E/dto-types';

function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

function createTransport(api: AxiosInstance): CcdTransport {
  return {
    get: async (url, headers) => (await api.get(url, { headers })).data,
    post: async (url, data, headers) => (await api.post(url, data, { headers })).data,
  };
}

async function run(): Promise<void> {
  const baseUrl = requireEnv('CCD_BASE_URL');
  const callbackBaseUrl = requireEnv('CCD_CALLBACK_BASE_URL');
  const caseTypeId = requireEnv('CCD_CASE_TYPE_ID');
  const caseId = requireEnv('CCD_CASE_ID');
  const userToken = requireEnv('CCD_USER_TOKEN');
  const serviceToken = requireEnv('CCD_SERVICE_TOKEN');
  const noteText = requireEnv('CCD_NOTE');

  const api = Axios.create({ baseURL: baseUrl });

  const config: CcdClientConfig = {
    baseUrl,
    callbackBaseUrl,
    caseTypeId,
    getAuthHeaders: () => ({
      Authorization: userToken.startsWith('Bearer ') ? userToken : `Bearer ${userToken}`,
      ServiceAuthorization: serviceToken.startsWith('Bearer ') ? serviceToken : `Bearer ${serviceToken}`,
      experimental: 'experimental',
      'Content-Type': 'application/json',
      Accept: '*/*',
    }),
    transport: createTransport(api),
  };

  const client = createCcdClient(config, caseBindings);
  const flow = await client.event('caseworkerServiceAddNote').start(caseId);
  const startData: CaseworkerAddNoteDto = flow.data;

  if (!startData.note || !startData.note.startsWith('[start] set by ')) {
    throw new Error(`Expected start callback note pre-population, got: ${JSON.stringify(startData)}`);
  }

  startData.note = noteText;
  const submitResult = await flow.submit(startData) as { id?: string | number };
  if (!submitResult?.id) {
    throw new Error(`Expected submit response to contain case id, got: ${JSON.stringify(submitResult)}`);
  }

  console.log(JSON.stringify({ caseId: String(submitResult.id), note: noteText }));
}

void run();
