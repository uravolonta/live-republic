"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { api, type Shop } from "@/lib/api";

export default function NewShopPage() {
  const router = useRouter();
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    const res = await api<Shop>("/api/shops", { method: "POST", json: { name } });
    if (res.status === 201) {
      router.replace("/settings");
      return;
    }
    setSubmitting(false);
    if (res.status === 401) router.replace("/login");
    else if (res.status === 409) router.replace("/");
    else setError("Shop 생성에 실패했습니다. 잠시 후 다시 시도하세요.");
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-sm flex-col justify-center gap-4 p-6">
      <h1 className="text-xl font-bold">Shop 만들기</h1>
      <p className="text-sm text-gray-500">
        Customer가 방문할 독립 판매 공간의 이름을 정하세요.
      </p>
      <form onSubmit={submit} className="flex flex-col gap-3">
        <input
          type="text"
          required
          maxLength={100}
          placeholder="Shop 이름"
          value={name}
          onChange={(e) => setName(e.target.value)}
          className="rounded border p-2"
        />
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={submitting}
          className="rounded bg-black p-2 text-white disabled:opacity-50"
        >
          Shop 생성
        </button>
      </form>
    </main>
  );
}
