"use client";

import { useCallback, useEffect, useRef, useState } from "react";
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
  addEventListener: (type: string, listener: () => void) => void;
};
declare global {
  interface Window {
    IVSPlayer?: {
      isPlayerSupported: boolean;
      create: () => IvsPlayer;
      PlayerEventType: { ERROR: string };
    };
  }
}

const POLL_INTERVAL_MS = 3_000;

/**
 * Customer 비로그인 시청 화면 (Issue #6): 공유 링크 입장 → IVS 재생,
 * 3초 폴링으로 현재 판매 상품 전환·품절을 반영한다.
 */
export function LiveViewer({ liveId }: { liveId: string }) {
  const [live, setLive] = useState<ViewerLive | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  /** IVS 스크립트: 로딩 중에는 폴백하지 않는다 — 로딩 중 ≠ 미지원 (P1). */
  const [scriptState, setScriptState] = useState<"loading" | "ready" | "failed">("loading");
  const [playbackError, setPlaybackError] = useState(false);
  const [retryTick, setRetryTick] = useState(0);
  const [muted, setMuted] = useState(true);
  const [videoPlaying, setVideoPlaying] = useState(false);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const playerRef = useRef<IvsPlayer | null>(null);
  const playingUrlRef = useRef<string | null>(null);
  /** 폴링 응답 순서 역전 방지 — 최신 요청의 응답만 반영한다. */
  const requestSeqRef = useRef(0);

  const load = useCallback(async () => {
    const seq = ++requestSeqRef.current;
    let body: ViewerLive;
    try {
      const res = await fetch(`/api/viewer/lives/${liveId}`);
      if (res.status === 404) {
        if (seq === requestSeqRef.current) setNotFound(true);
        return;
      }
      if (!res.ok) throw new Error(String(res.status));
      body = (await res.json()) as ViewerLive;
    } catch {
      if (seq === requestSeqRef.current) setLoadFailed(true);
      return;
    }
    // 느린 네트워크에서 오래된 LIVE 응답이 종료 화면을 되돌리지 않게 한다.
    if (seq !== requestSeqRef.current) return;
    setLoadFailed(false);
    setLive(body);
  }, [liveId]);

  useEffect(() => {
    void Promise.resolve().then(load);
    const timer = setInterval(() => void load(), POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [load]);

  /** 렌더 사이클과 분리해 재생 성공·실패를 기록한다 (effect 내 동기 setState 회피). */
  const markPlaybackError = useCallback((failed: boolean) => {
    queueMicrotask(() => setPlaybackError(failed));
  }, []);

  // 방송 상태·재생 URL·스크립트 상태에 맞춰 Player를 붙이고 뗀다.
  useEffect(() => {
    const video = videoRef.current;
    const url = live?.status === "LIVE" ? live.playbackUrl : null;
    if (!video) return;

    if (!url) {
      // 종료·시작 전 — Native HLS는 src 제거만으로는 리소스가 남을 수 있어 load()로 초기화한다.
      playerRef.current?.delete();
      playerRef.current = null;
      playingUrlRef.current = null;
      // pause 이벤트가 videoPlaying 상태를 함께 내린다 (onPause 핸들러).
      video.pause();
      video.removeAttribute("src");
      video.load();
      return;
    }
    if (playingUrlRef.current === url) return;
    if (scriptState === "loading") return; // 스크립트 대기 — 성급한 폴백 금지 (P1)

    if (scriptState === "ready" && window.IVSPlayer?.isPlayerSupported) {
      try {
        playerRef.current?.delete();
        const IVS = window.IVSPlayer;
        const player = IVS.create();
        player.addEventListener(IVS.PlayerEventType.ERROR, () => setPlaybackError(true));
        player.attachHTMLVideoElement(video);
        player.setMuted(true);
        player.load(url);
        player.play();
        playerRef.current = player;
        playingUrlRef.current = url; // 초기화가 실제로 성공한 뒤에만 기록한다
        markPlaybackError(false);
      } catch {
        markPlaybackError(true);
      }
      return;
    }
    // 스크립트 실패 또는 IVS 미지원 — Native HLS 지원을 확인한 뒤 폴백한다.
    if (video.canPlayType("application/vnd.apple.mpegurl")) {
      video.src = url;
      video.muted = true;
      void video.play().catch(() => undefined);
      playingUrlRef.current = url;
      markPlaybackError(false);
    } else {
      markPlaybackError(true);
    }
  }, [live?.status, live?.playbackUrl, scriptState, retryTick, markPlaybackError]);

  useEffect(() => () => playerRef.current?.delete(), []);

  /** 재생 실패 복구 — 스트림을 처음부터 다시 붙인다. */
  const retryPlayback = useCallback(() => {
    if (scriptState === "failed") {
      // 스크립트 자체를 못 받은 경우 — 새로고침으로 재시도한다.
      window.location.reload();
      return;
    }
    playingUrlRef.current = null;
    setPlaybackError(false);
    setRetryTick((tick) => tick + 1);
  }, [scriptState]);

  function toggleMute() {
    const next = !muted;
    setMuted(next);
    playerRef.current?.setMuted(next);
    if (videoRef.current) videoRef.current.muted = next;
  }

  const product = live?.status === "LIVE" ? live.currentProduct : null;

  return (
    <main className="relative mx-auto flex min-h-screen max-w-lg flex-col bg-black text-white">
      {/* onReady는 스크립트가 이미 로드된 재마운트에서도 호출된다 (onLoad는 최초 1회만). */}
      <Script
        src="https://player.live-video.net/1.24.0/amazon-ivs-player.min.js"
        strategy="afterInteractive"
        onReady={() => setScriptState("ready")}
        onError={() => setScriptState("failed")}
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
        onPlaying={() => setVideoPlaying(true)}
        onPause={() => setVideoPlaying(false)}
        className="h-screen w-full object-contain"
        data-testid="viewer-video"
      />

      {/* 재생 실패 — 원인 안내와 재시도 (매니페스트·네트워크 오류, 스크립트 실패, 미지원). */}
      {live?.status === "LIVE" && playbackError && (
        <div className="absolute inset-0 z-20 flex flex-col items-center justify-center gap-3 p-8">
          <p className="rounded-lg bg-black/70 p-4 text-center text-sm">
            영상을 재생하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.
          </p>
          <button
            onClick={retryPlayback}
            className="rounded-full border border-white/60 px-5 py-2 text-sm"
          >
            다시 시도
          </button>
        </div>
      )}

      {/* 자동재생이 차단된 환경(iOS 저전력 모드 등) — 탭 제스처로 재생을 시작한다. */}
      {live?.status === "LIVE" && !playbackError && !videoPlaying && (
        <button
          onClick={() => {
            playerRef.current?.play();
            void videoRef.current?.play().catch(() => undefined);
          }}
          className="absolute inset-0 z-10 flex items-center justify-center"
        >
          <span className="rounded-full bg-black/70 px-6 py-3 text-lg">▶ 탭하여 재생</span>
        </button>
      )}

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
