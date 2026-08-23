export type ApiResult<T> = { status: number; body: T | null };

/** 같은 Origin의 /api Proxy를 호출한다. 오류 응답도 status로 그대로 전달한다. */
export async function api<T>(
  path: string,
  options: { method?: string; json?: unknown } = {},
): Promise<ApiResult<T>> {
  const res = await fetch(path, {
    method: options.method ?? "GET",
    headers: options.json !== undefined ? { "Content-Type": "application/json" } : undefined,
    body: options.json !== undefined ? JSON.stringify(options.json) : undefined,
  });
  let body: T | null = null;
  try {
    body = (await res.json()) as T;
  } catch {
    body = null;
  }
  return { status: res.status, body };
}

export type Me = { id: number; email: string; name: string; shopId: number | null };

export type Shop = {
  id: number;
  name: string;
  bankName: string | null;
  bankAccountNumber: string | null;
  bankAccountHolder: string | null;
  courierName: string | null;
  baseShippingFee: number | null;
};
