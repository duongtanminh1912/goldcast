export function runtimeFixture() {
  const containers = ['db', 'backend', 'frontend', 'caddy'].map((service, index) => ({
    Id: service + '-container', Name: `/goldcast-${service}`, Image: `sha256:image-${service}`,
    Config: {
      Labels: { 'com.docker.compose.project': 'goldcast' },
      Env: service === 'db' ? ['POSTGRES_DB=goldcast', 'POSTGRES_USER=goldcast', 'POSTGRES_PASSWORD=database-secret']
        : service === 'backend' ? ['APP_RELEASE_ID=previous-122', 'APP_INGEST_ENABLED=true', 'APP_INGEST_SEED_ON_EMPTY=true', 'APP_ADMIN_TOKEN=secret$literal']
        : service === 'frontend' ? ['API_BASE_URL_INTERNAL=http://backend:8080', 'GOLDCAST_RELEASE_ID=previous-122'] : [],
      Entrypoint: ['entry-' + service], Cmd: ['run-' + service], User: service === 'frontend' ? 'nextjs' : '',
      WorkingDir: '/app', Healthcheck: { Test: ['CMD', 'check'], Interval: 5e9, Retries: 20 },
    },
    HostConfig: {
      RestartPolicy: { Name: 'unless-stopped' }, Memory: 256 * 1024 * 1024,
      PortBindings: service === 'caddy' ? { '443/tcp': [{ HostIp: '', HostPort: '443' }] } : {},
    },
    NetworkSettings: { Networks: { goldcast_default: { Aliases: [service, `goldcast-${service}`] } } },
    Mounts: service === 'db' ? [{ Type: 'volume', Name: 'goldcast_goldcast-pgdata', Destination: '/var/lib/postgresql/data', RW: true }]
      : service === 'caddy' ? [
        { Type: 'bind', Source: '/old/release/deploy/Caddyfile', Destination: '/etc/caddy/Caddyfile', RW: false },
        { Type: 'volume', Name: 'goldcast_caddy-data', Destination: '/data', RW: true },
      ] : [],
  }));
  const images = containers.map((c, i) => ({ Id: c.Image, RepoDigests: [`example/${i}@sha256:${String(i + 1).repeat(64)}`] }));
  return { containers, images };
}
