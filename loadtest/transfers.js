// k6 load test: random transfers + overdraft contention + concurrent duplicate keys.
// Run: k6 run -e BASE_URL=http://localhost:8080 -e RATE=200 -e DURATION=60s loadtest/transfers.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = parseInt(__ENV.RATE || '200', 10);
const DURATION = __ENV.DURATION || '60s';
const ACCOUNTS = 200;
const HOT_ACCOUNTS = 10;

const idemMismatch = new Counter('idem_mismatch');

export const options = {
  setupTimeout: '180s',
  scenarios: {
    transfers: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 100,
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    idem_mismatch: ['count==0'],
    checks: ['rate>0.99'],
  },
};

function params(key) {
  return { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key } };
}

function newKey() {
  return `k6-${__VU}-${__ITER}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function createFunded(amount) {
  const acct = http.post(`${BASE}/accounts`, JSON.stringify({ name: 'k6', currency: 'USD' }),
    { headers: { 'Content-Type': 'application/json' } });
  if (acct.status !== 201) throw new Error(`create account failed: ${acct.status} ${acct.body}`);
  const id = acct.json('id');
  const dep = http.post(`${BASE}/accounts/${id}/deposits`, JSON.stringify({ amount }), params(newKey()));
  if (dep.status !== 201) throw new Error(`deposit failed: ${dep.status} ${dep.body}`);
  return id;
}

export function setup() {
  const accounts = [];
  const hot = [];
  for (let i = 0; i < ACCOUNTS; i++) accounts.push(createFunded(1000000));
  for (let i = 0; i < HOT_ACCOUNTS; i++) hot.push(createFunded(5000));
  return { accounts, hot };
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

function idOf(res) {
  try {
    return res.json('id');
  } catch (e) {
    return null;
  }
}

export default function (data) {
  const fromHot = Math.random() < 0.10;
  const from = fromHot ? pick(data.hot) : pick(data.accounts);
  let to = pick(data.accounts);
  while (to === from) to = pick(data.accounts);
  const amount = 1 + Math.floor(Math.random() * (fromHot ? 2000 : 1000));
  const body = JSON.stringify({ fromAccountId: from, toAccountId: to, amount, currency: 'USD' });
  const url = `${BASE}/transfers`;
  const key = newKey();

  if (Math.random() < 0.05) {
    // Same key sent twice at once: one creates, the other replays (200) or waits and replays.
    const [a, b] = http.batch([
      ['POST', url, body, params(key)],
      ['POST', url, body, params(key)],
    ]);
    check(a, { 'dup status in [200,201,422]': (r) => [200, 201, 422].includes(r.status) });
    check(b, { 'dup status in [200,201,422]': (r) => [200, 201, 422].includes(r.status) });
    const idA = idOf(a);
    if (idA === null || idA !== idOf(b)) idemMismatch.add(1);
    return;
  }

  const res = http.post(url, body, params(key));
  check(res, { 'status in [201,422]': (r) => r.status === 201 || r.status === 422 });
}

export function teardown() {
  const res = http.get(`${BASE}/admin/audit`);
  console.log(`AUDIT ${JSON.stringify(res.json())}`);
}

export function handleSummary(data) {
  return {
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
    'results/k6-summary.json': JSON.stringify(data, null, 2),
    'results/k6-summary.txt': textSummary(data, { indent: ' ', enableColors: false }),
  };
}
