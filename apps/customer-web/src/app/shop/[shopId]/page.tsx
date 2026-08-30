import type { Metadata } from "next";
import { ShopViewer } from "./shop-client";

const API_BASE = process.env.API_PROXY_TARGET ?? "http://localhost:8080";

/** Shop 상시 공유 링크의 SNS 미리보기 — 상점 이름(방송 중이면 방송 제목·썸네일). */
export async function generateMetadata({
  params,
}: {
  params: Promise<{ shopId: string }>;
}): Promise<Metadata> {
  const { shopId } = await params;
  try {
    const res = await fetch(`${API_BASE}/api/viewer/shops/${shopId}`, {
      next: { revalidate: 30 },
    });
    if (!res.ok) return { title: "Live Republic" };
    const shop = (await res.json()) as {
      shopName?: string;
      live?: { title?: string; thumbnailUrl?: string | null } | null;
    };
    const title = shop.live?.title ?? shop.shopName ?? "Live Republic";
    return {
      title,
      description: `${shop.shopName ?? ""} 라이브 — Live Republic`.trim(),
      openGraph: {
        title,
        description: "라이브로 보고 바로 구매하세요",
        ...(shop.live?.thumbnailUrl ? { images: [shop.live.thumbnailUrl] } : {}),
      },
    };
  } catch {
    return { title: "Live Republic" };
  }
}

export default async function ShopViewerPage({
  params,
}: {
  params: Promise<{ shopId: string }>;
}) {
  const { shopId } = await params;
  return <ShopViewer shopId={shopId} />;
}
