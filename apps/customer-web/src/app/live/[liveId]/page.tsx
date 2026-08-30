import type { Metadata } from "next";
import { LiveViewer } from "./viewer-client";

const API_BASE = process.env.API_PROXY_TARGET ?? "http://localhost:8080";

/**
 * SNS 공유 미리보기 — 링크 공유가 핵심 유입 경로이므로 Live 제목·썸네일로
 * Open Graph 메타데이터를 만든다 (서버에서 조회, 30초 재검증).
 */
export async function generateMetadata({
  params,
}: {
  params: Promise<{ liveId: string }>;
}): Promise<Metadata> {
  const { liveId } = await params;
  try {
    const res = await fetch(`${API_BASE}/api/viewer/lives/${liveId}`, {
      next: { revalidate: 30 },
    });
    if (!res.ok) return { title: "Live Republic" };
    const live = (await res.json()) as { title?: string; thumbnailUrl?: string | null };
    return {
      title: live.title ?? "Live Republic",
      description: "라이브로 보고 바로 구매하세요 — Live Republic",
      openGraph: {
        title: live.title ?? "Live Republic",
        description: "라이브로 보고 바로 구매하세요",
        ...(live.thumbnailUrl ? { images: [live.thumbnailUrl] } : {}),
      },
    };
  } catch {
    return { title: "Live Republic" };
  }
}

export default async function LiveViewerPage({
  params,
}: {
  params: Promise<{ liveId: string }>;
}) {
  const { liveId } = await params;
  return <LiveViewer liveId={liveId} />;
}
