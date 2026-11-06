// k6 load test for mini-wallet. Pick a scenario with SCENARIO=spread|hot|topup (see load-test/README.md).
//
//   spread - random transfers between many wallets: the "normal" case, little lock contention
//   hot    - every transfer involves ONE wallet: the worst case for row locking
//   topup  - every request is a top-up: each one locks one of the 16 system cash-in wallets
//
// The API needs a login and only lets you spend from wallets you own. So setup registers USERS users, logs each in
// (one token each), and gives each user their own wallets. Every virtual user acts as one of those users.
//
// A warm-up phase runs first (JIT, connection pool, caches) and is excluded from the numbers: thresholds and the
// summary lines tagged {scenario:measured} are the ones to read.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'spread';
const VUS = Number(__ENV.VUS || 32);
const WARMUP_S = Number(__ENV.WARMUP_SECONDS || 15);
const DURATION_S = Number(__ENV.DURATION_SECONDS || 60);
const WALLETS = Number(__ENV.WALLETS || 200);   // in total, shared out evenly between the users
const USERS = Number(__ENV.USERS || 20);
const REPLAY_PERCENT = Number(__ENV.REPLAY_PERCENT || 5); // share of requests that retry the previous one
const FUNDING = 1000000;        // every wallet starts with this
const HOT_FUNDING = 1000000000; // the hot wallet can pay out for the whole test
const PASSWORD = 'load-test-password-1';

// 200/201 = applied or replayed, 422 = business refusal (e.g. insufficient funds). Anything else is a failure,
// including 429: the rate limit must be raised for a load test (see load-test/README.md), otherwise it throttles us.
http.setResponseCallback(http.expectedStatuses(200, 201, 422));

const applied = new Counter('wallet_applied');       // 201: a new transaction
const replayed = new Counter('wallet_replayed');     // 200: a retry answered from the stored result
const refused = new Counter('wallet_refused');       // 422
const rateLimited = new Counter('wallet_rate_limited'); // 429: the test is being throttled - numbers are not valid
const unexpected = new Counter('wallet_unexpected'); // anything else (5xx, timeouts...) - must stay 0

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-vus', vus: VUS, duration: `${WARMUP_S}s`, exec: 'run', gracefulStop: '5s',
    },
    measured: {
      executor: 'constant-vus', vus: VUS, duration: `${DURATION_S}s`, startTime: `${WARMUP_S + 5}s`, exec: 'run',
      gracefulStop: '10s',
    },
  },
  thresholds: {
    // Loose limits: they exist to make k6 print the measured-only numbers, and to fail the run on real errors.
    'http_req_duration{scenario:measured}': ['p(95)<30000'],
    'http_req_failed{scenario:measured}': ['rate<0.001'],
    'wallet_unexpected{scenario:measured}': ['count==0'],
    'wallet_rate_limited{scenario:measured}': ['count==0'],
    // These never fail; they make k6 report request and outcome counts for the measured phase only.
    'http_reqs{scenario:measured}': ['count>=0'],
    'wallet_applied{scenario:measured}': ['count>=0'],
    'wallet_replayed{scenario:measured}': ['count>=0'],
    'wallet_refused{scenario:measured}': ['count>=0'],
  },
};

function post(path, body, token, key, name) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (key) headers['Idempotency-Key'] = key;
  return http.post(`${BASE}${path}`, body === null ? null : JSON.stringify(body), {
    headers,
    tags: { name }, // one label per endpoint instead of one per wallet id
  });
}

function waitForApp() {
  for (let i = 0; i < 60; i++) {
    const res = http.get(`${BASE}/actuator/health`);
    if (res.status === 200) return;
    sleep(1);
  }
  throw new Error(`app not reachable at ${BASE}`);
}

// Registers a user and logs in. Returns the access token (valid 15 minutes: longer than any run).
function registerAndLogin(username) {
  const credentials = { username, password: PASSWORD };
  const registered = post('/api/v1/auth/register', credentials, null, null, 'setup register');
  if (registered.status !== 201) throw new Error(`register failed: ${registered.status} ${registered.body}`);
  const login = post('/api/v1/auth/login', credentials, null, null, 'setup login');
  if (login.status !== 200) throw new Error(`login failed: ${login.status} ${login.body}`);
  return login.json('accessToken');
}

function createWallet(token) {
  const res = post('/api/v1/wallets', null, token, null, 'setup create wallet');
  if (res.status !== 201) throw new Error(`create wallet failed: ${res.status} ${res.body}`);
  return res.json('id');
}

function fund(token, walletId, amount, key) {
  const res = post(`/api/v1/wallets/${walletId}/topups`, { amount }, token, key, 'setup top-up');
  if (res.status !== 201) throw new Error(`funding failed: ${res.status} ${res.body}`);
}

export function setup() {
  waitForApp();
  const runId = Date.now().toString(36);
  const users = [];
  const perUser = Math.max(1, Math.floor(WALLETS / USERS));
  for (let u = 0; u < USERS; u++) {
    const token = registerAndLogin(`load-${runId}-${u}`);
    const wallets = [];
    for (let w = 0; w < perUser; w++) {
      const id = createWallet(token);
      fund(token, id, FUNDING, `fund-${runId}-${u}-${w}`);
      wallets.push(id);
    }
    users.push({ token, wallets });
  }
  // The hot wallet belongs to one more user ("the merchant"): everybody pays it, and only it can pay out.
  const hotToken = registerAndLogin(`load-${runId}-hot`);
  const hotWallet = createWallet(hotToken);
  fund(hotToken, hotWallet, HOT_FUNDING, `fund-${runId}-hot`);
  return { runId, users, hot: { token: hotToken, wallet: hotWallet } };
}

function randomInt(n) {
  return Math.floor(Math.random() * n);
}

function pick(list) {
  return list[randomInt(list.length)];
}

function allWallets(data) {
  return data.users.flatMap((u) => u.wallets);
}

// What each scenario sends, and as whom. Returns { path, name, body, token }. `me` is this virtual user's user.
const builders = {
  spread(data, me) {
    const from = pick(me.wallets);
    let to = pick(allWallets(data));
    while (to === from) to = pick(allWallets(data));
    return { path: '/api/v1/transfers', name: 'POST /transfers', token: me.token,
      body: { fromWalletId: from, toWalletId: to, amount: 1 + randomInt(1000) } };
  },
  hot(data, me) {
    const intoHot = Math.random() < 0.5; // half the traffic pays the hot wallet, half is paid by it
    if (intoHot) {
      return { path: '/api/v1/transfers', name: 'POST /transfers (hot wallet)', token: me.token,
        body: { fromWalletId: pick(me.wallets), toWalletId: data.hot.wallet, amount: 1 + randomInt(100) } };
    }
    return { path: '/api/v1/transfers', name: 'POST /transfers (hot wallet)', token: data.hot.token,
      body: { fromWalletId: data.hot.wallet, toWalletId: pick(allWallets(data)), amount: 1 + randomInt(100) } };
  },
  topup(data, me) {
    return { path: `/api/v1/wallets/${pick(me.wallets)}/topups`, name: 'POST /topups', token: me.token,
      body: { amount: 100 } };
  },
};

function record(res) {
  if (res.status === 201) applied.add(1);
  else if (res.status === 200) replayed.add(1);
  else if (res.status === 422) refused.add(1);
  else if (res.status === 429) rateLimited.add(1);
  else unexpected.add(1);
}

let previous = null; // each virtual user remembers its last request, so it can retry it

export function run(data) {
  const me = data.users[(__VU - 1) % data.users.length]; // virtual user N acts as user N mod USERS
  if (previous && Math.random() * 100 < REPLAY_PERCENT) {
    // A client retry (e.g. after a timeout): same key, same body. Must be answered 200 from the stored result.
    const res = post(previous.path, previous.body, previous.token, previous.key, `${previous.name} (retry)`);
    record(res);
    check(res, { 'a retry returns 200': (r) => r.status === 200 });
    previous = null;
    return;
  }
  const request = builders[SCENARIO](data, me);
  const key = `${data.runId}-${__VU}-${__ITER}`;
  const res = post(request.path, request.body, request.token, key, request.name);
  record(res);
  check(res, { 'status is 201 or 422': (r) => r.status === 201 || r.status === 422 });
  previous = res.status === 201 ? { ...request, key } : null;
}
