// G2: real candidate, separate management port and the actual Prometheus wire format.
const base = process.argv[2];
const response = await fetch(base + '/actuator/prometheus', { signal: AbortSignal.timeout(10000) });
if (!response.ok) throw new Error(`Prometheus endpoint HTTP ${response.status}`);
const text = await response.text();
for (const name of ['jvm_memory_used_bytes', 'http_server_requests_seconds_count', 'goldcast_release_info',
  'goldcast_ingest_enabled', 'goldcast_market_refresh_success', 'goldcast_live_gold_enabled',
  'goldcast_live_gold_observed_timestamp', 'goldcast_live_gold_last_fetch_timestamp']) {
  if (!new RegExp('^' + name + '(?:\\{| )', 'm').test(text)) throw new Error('Missing metric: ' + name);
}
if (!text.includes(`release_id="${process.argv[3]}"`)) throw new Error('Metrics release identity mismatch');
const applicationPort = await fetch('http://backend:8080/actuator/prometheus', { signal: AbortSignal.timeout(10000) });
if (applicationPort.status !== 404) throw new Error('Management endpoint leaked onto application port');
console.log('PASS monitoring endpoint, metric families, release identity, management port isolation');
