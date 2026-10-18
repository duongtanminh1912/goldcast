import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const services = ['db', 'backend', 'frontend', 'caddy'];
const digestPattern = /^\S+@sha256:[a-f0-9]{64}$/;
const releasePattern = /^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$/;
const environment = config => Object.fromEntries((config.Env ?? []).map(entry => {
  const equals = entry.indexOf('=');
  if (equals < 1) throw new Error('Invalid container environment');
  return [entry.slice(0, equals), entry.slice(equals + 1)];
}));

// Compose interpolates even JSON strings. Preserve literal dollars in secrets,
// entrypoints and healthchecks rather than interpreting them as host variables.
function escapeInterpolation(value) {
  if (typeof value === 'string') return value.replaceAll('$', () => '$$');
  if (Array.isArray(value)) return value.map(escapeInterpolation);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, escapeInterpolation(item)]));
  }
  return value;
}

export function captureRelease(containers, images) {
  const compose = { services: {}, volumes: {}, networks: { default: { name: 'goldcast_default' } } };
  const manifest = {};
  for (const service of services) {
    const container = containers.find(c => c.Name === `/goldcast-${service}`);
    if (!container || container.Config.Labels?.['com.docker.compose.project'] !== 'goldcast') {
      throw new Error(`Unexpected Compose project/container for ${service}`);
    }
    if (Object.keys(container.NetworkSettings.Networks).join() !== 'goldcast_default') {
      throw new Error(`Unsupported network layout for ${service}; inspect before adoption`);
    }
    const image = images.find(i => i.Id === container.Image);
    const digest = image?.RepoDigests?.find(d => digestPattern.test(d));
    if (!digest) throw new Error(`Registry digest required for ${service}`);
    const config = container.Config;
    const host = container.HostConfig;
    const env = environment(config);
    const captured = {
      image: digest, container_name: `goldcast-${service}`, environment: env,
      entrypoint: config.Entrypoint ?? [], command: config.Cmd ?? [],
      networks: { default: { aliases: container.NetworkSettings.Networks.goldcast_default.Aliases ?? [service] } },
    };
    if (config.User) captured.user = config.User;
    if (config.WorkingDir) captured.working_dir = config.WorkingDir;
    const restart = host.RestartPolicy;
    if (restart?.Name) captured.restart = restart.Name === 'on-failure' && restart.MaximumRetryCount
      ? `on-failure:${restart.MaximumRetryCount}` : restart.Name;
    if (config.Healthcheck) {
      const health = config.Healthcheck;
      captured.healthcheck = { test: health.Test };
      for (const [source, target] of [['Interval', 'interval'], ['Timeout', 'timeout'], ['StartPeriod', 'start_period'], ['StartInterval', 'start_interval']]) {
        if (health[source]) captured.healthcheck[target] = `${health[source]}ns`;
      }
      if (health.Retries) captured.healthcheck.retries = health.Retries;
    }
    if (['db', 'backend'].includes(service) && (!config.Healthcheck?.Test || config.Healthcheck.Test[0] === 'NONE')) {
      throw new Error(`Required healthcheck missing for ${service}`);
    }
    if (host.ReadonlyRootfs) captured.read_only = true;
    if (host.Init) captured.init = true;
    if (host.Memory) captured.mem_limit = host.Memory;
    if (host.MemorySwap) captured.memswap_limit = host.MemorySwap;
    if (host.NanoCpus) captured.cpus = host.NanoCpus / 1e9;
    for (const [source, target] of [['CapAdd', 'cap_add'], ['CapDrop', 'cap_drop'], ['SecurityOpt', 'security_opt']]) {
      if (host[source]?.length) captured[target] = host[source];
    }
    if (host.Privileged || host.Devices?.length || host.DeviceRequests?.length) {
      throw new Error(`Unsupported privileged/device configuration for ${service}`);
    }
    captured.volumes = (container.Mounts ?? []).map((mount, index) => {
      if (mount.Type === 'volume') {
        const owned = mount.Name.startsWith('goldcast_');
        const key = owned ? mount.Name.slice('goldcast_'.length) : `${service}_${index}`;
        if (service === 'db' && mount.Destination === '/var/lib/postgresql/data' && mount.Name !== 'goldcast_goldcast-pgdata') {
          throw new Error('Unexpected PostgreSQL volume');
        }
        compose.volumes[key] = { name: mount.Name, ...(!owned && { external: true }) };
        return { type: 'volume', source: key, target: mount.Destination, read_only: !mount.RW };
      }
      if (service === 'caddy' && mount.Type === 'bind' && mount.Destination === '/etc/caddy/Caddyfile') {
        return { type: 'bind', source: './deploy/Caddyfile', target: mount.Destination, read_only: !mount.RW };
      }
      throw new Error(`Unsupported mount for ${service}: ${mount.Destination}`);
    });
    if (service === 'db' && !captured.volumes.some(m => m.target === '/var/lib/postgresql/data')) {
      throw new Error('PostgreSQL data mount missing');
    }
    if (service === 'caddy' && !captured.volumes.some(m => m.target === '/etc/caddy/Caddyfile')) {
      throw new Error('Running Caddyfile bind mount required');
    }
    const ports = Object.entries(host.PortBindings ?? {}).flatMap(([port, bindings]) =>
      (bindings ?? []).map(binding => ({
        target: Number(port.split('/')[0]), protocol: port.split('/')[1],
        published: binding.HostPort, host_ip: binding.HostIp || '0.0.0.0',
      })));
    if (service !== 'caddy' && ports.length) throw new Error(`Unexpected published port on ${service}`);
    if (ports.length) captured.ports = ports;
    compose.services[service] = captured;
    if (service === 'backend' || service === 'frontend') manifest[`${service.toUpperCase()}_IMAGE`] = digest;
    if (service === 'frontend' && env.GOLDCAST_RELEASE_ID) {
      if (!releasePattern.test(env.GOLDCAST_RELEASE_ID)) throw new Error('Invalid running release identity');
      manifest.RELEASE_ID = env.GOLDCAST_RELEASE_ID;
    }
  }
  // Keep startup ordering when this snapshot is used by rollback or recovery.
  compose.services.backend.depends_on = { db: { condition: 'service_healthy' } };
  compose.services.frontend.depends_on = { backend: { condition: 'service_healthy' } };
  compose.services.caddy.depends_on = ['frontend'];
  const databaseEnv = compose.services.db.environment;
  const backendEnv = compose.services.backend.environment;
  const runtimeEnv = {};
  for (const key of ['POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD']) {
    if (!databaseEnv[key]) throw new Error(`Missing live ${key}`);
    runtimeEnv[key] = databaseEnv[key];
  }
  for (const key of ['APP_INGEST_ENABLED', 'APP_INGEST_SEED_ON_EMPTY', 'APP_INGEST_CRON', 'APP_CORS_ALLOWED_ORIGINS', 'APP_ADMIN_TOKEN']) {
    if (backendEnv[key] !== undefined) runtimeEnv[key] = backendEnv[key];
  }
  if (!manifest.RELEASE_ID && compose.services.frontend.environment.API_BASE_URL_INTERNAL !== 'http://backend:8080') {
    throw new Error('Legacy adoption requires the standard internal backend route');
  }
  return { compose: escapeInterpolation(compose), manifest, runtimeEnv, legacy: !manifest.RELEASE_ID };
}

if ((process.argv[1] === '-' && import.meta.url.endsWith('/[eval1]')) || (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)) {
  const directory = process.argv[2];
  if (!directory) throw new Error('Missing snapshot directory');
  const result = captureRelease(
    JSON.parse(readFileSync(join(directory, 'containers.json'), 'utf8')),
    JSON.parse(readFileSync(join(directory, 'images.json'), 'utf8')),
  );
  writeFileSync(join(directory, 'docker-compose.prod.yml'), JSON.stringify(result.compose, null, 2) + '\n', { mode: 0o600 });
  writeFileSync(join(directory, 'release.env'), Object.entries(result.manifest).map(([k, v]) => `${k}=${v}\n`).join(''), { mode: 0o600 });
  const literal = value => "'" + value.replaceAll("'", () => "\\'") + "'";
  writeFileSync(join(directory, 'runtime.env'), Object.entries(result.runtimeEnv).map(([k, v]) => `${k}=${literal(v)}\n`).join(''), { mode: 0o600 });
  if (result.legacy) writeFileSync(join(directory, 'legacy-local-origin'), 'Direct VPS origin required\n', { mode: 0o600 });
  console.log('Captured running images, environment, mounts and service configuration');
}
