"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { api, errorMessage, type LiveDetail, type Product } from "@/lib/api";

/** 로컬 datetime-local 입력값으로 변환. */
function toLocalInput(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 예정 Live 상세 — 정보·썸네일 수정, 상품 연결·순서, 준비 상태 확인, 취소. */
export default function LiveDetailPage() {
  const router = useRouter();
  const params = useParams<{ liveId: string }>();
  const liveId = params.liveId;

  const [live, setLive] = useState<LiveDetail | null>(null);
  const [allProducts, setAllProducts] = useState<Product[]>([]);
  const [loadError, setLoadError] = useState(false);

  const [title, setTitle] = useState("");
  const [scheduledAt, setScheduledAt] = useState("");
  const [thumbnailUrl, setThumbnailUrl] = useState("");
  const [selectedProductIds, setSelectedProductIds] = useState<number[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const [liveRes, productRes] = await Promise.all([
      api<LiveDetail>(`/api/lives/${liveId}`),
      api<Product[]>("/api/products"),
    ]);
    if (liveRes.status === 401) {
      router.replace("/login");
      return;
    }
    if (liveRes.status === 404) {
      router.replace("/lives");
      return;
    }
    if (liveRes.status !== 200 || !liveRes.body) {
      setLoadError(true);
      return;
    }
    setLive(liveRes.body);
    setAllProducts(productRes.body ?? []);
    setTitle(liveRes.body.title);
    setScheduledAt(toLocalInput(liveRes.body.scheduledStartAt));
    setThumbnailUrl(liveRes.body.thumbnailUrl ?? "");
    setSelectedProductIds(liveRes.body.products.map((p) => p.productId));
  }, [liveId, router]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  function handleMutation(res: { status: number; body: LiveDetail | null }, okMessage: string) {
    setBusy(false);
    if (res.status === 200 && res.body) {
      setLive(res.body);
      setThumbnailUrl(res.body.thumbnailUrl ?? "");
      setSelectedProductIds(res.body.products.map((p) => p.productId));
      setMessage(okMessage);
    } else if (res.status === 401) {
      router.replace("/login");
    } else if (res.status === 409) {
      setError("취소된 Live는 수정할 수 없습니다.");
    } else if (res.status === 400) {
      setError(errorMessage(res.body) ?? "입력값을 확인하세요.");
    } else {
      setError("일시적인 오류가 발생했습니다. 잠시 후 다시 시도하세요.");
    }
  }

  async function saveInfo(e: React.FormEvent) {
    e.preventDefault();
    setMessage(null);
    setError(null);
    setBusy(true);
    handleMutation(
      await api<LiveDetail>(`/api/lives/${liveId}`, {
        method: "PUT",
        json: {
          title,
          scheduledStartAt: new Date(scheduledAt).toISOString(),
          thumbnailUrl: thumbnailUrl.trim() === "" ? null : thumbnailUrl.trim(),
        },
      }),
      "예정 정보가 저장되었습니다.",
    );
  }

  async function saveProducts(ids: number[]) {
    setMessage(null);
    setError(null);
    setBusy(true);
    handleMutation(
      await api<LiveDetail>(`/api/lives/${liveId}/products`, {
        method: "PUT",
        json: { productIds: ids },
      }),
      "판매 상품이 저장되었습니다.",
    );
  }

  async function cancelLive() {
    if (!window.confirm("이 예정 Live를 취소할까요? 취소 후에는 수정할 수 없습니다.")) return;
    setMessage(null);
    setError(null);
    setBusy(true);
    handleMutation(
      await api<LiveDetail>(`/api/lives/${liveId}/cancel`, { method: "POST" }),
      "Live가 취소되었습니다.",
    );
  }

  function move(index: number, delta: number) {
    const next = [...selectedProductIds];
    const target = index + delta;
    if (target < 0 || target >= next.length) return;
    [next[index], next[target]] = [next[target], next[index]];
    void saveProducts(next);
  }

  if (loadError) {
    return (
      <main className="flex min-h-screen flex-col items-center justify-center gap-3 p-8">
        <p className="text-sm text-red-600">Live를 불러오지 못했습니다.</p>
        <button onClick={() => window.location.reload()} className="rounded border px-4 py-2 text-sm">
          다시 시도
        </button>
      </main>
    );
  }

  if (!live) {
    return <main className="p-8 text-sm text-gray-500">불러오는 중…</main>;
  }

  const cancelled = live.status === "CANCELLED";
  const productById = new Map(allProducts.map((p) => [p.id, p]));
  const unselectedProducts = allProducts.filter((p) => !selectedProductIds.includes(p.id));

  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col gap-5 p-6">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-bold">
          {live.title}
          {cancelled && <span className="ml-2 text-sm text-red-600">취소됨</span>}
        </h1>
        <Link href="/lives" className="text-sm text-gray-500 underline">
          Live 목록으로
        </Link>
      </header>

      {!cancelled && (
        <section
          className={`rounded-lg border p-4 text-sm ${live.ready ? "border-green-600" : "border-amber-500"}`}
        >
          {live.ready ? (
            <p className="text-green-600">방송 준비가 완료되었습니다.</p>
          ) : (
            <div>
              <p className="mb-1 text-amber-600">방송 준비가 완료되지 않았습니다.</p>
              <ul className="list-inside list-disc text-gray-500">
                {live.notReadyReasons.map((reason) => (
                  <li key={reason}>{reason}</li>
                ))}
              </ul>
            </div>
          )}
        </section>
      )}

      {message && <p className="text-sm text-green-600">{message}</p>}
      {error && <p className="text-sm text-red-600">{error}</p>}

      <form onSubmit={saveInfo} className="flex flex-col gap-3 rounded-lg border p-4">
        <h2 className="font-semibold">예정 정보</h2>
        <input
          type="text"
          required
          maxLength={200}
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          disabled={cancelled}
          className="rounded border p-2 disabled:opacity-60"
        />
        <input
          type="datetime-local"
          required
          value={scheduledAt}
          onChange={(e) => setScheduledAt(e.target.value)}
          disabled={cancelled}
          className="rounded border p-2 disabled:opacity-60"
        />
        <input
          type="url"
          maxLength={500}
          placeholder="썸네일 URL (선택, http(s)://…)"
          value={thumbnailUrl}
          onChange={(e) => setThumbnailUrl(e.target.value)}
          disabled={cancelled}
          className="rounded border p-2 disabled:opacity-60"
        />
        {live.thumbnailUrl && (
          /* eslint-disable-next-line @next/next/no-img-element -- 외부 URL 썸네일 */
          <img
            src={live.thumbnailUrl}
            alt="Live 썸네일"
            className="h-32 w-32 rounded object-cover"
          />
        )}
        {!cancelled && (
          <button
            type="submit"
            disabled={busy}
            className="rounded bg-black p-2 text-white disabled:opacity-50"
          >
            예정 정보 저장
          </button>
        )}
      </form>

      <section className="flex flex-col gap-3 rounded-lg border p-4">
        <h2 className="font-semibold">판매 상품 (표시 순서)</h2>
        {selectedProductIds.length === 0 ? (
          <p className="text-sm text-gray-500">연결된 상품이 없습니다.</p>
        ) : (
          <ul className="flex flex-col gap-2 text-sm">
            {selectedProductIds.map((id, index) => {
              const product = productById.get(id);
              return (
                <li key={id} className="flex items-center justify-between rounded border p-2">
                  <span>
                    {index + 1}. {product?.name ?? `상품 #${id}`}
                    {product && (
                      <span className="text-gray-500"> · {product.price.toLocaleString()}원</span>
                    )}
                  </span>
                  {!cancelled && (
                    <span className="flex gap-1">
                      <button onClick={() => move(index, -1)} disabled={busy} className="rounded border px-2">
                        ↑
                      </button>
                      <button onClick={() => move(index, 1)} disabled={busy} className="rounded border px-2">
                        ↓
                      </button>
                      <button
                        onClick={() => void saveProducts(selectedProductIds.filter((p) => p !== id))}
                        disabled={busy}
                        className="rounded border px-2 text-red-600"
                      >
                        제거
                      </button>
                    </span>
                  )}
                </li>
              );
            })}
          </ul>
        )}
        {!cancelled && unselectedProducts.length > 0 && (
          <select
            value=""
            onChange={(e) => {
              if (e.target.value !== "") {
                void saveProducts([...selectedProductIds, Number(e.target.value)]);
              }
            }}
            disabled={busy}
            className="rounded border p-2"
          >
            <option value="">+ 상품 추가…</option>
            {unselectedProducts.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name} ({p.price.toLocaleString()}원)
              </option>
            ))}
          </select>
        )}
      </section>

      {!cancelled && (
        <button
          onClick={cancelLive}
          disabled={busy}
          className="rounded-lg border border-red-600 p-2 text-red-600 disabled:opacity-50"
        >
          예정 Live 취소
        </button>
      )}
    </main>
  );
}
