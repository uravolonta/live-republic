"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, type LiveSummary } from "@/lib/api";

/** 예정 Live 목록. 예정 Live는 변경·취소 가능한 사전 예고다. */
export default function LivesPage() {
  const router = useRouter();
  const [lives, setLives] = useState<LiveSummary[] | null>(null);
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    (async () => {
      const res = await api<LiveSummary[]>("/api/lives");
      if (res.status === 401) {
        router.replace("/login");
        return;
      }
      if (res.status !== 200 || !res.body) {
        setLoadError(true);
        return;
      }
      setLives(res.body);
    })();
  }, [router]);

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

  if (lives === null) {
    return <main className="p-8 text-sm text-gray-500">불러오는 중…</main>;
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col gap-4 p-6">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-bold">Live 관리</h1>
        <Link href="/" className="text-sm text-gray-500 underline">
          운영 화면으로
        </Link>
      </header>

      <Link
        href="/lives/new"
        className="rounded-lg bg-black px-4 py-2 text-center text-white"
      >
        예정 Live 만들기
      </Link>

      {lives.length === 0 ? (
        <p className="text-sm text-gray-500">
          예정된 Live가 없습니다. 방송을 예고하려면 예정 Live를 만드세요.
        </p>
      ) : (
        <ul className="flex flex-col gap-3">
          {lives.map((live) => (
            <li key={live.id}>
              <Link
                href={`/lives/${live.id}`}
                className="flex items-center justify-between rounded-lg border p-4"
              >
                <div>
                  <p className="font-semibold">
                    {live.title}
                    {live.status === "CANCELLED" && (
                      <span className="ml-2 text-sm text-red-600">취소됨</span>
                    )}
                  </p>
                  <p className="text-sm text-gray-500">
                    {new Date(live.scheduledStartAt).toLocaleString()} ·{" "}
                    {live.streamerName ?? "담당자 미지정"} · 상품 {live.productCount}개
                  </p>
                </div>
                {live.status === "SCHEDULED" && (
                  <span className={`text-sm ${live.ready ? "text-green-600" : "text-amber-600"}`}>
                    {live.ready ? "방송 준비 완료" : "준비 미완료"}
                  </span>
                )}
              </Link>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
