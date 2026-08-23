"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { api, errorMessage, type Me } from "@/lib/api";

/** 비밀번호 변경. 임시 비밀번호 상태의 계정은 변경 전까지 다른 기능을 사용할 수 없다. */
export default function PasswordPage() {
  const router = useRouter();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!/^[!-~]{8,72}$/.test(newPassword)) {
      setError("새 비밀번호는 8자 이상의 영문 대소문자, 숫자, 특수문자만 사용할 수 있습니다.");
      return;
    }
    setSubmitting(true);
    const res = await api<Me>("/api/auth/password", {
      method: "POST",
      json: { currentPassword, newPassword },
    });
    setSubmitting(false);
    if (res.status === 200) {
      router.replace("/");
    } else if (res.status === 401) {
      router.replace("/login");
    } else if (res.status === 400) {
      setError(errorMessage(res.body) ?? "입력값을 확인하세요.");
    } else {
      setError("일시적인 오류로 변경하지 못했습니다. 잠시 후 다시 시도하세요.");
    }
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-sm flex-col justify-center gap-4 p-6">
      <h1 className="text-xl font-bold">비밀번호 변경</h1>
      <p className="text-sm text-gray-500">
        임시 비밀번호로 로그인한 경우, 비밀번호를 변경해야 다른 기능을 사용할 수 있습니다.
      </p>
      <form onSubmit={submit} className="flex flex-col gap-3">
        <input
          type="password"
          required
          placeholder="현재 비밀번호"
          value={currentPassword}
          onChange={(e) => setCurrentPassword(e.target.value)}
          className="rounded border p-2"
        />
        <input
          type="password"
          required
          minLength={8}
          maxLength={72}
          pattern="[!-~]{8,72}"
          placeholder="새 비밀번호 (영문·숫자·특수문자 8자 이상)"
          value={newPassword}
          onChange={(e) => setNewPassword(e.target.value)}
          className="rounded border p-2"
        />
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={submitting}
          className="rounded bg-black p-2 text-white disabled:opacity-50"
        >
          변경하기
        </button>
      </form>
    </main>
  );
}
