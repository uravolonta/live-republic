"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import {
  api,
  errorMessage,
  type AppSessionInfo,
  type CurrentBroadcast,
  type Me,
  type Product,
  type ProductConfigEntry,
} from "@/lib/api";

// 시청(공유) 링크의 기준 도메인 — Customer Web production.
const CUSTOMER_WEB_URL =
  process.env.NEXT_PUBLIC_CUSTOMER_WEB_URL ?? "https://live-republic-customer-web.vercel.app";

/**
 * 방송 제어 대시보드 (2026-08-28 정책):
 * 앱은 테넌트당 1개 세션만 로그인되며, Owner는 여기서 진행 중 방송 강제 종료·앱 세션
 * 로그아웃·다음 방송의 판매 상품 사전 구성을 관리한다.
 */
export default function BroadcastControlPage() {
  const router = useRouter();
  const [current, setCurrent] = useState<CurrentBroadcast | null>(null);
  const [shopId, setShopId] = useState<number | null>(null);
  const [appSession, setAppSession] = useState<AppSessionInfo | null>(null);
  const [allProducts, setAllProducts] = useState<Product[]>([]);
  const [configIds, setConfigIds] = useState<number[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const [currentRes, sessionRes, productRes, configRes, meRes] = await Promise.all([
      api<CurrentBroadcast>("/api/broadcast/current"),
      api<AppSessionInfo>("/api/broadcast/app-session"),
      api<Product[]>("/api/products"),
      api<ProductConfigEntry[]>("/api/broadcast/config/products"),
      api<Me>("/api/auth/me"),
    ]);
    if (currentRes.status === 401) {
      router.replace("/login");
      return;
    }
    // 조회 실패를 "방송 없음·세션 없음"으로 표시하면 안 된다 — 기존 표시를 유지하고
    // 실패 배너만 띄운다.
    if (currentRes.status !== 200 || sessionRes.status !== 200) {
      setLoadFailed(true);
      return;
    }
    setLoadFailed(false);
    setShopId(meRes.body?.shopId ?? null);
    setCurrent(currentRes.body);
    setAppSession(sessionRes.body);
    setAllProducts(productRes.body ?? []);
    setConfigIds((configRes.body ?? []).map((entry) => entry.productId));
  }, [router]);

  useEffect(() => {
    void Promise.resolve().then(load);
    // 방송 상태는 바뀔 수 있으므로 15초마다 갱신한다.
    const timer = setInterval(() => void load(), 15_000);
    return () => clearInterval(timer);
  }, [load]);

  async function run(action: () => Promise<{ status: number; body: unknown }>, okMessage: string) {
    setMessage(null);
    setError(null);
    setBusy(true);
    const res = await action();
    setBusy(false);
    if (res.status >= 200 && res.status < 300) {
      setMessage(okMessage);
      void load();
    } else if (res.status === 401) {
      router.replace("/login");
    } else {
      setError(errorMessage(res.body) ?? "요청에 실패했습니다. 잠시 후 다시 시도하세요.");
    }
  }

  async function forceEnd(liveId: number) {
    if (!window.confirm("진행 중인 방송을 강제 종료할까요? 시청자에게 즉시 중단됩니다.")) return;
    await run(() => api(`/api/broadcast/lives/${liveId}/end`, { method: "POST" }), "방송을 종료했습니다.");
  }

  async function forceLogout() {
    if (
      !window.confirm(
        "방송 앱 세션을 로그아웃할까요? 진행 중인 방송이 있으면 먼저 종료됩니다.",
      )
    ) {
      return;
    }
    await run(
      () => api("/api/broadcast/app-session/logout", { method: "POST" }),
      "앱 세션을 로그아웃했습니다. 이제 다른 계정이 앱에 로그인할 수 있습니다.",
    );
  }

  async function saveConfig(ids: number[]) {
    setConfigIds(ids);
    await run(
      () => api("/api/broadcast/config/products", { method: "PUT", json: { productIds: ids } }),
      "방송 상품 구성이 저장되었습니다.",
    );
  }

  function move(index: number, delta: number) {
    const next = [...configIds];
    const target = index + delta;
    if (target < 0 || target >= next.length) return;
    [next[index], next[target]] = [next[target], next[index]];
    void saveConfig(next);
  }

  const productById = new Map(allProducts.map((p) => [p.id, p]));
  const unselected = allProducts.filter((p) => !configIds.includes(p.id));
  const live = current?.live ?? null;

  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col gap-5 p-6">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-bold">방송 제어</h1>
        <Link href="/" className="text-sm text-gray-500 underline">
          홈으로
        </Link>
      </header>

      {loadFailed && (
        <p className="rounded border border-amber-500 p-2 text-sm text-amber-600">
          상태를 불러오지 못했습니다 — 표시된 정보가 오래되었을 수 있습니다. 잠시 후
          자동으로 다시 시도합니다.
        </p>
      )}
      {message && <p className="text-sm text-green-600">{message}</p>}
      {error && <p className="text-sm text-red-600">{error}</p>}

      {shopId !== null && (
        <section className="rounded-lg border p-4">
          <h2 className="mb-1 font-semibold">상시 시청 링크</h2>
          <p className="mb-2 text-xs text-gray-500">
            우리 상점의 고정 주소입니다 — SNS 프로필·공지에 걸어 두세요. 방송을 시작하면
            이 주소로 들어온 시청자가 자동으로 연결됩니다.
          </p>
          <div className="flex items-center gap-2 rounded border bg-gray-50 p-2">
            <span className="truncate text-xs text-gray-600">{`${CUSTOMER_WEB_URL}/shop/${shopId}`}</span>
            <button
              onClick={() => {
                void navigator.clipboard
                  .writeText(`${CUSTOMER_WEB_URL}/shop/${shopId}`)
                  .then(() => setMessage("시청 링크를 복사했습니다."))
                  .catch(() => setError("복사에 실패했습니다. 링크를 직접 선택해 복사하세요."));
              }}
              className="shrink-0 rounded border px-2 py-1 text-xs"
            >
              링크 복사
            </button>
          </div>
        </section>
      )}

      <section className="rounded-lg border p-4">
        <h2 className="mb-2 font-semibold">진행 중 방송</h2>
        {live ? (
          <div className="flex flex-col gap-2 text-sm">
            <p>
              <span className={live.status === "LIVE" ? "text-red-500" : "text-amber-600"}>
                ● {live.status === "LIVE" ? "방송중" : "시작 중"}
              </span>{" "}
              {live.title}
            </p>
            <p className="text-gray-500">
              판매 상품 {live.products.length}개
              {live.startedAt && ` · 시작 ${new Date(live.startedAt).toLocaleString()}`}
            </p>
            <button
              onClick={() => void forceEnd(live.id)}
              disabled={busy}
              className="self-start rounded border border-red-600 px-3 py-1 text-red-600 disabled:opacity-50"
            >
              방송 강제 종료
            </button>
          </div>
        ) : (
          <p className="text-sm text-gray-500">진행 중인 방송이 없습니다.</p>
        )}
      </section>

      <section className="rounded-lg border p-4">
        <h2 className="mb-2 font-semibold">방송 앱 세션</h2>
        <p className="mb-2 text-xs text-gray-500">
          방송 앱은 한 번에 한 계정만 로그인할 수 있습니다. 다른 계정으로 바꾸려면 여기서
          로그아웃하세요.
        </p>
        {appSession?.session ? (
          <div className="flex items-center gap-3 text-sm">
            <span>
              {appSession.session.accountName} ·{" "}
              {new Date(appSession.session.loginAt).toLocaleString()} 로그인
            </span>
            <button
              onClick={() => void forceLogout()}
              disabled={busy}
              className="rounded border px-3 py-1 disabled:opacity-50"
            >
              앱 로그아웃
            </button>
          </div>
        ) : (
          <p className="text-sm text-gray-500">로그인된 앱 세션이 없습니다.</p>
        )}
      </section>

      <section className="rounded-lg border p-4">
        <h2 className="mb-1 font-semibold">다음 방송 판매 상품 구성</h2>
        <p className="mb-3 text-xs text-gray-500">
          앱에서 방송을 시작하면 이 구성 순서로 상품이 연결됩니다. 구성이 비어 있으면 판매
          중 상품 전체가 연결됩니다.
        </p>
        {configIds.length === 0 ? (
          <p className="mb-2 text-sm text-amber-600">
            구성 없음 — 판매 중 상품 전체({allProducts.length}개)가 연결됩니다.
          </p>
        ) : (
          <ul className="mb-3 flex flex-col gap-1">
            {configIds.map((id, index) => (
              <li key={id} className="flex items-center gap-2 rounded border px-2 py-1 text-sm">
                <span className="flex-1">
                  {index + 1}. {productById.get(id)?.name ?? `(삭제된 상품 #${id})`}
                </span>
                <button onClick={() => move(index, -1)} disabled={busy} className="px-1">
                  ↑
                </button>
                <button onClick={() => move(index, 1)} disabled={busy} className="px-1">
                  ↓
                </button>
                <button
                  onClick={() => void saveConfig(configIds.filter((v) => v !== id))}
                  disabled={busy}
                  className="px-1 text-red-500"
                >
                  ✕
                </button>
              </li>
            ))}
          </ul>
        )}
        {unselected.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {unselected.map((p) => (
              <button
                key={p.id}
                onClick={() => void saveConfig([...configIds, p.id])}
                disabled={busy}
                className="rounded border px-2 py-1 text-sm text-gray-600 disabled:opacity-50"
              >
                + {p.name}
              </button>
            ))}
          </div>
        )}
      </section>
    </main>
  );
}
