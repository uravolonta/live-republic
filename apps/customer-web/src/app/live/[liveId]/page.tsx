"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useParams } from "next/navigation";
import Script from "next/script";

/** 시청 화면은 재고 수치를 받지 않는다 — 품절 여부만 (2026-08-29 정책). */
type ViewerOption = { label: string; soldOut: boolean };
type ViewerProduct = {
  productId: number;
  name: string;
  price: number;
  soldOut: boolean;
  options: ViewerOption[];
};
type ViewerLive = {
  id: number;
  title: string;
  status: "SCHEDULED" | "STARTING" | "LIVE" | "ENDED" | "CANCELLED";
  playbackUrl: string | null;
  currentProduct: ViewerProduct | null;
};

/** IVS Web Player (CDN 로드) 최소 타입. */
type IvsPlayer = {
  attachHTMLVideoElement: (el: HTMLVideoElement) => void;
  load: (url: string) => void;
  play: () => void;
  delete: () => void;
  setMuted: (muted: boolean) => void;
};
declare global {
  interface Window {
    IVSPlayer?: {
      isPlayerSupported: boolean;
      create: () => IvsPlayer;
    };
  }
}

const POLL_INTERVAL_MS = 3_000;

/**
 * Customer 비로그인 시청 화면 (Issue #6): 공유 링크 입장 → IVS 재생,
 * 3초 폴링으로 현재 판매 상품 전환·품절을 반영한다.
 */
export default function LiveViewerPage() {
  const params = useParams<{ liveId: string }>();
  const liveId = params.liveId;

  const [live, setLive] = useState<ViewerLive | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [playerReady, setPlayerReady] = useState(false);
  const [muted, setMuted] = useState(true);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const playerRef = useRef<IvsPlayer | null>(null);
  const playingUrlRef = useRef<string | null>(null);

  const load = useCallback(async () => {
    let res: Response;
    try {
      res = await fetch(`/api/viewer/lives/${liveId}`);
    } catch {
      setLoadFailed(true);
      return;
    }
    if (res.status === 404) {
      setNotFound(true);
      return;
    }
    if (!res.ok) {
      setLoadFailed(true);
      return;
    }
    setLoadFailed(false);
    setLive((await res.json()) as ViewerLive);
  }, [liveId]);

  useEffect(() => {
    void Promise.resolve().then(load);
    const timer = setInterval(() => void load(), POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [load]);

  // 방송 상태·재생 URL에 맞춰 Player를 붙이고 뗀다.
  useEffect(() => {
    const video = videoRef.current;
    const url = live?.status === "LIVE" ? live.playbackUrl : null;
    if (!video) return;

    if (!url) {
      // 종료·시작 전 — 재생 중이면 정리한다.
      playerRef.current?.delete();
      playerRef.current = null;
      playingUrlRef.current = null;
      video.removeAttribute("src");
      return;
    }
    if (playingUrlRef.current === url) return;

    if (window.IVSPlayer?.isPlayerSupported) {
      if (!playerReady) return; // Script 로드 대기
      playerRef.current?.delete();
      const player = window.IVSPlayer.create();
      player.attachHTMLVideoElement(video);
      player.setMuted(true);
      player.load(url);
      player.play();
      playerRef.current = player;
    } else {
      // Safari 등 HLS Native 재생 폴백.
      video.src = url;
      video.muted = true;
      void video.play().catch(() => undefined);
    }
    playingUrlRef.current = url;
  }, [live?.status, live?.playbackUrl, playerReady]);

  useEffect(() => () => playerRef.current?.delete(), []);

  function toggleMute() {
    const next = !muted;
    setMuted(next);
    playerRef.current?.setMuted(next);
    if (videoRef.current) videoRef.current.muted = next;
  }

  const product = live?.status === "LIVE" ? live.currentProduct : null;

  return (
    <main className="relative mx-auto flex min-h-screen max-w-lg flex-col bg-black text-white">
      <Script
        src="https://player.live-video.net/1.24.0/amazon-ivs-player.min.js"
        strategy="afterInteractive"
        onLoad={() => setPlayerReady(true)}
      />

      <header className="absolute inset-x-0 top-0 z-10 flex items-center gap-2 bg-gradient-to-b from-black/70 to-transparent p-4">
        {live?.status === "LIVE" && (
          <span className="rounded bg-red-600 px-2 py-0.5 text-xs font-bold">LIVE</span>
        )}
        <h1 className="truncate text-sm font-semibold">{live?.title ?? ""}</h1>
      </header>

      <video
        ref={videoRef}
        playsInline
        muted
        className="h-screen w-full object-contain"
        data-testid="viewer-video"
      />

      {/* 상태 안내 (영상 위 중앙) */}
      {notFound && <CenterNotice message="존재하지 않는 방송입니다." />}
      {!notFound && loadFailed && !live && (
        <CenterNotice message="연결을 확인하고 있습니다…" />
      )}
      {live && (live.status === "ENDED" || live.status === "CANCELLED") && (
        <CenterNotice message="방송이 종료되었습니다. 시청해 주셔서 감사합니다." />
      )}
      {live && (live.status === "STARTING" || live.status === "SCHEDULED") && (
        <CenterNotice message="방송이 곧 시작됩니다. 잠시만 기다려 주세요." />
      )}

      {/* 현재 판매 상품 (방송 중에만) */}
      {product && (
        <section className="absolute inset-x-0 bottom-0 z-10 flex flex-col gap-2 bg-gradient-to-t from-black/80 to-transparent p-4">
          <button
            onClick={toggleMute}
            className="self-end rounded-full border border-white/50 px-3 py-1 text-xs"
          >
            {muted ? "🔇 소리 켜기" : "🔊 소리 끄기"}
          </button>
          <div className="rounded-xl bg-white/95 p-3 text-black">
            <p className="text-sm font-semibold">{product.name}</p>
            <p className="text-lg font-bold">{product.price.toLocaleString()}원</p>
            {product.options.length > 0 && (
              <div className="mt-1 flex flex-wrap gap-1">
                {product.options.map((option) => (
                  <span
                    key={option.label}
                    className={`rounded border px-2 py-0.5 text-xs ${
                      option.soldOut ? "text-gray-400 line-through" : "text-gray-700"
                    }`}
                  >
                    {option.label}
                    {option.soldOut && " 품절"}
                  </span>
                ))}
              </div>
            )}
            {/* 품절 시 비활성 (2026-08-29 정책). 주문 자체는 Issue #7. */}
            <button
              disabled={product.soldOut}
              onClick={() => window.alert("주문 기능을 준비하고 있습니다.")}
              className="mt-2 w-full rounded-lg bg-black py-2 text-sm font-semibold text-white disabled:bg-gray-300 disabled:text-gray-500"
            >
              {product.soldOut ? "품절" : "구매하기"}
            </button>
          </div>
        </section>
      )}
    </main>
  );
}

function CenterNotice({ message }: { message: string }) {
  return (
    <div className="absolute inset-0 z-10 flex items-center justify-center p-8">
      <p className="rounded-lg bg-black/70 p-4 text-center text-sm">{message}</p>
    </div>
  );
}
