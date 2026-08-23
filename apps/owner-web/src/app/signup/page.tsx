"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, type Me } from "@/lib/api";

export default function SignupPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    const res = await api<Me>("/api/auth/signup", {
      method: "POST",
      json: { email, password, name },
    });
    if (res.status === 201) {
      // 가입 직후 자동 로그인하고 Shop 생성으로 이동한다.
      await api("/api/auth/login", { method: "POST", json: { email, password } });
      router.replace("/shop/new");
      return;
    }
    setSubmitting(false);
    if (res.status === 409) setError("이미 가입된 이메일입니다.");
    else if (res.status === 400)
      setError("입력값을 확인하세요. 비밀번호는 8자 이상이어야 합니다.");
    else setError("가입에 실패했습니다. 잠시 후 다시 시도하세요.");
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-sm flex-col justify-center gap-4 p-6">
      <h1 className="text-xl font-bold">Owner 가입</h1>
      <form onSubmit={submit} className="flex flex-col gap-3">
        <input
          type="text"
          required
          placeholder="이름"
          value={name}
          onChange={(e) => setName(e.target.value)}
          className="rounded border p-2"
        />
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
          minLength={8}
          placeholder="비밀번호 (8자 이상)"
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
          가입하기
        </button>
      </form>
      <p className="text-sm text-gray-500">
        이미 계정이 있나요?{" "}
        <Link href="/login" className="underline">
          로그인
        </Link>
      </p>
    </main>
  );
}
