"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, errorMessage, type Streamer } from "@/lib/api";

/** 방송용 Streamer 서브계정 관리. 임시 비밀번호는 생성 시 한 번만 표시된다. */
export default function StreamersPage() {
  const router = useRouter();
  const [streamers, setStreamers] = useState<Streamer[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [loginId, setLoginId] = useState("");
  const [temporaryPassword, setTemporaryPassword] = useState("");
  const [name, setName] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    const res = await api<Streamer[]>("/api/streamers");
    if (res.status === 401) {
      router.replace("/login");
      return;
    }
    if (res.status !== 200 || !res.body) {
      setLoadError(true);
      return;
    }
    setStreamers(res.body);
  }, [router]);

  useEffect(() => {
    void Promise.resolve().then(load);
  }, [load]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setMessage(null);
    setError(null);
    setSubmitting(true);
    const res = await api<Streamer>("/api/streamers", {
      method: "POST",
      json: { loginId, temporaryPassword, name },
    });
    setSubmitting(false);
    if (res.status === 201 && res.body) {
      setMessage(
        `'${res.body.loginId}' 계정이 생성되었습니다. 임시 비밀번호를 Streamer에게 전달하세요 — 다시 조회할 수 없습니다.`,
      );
      setLoginId("");
      setTemporaryPassword("");
      setName("");
      await load();
    } else if (res.status === 401) {
      router.replace("/login");
    } else if (res.status === 409) {
      setError("이미 사용 중인 로그인 ID입니다.");
    } else if (res.status === 400) {
      setError(errorMessage(res.body) ?? "입력값을 확인하세요.");
    } else {
      setError("일시적인 오류로 생성하지 못했습니다. 잠시 후 다시 시도하세요.");
    }
  }

  if (loadError) {
    return (
      <main className="flex min-h-screen flex-col items-center justify-center gap-3 p-8">
        <p className="text-sm text-red-600">정보를 불러오지 못했습니다.</p>
        <button onClick={() => window.location.reload()} className="rounded border px-4 py-2 text-sm">
          다시 시도
        </button>
      </main>
    );
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col gap-4 p-6">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-bold">Streamer 계정</h1>
        <Link href="/" className="text-sm text-gray-500 underline">
          운영 화면으로
        </Link>
      </header>

      <form onSubmit={submit} className="flex flex-col gap-3 rounded-lg border p-4">
        <h2 className="font-semibold">새 Streamer 계정</h2>
        <input
          type="text"
          required
          pattern="[a-z0-9._-]{4,50}"
          title="영소문자, 숫자, '.', '_', '-'로 4~50자"
          placeholder="로그인 ID (영소문자·숫자, 4자 이상)"
          value={loginId}
          onChange={(e) => setLoginId(e.target.value)}
          className="rounded border p-2"
        />
        <input
          type="text"
          required
          minLength={8}
          maxLength={72}
          pattern="[!-~]{8,72}"
          placeholder="임시 비밀번호 (8자 이상)"
          value={temporaryPassword}
          onChange={(e) => setTemporaryPassword(e.target.value)}
          className="rounded border p-2"
        />
        <input
          type="text"
          required
          maxLength={100}
          placeholder="이름"
          value={name}
          onChange={(e) => setName(e.target.value)}
          className="rounded border p-2"
        />
        {message && <p className="text-sm text-green-600">{message}</p>}
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={submitting}
          className="rounded bg-black p-2 text-white disabled:opacity-50"
        >
          계정 생성
        </button>
        <p className="text-xs text-gray-500">
          Streamer는 임시 비밀번호로 처음 로그인한 뒤 비밀번호를 반드시 변경해야 합니다.
        </p>
      </form>

      <section className="flex flex-col gap-2 rounded-lg border p-4">
        <h2 className="font-semibold">계정 목록</h2>
        {streamers === null ? (
          <p className="text-sm text-gray-500">불러오는 중…</p>
        ) : streamers.length === 0 ? (
          <p className="text-sm text-gray-500">아직 Streamer 계정이 없습니다.</p>
        ) : (
          <ul className="flex flex-col gap-2 text-sm">
            {streamers.map((s) => (
              <li key={s.userId} className="flex items-center justify-between rounded border p-3">
                <span>
                  <b>{s.name}</b> · {s.loginId}
                </span>
                <span className={s.mustChangePassword ? "text-amber-600" : "text-green-600"}>
                  {s.mustChangePassword ? "임시 비밀번호 상태" : "사용 중"}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
