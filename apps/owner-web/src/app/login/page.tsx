"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, type Me } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    const res = await api<Me>("/api/auth/login", {
      method: "POST",
      json: { email, password },
    });
    if (res.status === 200 && res.body) {
      router.replace(res.body.shopId === null ? "/shop/new" : "/");
      return;
    }
    setSubmitting(false);
    if (res.status === 401) setError("이메일 또는 비밀번호가 올바르지 않습니다.");
    else setError("로그인에 실패했습니다. 잠시 후 다시 시도하세요.");
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-sm flex-col justify-center gap-4 p-6">
      <h1 className="text-xl font-bold">Owner 로그인</h1>
      <form onSubmit={submit} className="flex flex-col gap-3">
        <input
          type="email"
          required
          placeholder="이메일"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="rounded border p-2"
        />
        <input
          type="password"
          required
          placeholder="비밀번호"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="rounded border p-2"
        />
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={submitting}
          className="rounded bg-black p-2 text-white disabled:opacity-50"
        >
          로그인
        </button>
      </form>
      <p className="text-sm text-gray-500">
        계정이 없나요?{" "}
        <Link href="/signup" className="underline">
          가입하기
        </Link>
      </p>
    </main>
  );
}
