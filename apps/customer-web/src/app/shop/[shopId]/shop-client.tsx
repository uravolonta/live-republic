"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { LiveViewer } from "../../live/[liveId]/viewer-client";

type ShopState = {
  shopName: string;
  live: { id: number } | null;
};

const POLL_INTERVAL_MS = 3_000;

/**
 * Shop 상시 시청 URL (2026-08-30 정책): 공유 링크는 방송 id가 아니라 Shop 단위다.
 * 방송 중이면 즉시 시청으로 진입하고, 아니면 대기 화면에서 3초 폴링으로 기다리다
 * 방송이 시작되면 자동 진입한다. 방송이 끝나도 종료 안내를 유지하며, 새 방송이
 * 시작되면 자동으로 갈아탄다.
 */
export function ShopViewer({ shopId }: { shopId: string }) {
  const [shopName, setShopName] = useState<string | null>(null);
  const [liveId, setLiveId] = useState<number | null>(null);
  const [notFound, setNotFound] = useState(false);
  const requestSeqRef = useRef(0);

  const load = useCallback(async () => {
    const seq = ++requestSeqRef.current;
    let body: ShopState;
    try {
      const res = await fetch(`/api/viewer/shops/${shopId}`);
      if (res.status === 404) {
        if (seq === requestSeqRef.current) setNotFound(true);
        return;
      }
      if (!res.ok) return;
      body = (await res.json()) as ShopState;
    } catch {
      return;
    }
    if (seq !== requestSeqRef.current) return;
    setShopName(body.shopName);
    // 새 방송이 시작되면 진입·교체한다. null은 지우지 않는다 — 방금 끝난 방송의
    // 종료 안내(LiveViewer)를 유지하고, 다음 방송이 오면 자동으로 갈아탄다.
    if (body.live) {
      setLiveId((current) => (current === body.live!.id ? current : body.live!.id));
    }
  }, [shopId]);

  useEffect(() => {
    void Promise.resolve().then(load);
    const timer = setInterval(() => void load(), POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [load]);

  if (liveId !== null) {
    return <LiveViewer key={liveId} liveId={String(liveId)} />;
  }

  return (
    <main className="relative mx-auto flex min-h-screen max-w-lg flex-col items-center justify-center gap-3 bg-black p-8 text-white">
      <h1 className="text-lg font-semibold">{shopName ?? ""}</h1>
      {notFound ? (
        <p className="rounded-lg bg-white/10 p-4 text-center text-sm">존재하지 않는 상점입니다.</p>
      ) : (
        <>
          <p className="rounded-lg bg-white/10 p-4 text-center text-sm">
            지금은 방송 중이 아닙니다. 방송이 시작되면 자동으로 연결됩니다.
          </p>
          <p className="text-xs text-gray-400">이 페이지를 닫지 않아도 됩니다</p>
        </>
      )}
    </main>
  );
}
