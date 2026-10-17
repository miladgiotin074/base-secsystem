export function formatTime(ms: number): string {
  return new Intl.DateTimeFormat('fa-IR', {
    hour: '2-digit',
    minute: '2-digit',
  }).format(ms);
}

export function formatRelative(ms: number): string {
  const delta = Date.now() - ms;
  if (delta < 60_000) return 'همین الان';
  if (delta < 3_600_000) return `${Math.floor(delta / 60_000)} دقیقه پیش`;
  if (delta < 86_400_000) return `${Math.floor(delta / 3_600_000)} ساعت پیش`;
  return formatTime(ms);
}

/** Android Messages–style list timestamp. */
export function formatSmsListTime(ms: number): string {
  const now = new Date();
  const d = new Date(ms);
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const startYesterday = startToday - 86_400_000;
  if (ms >= startToday) return formatTime(ms);
  if (ms >= startYesterday) return 'دیروز';
  if (now.getFullYear() === d.getFullYear()) {
    return new Intl.DateTimeFormat('fa-IR', { month: 'short', day: 'numeric' }).format(ms);
  }
  return new Intl.DateTimeFormat('fa-IR', { year: 'numeric', month: 'short', day: 'numeric' }).format(ms);
}

export function formatSmsBubbleTime(ms: number): string {
  return formatTime(ms);
}

export function formatPortCode(code: string): string {
  return code.replace(/(.{4})(?=.)/g, '$1 ').trim();
}
