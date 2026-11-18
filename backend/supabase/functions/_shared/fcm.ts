/**
 * Firebase Cloud Messaging, HTTP v1 (D4: push is only used for coordination
 * alerts).
 *
 * The service-account JSON comes from the edge function environment
 * (`FCM_SERVICE_ACCOUNT_JSON`), never from the repository. This module signs
 * the OAuth assertion with WebCrypto (RS256) - no npm JWT library - exchanges
 * it for an access token and caches that token until shortly before it
 * expires.
 *
 * Two rules that are easy to get wrong and are pinned by tests:
 * - A token the service reports as UNREGISTERED / INVALID_ARGUMENT is dead:
 *   the caller must delete the `core.push_devices` row, not retry forever.
 * - Nothing here logs a token, a message body or a response body. A push
 *   carries no health data (D4); a device token is personal data
 *   (docs/security-and-privacy.md).
 */

export interface ServiceAccount {
  projectId: string;
  clientEmail: string;
  privateKeyPem: string;
  tokenUri: string;
}

/** The subset of an FCM message this project sends. */
export interface PushMessage {
  notification?: { title: string; body: string };
  /** Values must be strings; ids and wall-clock times only (D4). */
  data?: Record<string, string>;
  android?: {
    priority?: 'HIGH' | 'NORMAL';
    notification?: {
      channel_id?: string;
      /** Replaces an earlier notification with the same tag on the device. */
      tag?: string;
    };
    /** Message lifetime; FCM defaults to four weeks. */
    ttl?: string;
    collapse_key?: string;
  };
}

export type FcmResult =
  | { status: 'sent' }
  | { status: 'unregistered' }
  | { status: 'invalid' }
  | { status: 'retry'; reason: string }
  | { status: 'failed'; reason: string };

type FetchLike = (
  input: string,
  init?: RequestInit,
) => Promise<Response>;

export interface FcmDependencies {
  fetch: FetchLike;
  /** Injectable clock (epoch ms) for the token-cache tests. */
  now?: () => number;
}

const FCM_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const DEFAULT_TOKEN_URI = 'https://oauth2.googleapis.com/token';
const FCM_ENDPOINT = 'https://fcm.googleapis.com/v1/projects';

/** Parse and validate the service-account JSON from the environment. */
export function parseServiceAccount(json: string | undefined | null): ServiceAccount {
  if (!json || json.trim() === '') {
    throw new Error('FCM_SERVICE_ACCOUNT_JSON is not set');
  }

  let parsed: Record<string, unknown>;
  try {
    parsed = JSON.parse(json) as Record<string, unknown>;
  } catch {
    throw new Error('FCM_SERVICE_ACCOUNT_JSON is not valid JSON');
  }

  const projectId = parsed.project_id;
  const clientEmail = parsed.client_email;
  const privateKey = parsed.private_key;
  const tokenUri = parsed.token_uri ?? DEFAULT_TOKEN_URI;

  if (typeof projectId !== 'string' || projectId === '') {
    throw new Error('FCM service account has no project_id');
  }
  if (typeof clientEmail !== 'string' || clientEmail === '') {
    throw new Error('FCM service account has no client_email');
  }
  if (typeof privateKey !== 'string' || !privateKey.includes('PRIVATE KEY')) {
    throw new Error('FCM service account has no usable private_key');
  }

  return {
    projectId,
    clientEmail,
    privateKeyPem: privateKey,
    tokenUri: typeof tokenUri === 'string' ? tokenUri : DEFAULT_TOKEN_URI,
  };
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64UrlEncodeText(text: string): string {
  return base64UrlEncode(new TextEncoder().encode(text));
}

function pemToDer(pem: string): ArrayBuffer {
  const body = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, '')
    .replace(/-----END PRIVATE KEY-----/, '')
    .replace(/\s+/g, '');
  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  const buffer = new ArrayBuffer(bytes.length);
  new Uint8Array(buffer).set(bytes);
  return buffer;
}

export async function importSigningKey(privateKeyPem: string): Promise<CryptoKey> {
  return await crypto.subtle.importKey(
    'pkcs8',
    pemToDer(privateKeyPem),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
}

/** The RS256-signed OAuth assertion Google exchanges for an access token. */
export async function buildAssertion(
  account: ServiceAccount,
  issuedAtSeconds: number,
): Promise<string> {
  const header = { alg: 'RS256', typ: 'JWT' };
  const claims = {
    iss: account.clientEmail,
    scope: FCM_SCOPE,
    aud: account.tokenUri,
    iat: issuedAtSeconds,
    exp: issuedAtSeconds + 3600,
  };

  const signingInput = `${base64UrlEncodeText(JSON.stringify(header))}.${
    base64UrlEncodeText(JSON.stringify(claims))
  }`;
  const key = await importSigningKey(account.privateKeyPem);
  const signature = await crypto.subtle.sign(
    { name: 'RSASSA-PKCS1-v1_5' },
    key,
    new TextEncoder().encode(signingInput),
  );

  return `${signingInput}.${base64UrlEncode(new Uint8Array(signature))}`;
}

export interface FcmSender {
  /** Send one message to one device token. Never throws for an API error. */
  send(token: string, message: PushMessage): Promise<FcmResult>;
  /** Drop the cached access token (used after a 401). */
  invalidateAccessToken(): void;
}

export function createFcmSender(
  account: ServiceAccount,
  deps: FcmDependencies,
): FcmSender {
  const now = deps.now ?? (() => Date.now());
  let accessToken: string | null = null;
  let accessTokenExpiresAtMs = 0;

  function invalidateAccessToken(): void {
    accessToken = null;
    accessTokenExpiresAtMs = 0;
  }

  async function fetchAccessToken(): Promise<string> {
    const nowMs = now();
    if (accessToken !== null && nowMs < accessTokenExpiresAtMs - 60_000) {
      return accessToken;
    }

    const assertion = await buildAssertion(account, Math.floor(nowMs / 1000));
    const response = await deps.fetch(account.tokenUri, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion,
      }).toString(),
    });

    if (!response.ok) {
      throw new Error(`token exchange failed: HTTP ${response.status}`);
    }

    const payload = await response.json() as {
      access_token?: unknown;
      expires_in?: unknown;
    };
    if (typeof payload.access_token !== 'string' || payload.access_token === '') {
      throw new Error('token exchange returned no access_token');
    }

    const expiresInSeconds = typeof payload.expires_in === 'number'
      ? payload.expires_in
      : 3600;

    accessToken = payload.access_token;
    accessTokenExpiresAtMs = nowMs + expiresInSeconds * 1000;
    return accessToken;
  }

  async function send(token: string, message: PushMessage): Promise<FcmResult> {
    let bearer: string;
    try {
      bearer = await fetchAccessToken();
    } catch (error) {
      // Do not include the response body or the token in the message.
      return {
        status: 'retry',
        reason: error instanceof Error ? error.message : 'token exchange failed',
      };
    }

    let response: Response;
    try {
      response = await deps.fetch(
        `${FCM_ENDPOINT}/${encodeURIComponent(account.projectId)}/messages:send`,
        {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${bearer}`,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            message: {
              token,
              ...(message.notification ? { notification: message.notification } : {}),
              ...(message.data ? { data: message.data } : {}),
              ...(message.android ? { android: message.android } : {}),
            },
          }),
        },
      );
    } catch {
      // A transport failure is transient (phone/network/Google blip).
      return { status: 'retry', reason: 'network error' };
    }

    if (response.ok) return { status: 'sent' };

    if (response.status === 401 || response.status === 403) {
      invalidateAccessToken();
      return { status: 'retry', reason: `HTTP ${response.status}` };
    }

    const detail = await readErrorDetail(response);

    if (detail.errorCode === 'UNREGISTERED' || response.status === 404) {
      return { status: 'unregistered' };
    }
    if (
      detail.errorCode === 'INVALID_ARGUMENT' ||
      (response.status === 400 && detail.status === 'INVALID_ARGUMENT')
    ) {
      return { status: 'invalid' };
    }
    if (response.status === 429 || response.status >= 500) {
      return { status: 'retry', reason: `HTTP ${response.status}` };
    }

    return { status: 'failed', reason: `HTTP ${response.status}` };
  }

  return { send, invalidateAccessToken };
}

/** Only the error classification is read; nothing here is logged. */
async function readErrorDetail(
  response: Response,
): Promise<{ errorCode: string | null; status: string | null }> {
  try {
    const body = await response.json() as {
      error?: { status?: unknown; details?: Array<{ errorCode?: unknown }> };
    };
    const status = typeof body.error?.status === 'string' ? body.error.status : null;
    let errorCode: string | null = null;
    for (const detail of body.error?.details ?? []) {
      if (typeof detail.errorCode === 'string') {
        errorCode = detail.errorCode;
        break;
      }
    }
    return { errorCode, status };
  } catch {
    return { errorCode: null, status: null };
  }
}
