/** Headers for Mini App API calls. Always a valid HeadersInit (no undefined values). */
export function telegramAuthHeaders(initData?: string | null): HeadersInit {
  if (!initData) return {};
  return { "x-telegram-init-data": initData };
}
