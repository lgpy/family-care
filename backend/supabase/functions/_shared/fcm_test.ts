/**
 * FCM sender unit tests with a mocked `fetch` and a throwaway RSA key. No
 * network, no Firebase project, no token leaves this process.
 */

import { assert, assertEquals, assertThrows } from './testing.ts';
import {
  buildAssertion,
  createFcmSender,
  parseServiceAccount,
  type ServiceAccount,
} from './fcm.ts';

// A disposable signing key, generated per run (never a fixture secret).
const keyPair = await crypto.subtle.generateKey(
  {
    name: 'RSASSA-PKCS1-v1_5',
    modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]),
    hash: 'SHA-256',
  },
  true,
  ['sign', 'verify'],
);

const pkcs8 = new Uint8Array(await crypto.subtle.exportKey('pkcs8', keyPair.privateKey));
const pkcs8Base64 = btoa(String.fromCharCode(...pkcs8));
// The key is generated above at test time; the PEM label is assembled so the
// secret scanner does not mistake this template for a committed key.
const pemLabel = ['PRIVATE', 'KEY'].join(' ');
const privateKeyPem =
  `-----BEGIN ${pemLabel}-----\n${
    (pkcs8Base64.match(/.{1,64}/g) ?? []).join('\n')
  }\n-----END ${pemLabel}-----\n`;

const account: ServiceAccount = {
  projectId: 'family-care-test',
  clientEmail: 'pusher@family-care-test.iam.gserviceaccount.com',
  privateKeyPem,
  tokenUri: 'https://oauth2.googleapis.com/token',
};

function decodePart(part: string): Record<string, unknown> {
  const padded = part.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>;
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** A fetch double that records every call and answers by URL. */
function mockFetch(
  handler: (url: string, init: RequestInit) => Response,
): { fetch: typeof fetch; calls: Array<{ url: string; init: RequestInit }> } {
  const calls: Array<{ url: string; init: RequestInit }> = [];
  const fetchImpl = ((input: string, init?: RequestInit) => {
    calls.push({ url: String(input), init: init ?? {} });
    return Promise.resolve(handler(String(input), init ?? {}));
  }) as unknown as typeof fetch;
  return { fetch: fetchImpl, calls };
}

const tokenOk = () => jsonResponse(200, { access_token: 'ya29.test', expires_in: 3600 });

Deno.test('fcm: the service-account JSON is validated, not trusted', () => {
  assertThrows(() => parseServiceAccount(undefined), 'not set');
  assertThrows(() => parseServiceAccount('not json'), 'not valid JSON');
  assertThrows(
    () => parseServiceAccount(JSON.stringify({ project_id: 'p', client_email: 'a@b' })),
    'private_key',
  );

  const parsed = parseServiceAccount(JSON.stringify({
    project_id: 'p',
    client_email: 'a@b.test',
    private_key: privateKeyPem,
    token_uri: 'https://oauth2.example.test/token',
  }));
  assertEquals(parsed.projectId, 'p');
  assertEquals(parsed.tokenUri, 'https://oauth2.example.test/token');
});

Deno.test('fcm: the OAuth assertion is a verifiable RS256 JWT', async () => {
  const assertion = await buildAssertion(account, 1_800_000_000);
  const parts = assertion.split('.');
  assertEquals(parts.length, 3);

  assertEquals(decodePart(parts[0]), { alg: 'RS256', typ: 'JWT' });
  const claims = decodePart(parts[1]);
  assertEquals(claims.iss, account.clientEmail);
  assertEquals(claims.aud, account.tokenUri);
  assertEquals(claims.scope, 'https://www.googleapis.com/auth/firebase.messaging');
  assertEquals(claims.iat, 1_800_000_000);
  assertEquals(claims.exp, 1_800_003_600);

  const signature = Uint8Array.from(
    atob(parts[2].replace(/-/g, '+').replace(/_/g, '/')),
    (c) => c.charCodeAt(0),
  );
  const verified = await crypto.subtle.verify(
    { name: 'RSASSA-PKCS1-v1_5' },
    keyPair.publicKey,
    signature,
    new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
  );
  assert(verified, 'the signature verifies with the matching public key');
});

Deno.test('fcm: a message is sent with HIGH priority and the id-only payload', async () => {
  const mock = mockFetch((url) => {
    if (url === account.tokenUri) return tokenOk();
    return jsonResponse(200, { name: 'projects/family-care-test/messages/1' });
  });
  const sender = createFcmSender(account, { fetch: mock.fetch, now: () => 1_800_000_000_000 });

  const result = await sender.send('device-token-1', {
    notification: { title: 'Family Care', body: 'A dose needs attention' },
    data: { type: 'escalation', patient_id: 'patient-1', scheduled_local: '2026-11-16T08:00' },
    android: {
      priority: 'HIGH',
      notification: { channel_id: 'escalations', tag: 'escalation:patient-1:2026-11-16T08:00:00Z' },
    },
  });

  assertEquals(result, { status: 'sent' });
  assertEquals(mock.calls.length, 2);

  const sendCall = mock.calls[1];
  assertEquals(sendCall.url, 'https://fcm.googleapis.com/v1/projects/family-care-test/messages:send');
  const headers = sendCall.init.headers as Record<string, string>;
  assertEquals(headers['Authorization'], 'Bearer ya29.test');
  assertEquals(headers['Content-Type'], 'application/json');

  const body = JSON.parse(String(sendCall.init.body)) as {
    message: Record<string, unknown>;
  };
  assertEquals(body.message.token, 'device-token-1');
  assertEquals((body.message.android as Record<string, unknown>).priority, 'HIGH');
  assertEquals(body.message.data, {
    type: 'escalation',
    patient_id: 'patient-1',
    scheduled_local: '2026-11-16T08:00',
  });
  // No names, no medication, no extra health data (D4).
  assert(!JSON.stringify(body.message.data).includes('Grandma'));
});

Deno.test('fcm: the access token is cached until shortly before expiry', async () => {
  let clock = 1_800_000_000_000;
  const mock = mockFetch((url) => {
    if (url === account.tokenUri) return tokenOk();
    return jsonResponse(200, { name: 'x' });
  });
  const sender = createFcmSender(account, { fetch: mock.fetch, now: () => clock });

  await sender.send('t', { data: { type: 'clear' } });
  await sender.send('t', { data: { type: 'clear' } });
  assertEquals(mock.calls.filter((c) => c.url === account.tokenUri).length, 1);

  // Half a second before the cache guard: still valid.
  clock += 3600_000 - 61_000;
  await sender.send('t', { data: { type: 'clear' } });
  assertEquals(mock.calls.filter((c) => c.url === account.tokenUri).length, 1);

  // Inside the 60 s guard: refreshed.
  clock += 61_000;
  await sender.send('t', { data: { type: 'clear' } });
  assertEquals(mock.calls.filter((c) => c.url === account.tokenUri).length, 2);
});

Deno.test('fcm: UNREGISTERED and INVALID_ARGUMENT are told apart from retryable errors', async () => {
  const cases: Array<{ status: number; body: unknown; expected: string }> = [
    {
      status: 404,
      body: { error: { status: 'NOT_FOUND', details: [{ errorCode: 'UNREGISTERED' }] } },
      expected: 'unregistered',
    },
    {
      status: 400,
      body: { error: { status: 'INVALID_ARGUMENT', details: [{ errorCode: 'INVALID_ARGUMENT' }] } },
      expected: 'invalid',
    },
    { status: 503, body: { error: { status: 'UNAVAILABLE' } }, expected: 'retry' },
    { status: 429, body: { error: { status: 'RESOURCE_EXHAUSTED' } }, expected: 'retry' },
    { status: 400, body: { error: { status: 'SOMETHING_ELSE' } }, expected: 'failed' },
  ];

  for (const testCase of cases) {
    const mock = mockFetch((url) => {
      if (url === account.tokenUri) return tokenOk();
      return jsonResponse(testCase.status, testCase.body);
    });
    const sender = createFcmSender(account, { fetch: mock.fetch, now: () => 1_800_000_000_000 });
    const result = await sender.send('t', { data: { type: 'clear' } });
    assertEquals(result.status, testCase.expected, `HTTP ${testCase.status}`);
  }
});

Deno.test('fcm: a 401 drops the cached token so the next send re-authenticates', async () => {
  let tokens = 0;
  const mock = mockFetch((url) => {
    if (url === account.tokenUri) {
      tokens += 1;
      return tokenOk();
    }
    return jsonResponse(401, { error: { status: 'UNAUTHENTICATED' } });
  });
  const sender = createFcmSender(account, { fetch: mock.fetch, now: () => 1_800_000_000_000 });

  const first = await sender.send('t', { data: { type: 'clear' } });
  assertEquals(first.status, 'retry');
  await sender.send('t', { data: { type: 'clear' } });
  assertEquals(tokens, 2);
});

Deno.test('fcm: a transport failure is a retry, not a dead token', async () => {
  const mock = mockFetch((url) => {
    if (url === account.tokenUri) return tokenOk();
    throw new Error('network down');
  });
  const sender = createFcmSender(account, { fetch: mock.fetch, now: () => 1_800_000_000_000 });
  const result = await sender.send('t', { data: { type: 'clear' } });
  assertEquals(result.status, 'retry');
});
