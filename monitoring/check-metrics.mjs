// G2: real candidate, separate management port and the actual Prometheus wire format.
const base = process.argv[2];
const families = ['jvm_memory_used_bytes', 'http_server_requests_seconds_count', 'goldcast_release_info',
  'goldcast_ingest_enabled', 'goldcast_market_refresh_success', 'goldcast_live_gold_enabled',
  'goldcast_live_gold_observed_timestamp', 'goldcast_live_gold_last_fetch_timestamp',
  'goldcast_macro_enabled', 'goldcast_macro_max_lag_days', 'goldcast_macro_observation_lag_days'];

async function scrape() {
  const response = await fetch(base + '/actuator/prometheus', { signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw new Error(`Prometheus endpoint HTTP ${response.status}`);
  return response.text();
}

// The refresh reads every series and both macro drivers from the real database; until its
// first run the gauge is 0, so wait for it instead of racing the scheduler.
let text = await scrape();
for (let i = 0; i < 30 && !/^goldcast_market_refresh_success(\{[^}]*\})? 1(\.0)?$/m.test(text); i++) {
  await new Promise(resolve => setTimeout(resolve, 1000));
  text = await scrape();
}
for (const name of families) {
  if (!new RegExp('^' + name + '(?:\\{| )', 'm').test(text)) throw new Error('Missing metric: ' + name);
}
if (!/^goldcast_market_refresh_success(\{[^}]*\})? 1(\.0)?$/m.test(text)) throw new Error('Monitoring refresh against the database never succeeded');
if (!/^goldcast_ingest_provider_enabled\{[^}]*provider="fred"/m.test(text)) throw new Error('Missing provider metric for fred');
if (!text.includes(`release_id="${process.argv[3]}"`)) throw new Error('Metrics release identity mismatch');
const applicationPort = await fetch('http://backend:8080/actuator/prometheus', { signal: AbortSignal.timeout(10000) });
if (applicationPort.status !== 404) throw new Error('Management endpoint leaked onto application port');
console.log('PASS monitoring endpoint, metric families, database refresh, release identity, management port isolation');
