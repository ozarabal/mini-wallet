// k6 load test for mini-wallet. Pick a scenario with SCENARIO=spread|hot|topup (see load-test/README.md).
//
//   spread - random transfers between many wallets: the "normal" case, little lock contention
//   hot    - every transfer involves ONE wallet: the worst case for row locking
//   topup  - every request is a top-up: all of them lock the single system cash-in wallet
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
const WALLETS = Number(__ENV.WALLETS || 200);
const REPLAY_PERCENT = Number(__ENV.REPLAY_PERCENT || 5); // share of requests that retry the previous one
const FUNDING = 1000000;        // every wallet starts with this
const HOT_FUNDING = 1000000000; // the hot wallet can pay out for the whole test

// 200/201 = applied or replayed, 422 = business refusal (e.g. insufficient funds). Anything else is a failure.
http.setResponseCallback(http.expectedStatuses(200, 201, 422));

const applied = new Counter('wallet_applied');       // 201: a new transaction
const replayed = new Counter('wallet_replayed');     // 200: a retry answered from the stored result
const refused = new Counter('wallet_refused');       // 422
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
    // These never fail; they make k6 report request and outcome counts for the measured phase only.
    'http_reqs{scenario:measured}': ['count>=0'],
    'wallet_applied{scenario:measured}': ['count>=0'],
    'wallet_replayed{scenario:measured}': ['count>=0'],
    'wallet_refused{scenario:measured}': ['count>=0'],
  },
};

function json(path, body, key, name) {
  return http.post(`${BASE}${path}`, JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key },
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

function createWallet(owner) {
  const res = http.post(`${BASE}/api/v1/wallets`, JSON.stringify({ ownerId: owner }),
    { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 201) throw new Error(`create wallet failed: ${res.status} ${res.body}`);
  return res.json('id');
}

function fund(walletId, amount, key) {
  const res = json(`/api/v1/wallets/${walletId}/topups`, { amount }, key, 'setup top-up');
  if (res.status !== 201) throw new Error(`funding failed: ${res.status} ${res.body}`);
}

export function setup() {
  waitForApp();
  const runId = Date.now().toString(36);
  const wallets = [];
  for (let i = 0; i < WALLETS; i++) {
    const id = createWallet(`load-${runId}-${i}`);
    fund(id, FUNDING, `fund-${runId}-${i}`);
    wallets.push(id);
  }
  const hot = createWallet(`load-${runId}-hot`);
  fund(hot, HOT_FUNDING, `fund-${runId}-hot`);
  return { runId, wallets, hot };
}

function randomInt(n) {
  return Math.floor(Math.random() * n);
}

function twoDifferent(list) {
  const a = randomInt(list.length);
  let b = randomInt(list.length - 1);
  if (b >= a) b += 1;
  return [list[a], list[b]];
}

// What each scenario sends. Returns { path, name, body }.
const builders = {
  spread(data) {
    const [from, to] = twoDifferent(data.wallets);
    return { path: '/api/v1/transfers', name: 'POST /transfers',
      body: { fromWalletId: from, toWalletId: to, amount: 1 + randomInt(1000) } };
  },
  hot(data) {
    const other = data.wallets[randomInt(data.wallets.length)];
    const intoHot = Math.random() < 0.5; // half the traffic pays the hot wallet, half is paid by it
    return { path: '/api/v1/transfers', name: 'POST /transfers (hot wallet)',
      body: { fromWalletId: intoHot ? other : data.hot, toWalletId: intoHot ? data.hot : other,
        amount: 1 + randomInt(100) } };
  },
  topup(data) {
    const wallet = data.wallets[randomInt(data.wallets.length)];
    return { path: `/api/v1/wallets/${wallet}/topups`, name: 'POST /topups', body: { amount: 100 } };
  },
};

function record(res) {
  if (res.status === 201) applied.add(1);
  else if (res.status === 200) replayed.add(1);
  else if (res.status === 422) refused.add(1);
  else unexpected.add(1);
}

let previous = null; // each virtual user remembers its last request, so it can retry it

export function run(data) {
  if (previous && Math.random() * 100 < REPLAY_PERCENT) {
    // A client retry (e.g. after a timeout): same key, same body. Must be answered 200 from the stored result.
    const res = json(previous.path, previous.body, previous.key, `${previous.name} (retry)`);
    record(res);
    check(res, { 'a retry returns 200': (r) => r.status === 200 });
    previous = null;
    return;
  }
  const request = builders[SCENARIO](data);
  const key = `${data.runId}-${__VU}-${__ITER}`;
  const res = json(request.path, request.body, key, request.name);
  record(res);
  check(res, { 'status is 201 or 422': (r) => r.status === 201 || r.status === 422 });
  previous = res.status === 201 ? { ...request, key } : null;
}
