export function realtimeBase(): string {
  return (process.env.REALTIME_API_URL || 'http://127.0.0.1:8080').replace(/\/+$/, '');
}

export function realtimeHeaders(): HeadersInit {
  const token = (process.env.REALTIME_ADMIN_TOKEN || '').trim();
  return token ? { Authorization: `Bearer ${token}` } : {};
}
