"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, errorMessage, type LiveDetail } from "@/lib/api";

/** 예정 Live 생성 — 사전 예고이며 Streamer·상품 연결은 상세에서 진행한다. */
export default function NewLivePage() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [scheduledAt, setScheduledAt] = useState("");
  const [thumbnailUrl, setThumbnailUrl] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    const res = await api<LiveDetail>("/api/lives", {
      method: "POST",
      json: {
        title,
        scheduledStartAt: new Date(scheduledAt).toISOString(),
        thumbnailUrl: thumbnailUrl.trim() === "" ? null : thumbnailUrl.trim(),
      },
    });
    if (res.status === 201 && res.body) {
      router.replace(`/lives/${res.body.id}`);
      return;
    }
    setSubmitting(false);
    if (res.status === 401) router.replace("/login");
    else if (res.status === 400) setError(errorMessage(res.body) ?? "입력값을 확인하세요.");
    else setError("일시적인 오류로 생성하지 못했습니다. 잠시 후 다시 시도하세요.");
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-sm flex-col justify-center gap-4 p-6">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-bold">예정 Live 만들기</h1>
        <Link href="/lives" className="text-sm text-gray-500 underline">
          Live 목록으로
        </Link>
      </header>
      <p className="text-sm text-gray-500">
        예정 Live는 사전 예고입니다. 예정 시각이 지나도 자동으로 시작되지 않으며, 언제든
        수정하거나 취소할 수 있습니다.
      </p>
      <form onSubmit={submit} className="flex flex-col gap-3">
        <input
          type="text"
          required
          maxLength={200}
          placeholder="방송 제목"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          className="rounded border p-2"
        />
        <label className="flex flex-col gap-1 text-sm">
          예정 시각
          <input
            type="datetime-local"
            required
            value={scheduledAt}
            onChange={(e) => setScheduledAt(e.target.value)}
            className="rounded border p-2"
          />
        </label>
        <input
          type="url"
          maxLength={500}
          placeholder="썸네일 URL (선택, http(s)://…)"
          value={thumbnailUrl}
          onChange={(e) => setThumbnailUrl(e.target.value)}
          className="rounded border p-2"
        />
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={submitting}
          className="rounded bg-black p-2 text-white disabled:opacity-50"
        >
          만들기
        </button>
      </form>
    </main>
  );
}
