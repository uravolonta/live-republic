"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, type Shop } from "@/lib/api";

/** 입금 계좌와 기본 배송정보 설정. 저장된 값을 다시 불러와 재확인할 수 있다. */
export default function SettingsPage() {
  const router = useRouter();
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [name, setName] = useState("");
  const [bankName, setBankName] = useState("");
  const [bankAccountNumber, setBankAccountNumber] = useState("");
  const [bankAccountHolder, setBankAccountHolder] = useState("");
  const [courierName, setCourierName] = useState("");
  const [baseShippingFee, setBaseShippingFee] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    (async () => {
      const res = await api<Shop>("/api/shops/my");
      if (res.status === 401) {
        router.replace("/login");
        return;
      }
      if (res.status === 404) {
        router.replace("/shop/new");
        return;
      }
      if (res.status !== 200 || !res.body) {
        setLoadError(true);
        setLoading(false);
        return;
      }
      {
        setName(res.body.name);
        setBankName(res.body.bankName ?? "");
        setBankAccountNumber(res.body.bankAccountNumber ?? "");
        setBankAccountHolder(res.body.bankAccountHolder ?? "");
        setCourierName(res.body.courierName ?? "");
        setBaseShippingFee(
          res.body.baseShippingFee !== null ? String(res.body.baseShippingFee) : "",
        );
      }
      setLoading(false);
    })();
  }, [router]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setMessage(null);
    setError(null);
    setSubmitting(true);
    const res = await api<Shop>("/api/shops/my", {
      method: "PUT",
      json: {
        name,
        bankName,
        bankAccountNumber,
        bankAccountHolder,
        courierName,
        baseShippingFee: baseShippingFee === "" ? null : Number(baseShippingFee),
      },
    });
    setSubmitting(false);
    if (res.status === 200) {
      setMessage("저장되었습니다.");
    } else if (res.status === 401) {
      // 세션 만료: 재시도해도 성공할 수 없으므로 로그인으로 안내한다.
      router.replace("/login");
    } else if (res.status === 400) {
      setError("저장에 실패했습니다. 입력값을 확인하세요.");
    } else {
      setError("일시적인 오류로 저장하지 못했습니다. 잠시 후 다시 시도하세요.");
    }
  }

  if (loadError) {
    return (
      <main className="flex min-h-screen flex-col items-center justify-center gap-3 p-8">
        <p className="text-sm text-red-600">설정을 불러오지 못했습니다.</p>
        <button
          onClick={() => window.location.reload()}
          className="rounded border px-4 py-2 text-sm"
        >
          다시 시도
        </button>
      </main>
    );
  }

  if (loading) {
    return <main className="p-8 text-sm text-gray-500">불러오는 중…</main>;
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col gap-4 p-6">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-bold">Shop 설정</h1>
        <Link href="/" className="text-sm text-gray-500 underline">
          운영 화면으로
        </Link>
      </header>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <label className="flex flex-col gap-1 text-sm">
          Shop 이름
          <input
            type="text"
            required
            maxLength={100}
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="rounded border p-2"
          />
        </label>

        <fieldset className="flex flex-col gap-3 rounded-lg border p-4">
          <legend className="px-1 text-sm font-semibold">입금 계좌</legend>
          <input
            type="text"
            maxLength={50}
            placeholder="은행명"
            value={bankName}
            onChange={(e) => setBankName(e.target.value)}
            className="rounded border p-2"
          />
          <input
            type="text"
            maxLength={50}
            placeholder="계좌번호"
            value={bankAccountNumber}
            onChange={(e) => setBankAccountNumber(e.target.value)}
            className="rounded border p-2"
          />
          <input
            type="text"
            maxLength={50}
            placeholder="예금주"
            value={bankAccountHolder}
            onChange={(e) => setBankAccountHolder(e.target.value)}
            className="rounded border p-2"
          />
        </fieldset>

        <fieldset className="flex flex-col gap-3 rounded-lg border p-4">
          <legend className="px-1 text-sm font-semibold">기본 배송정보</legend>
          <input
            type="text"
            maxLength={50}
            placeholder="기본 택배사"
            value={courierName}
            onChange={(e) => setCourierName(e.target.value)}
            className="rounded border p-2"
          />
          <input
            type="number"
            min={0}
            placeholder="기본 배송비 (원)"
            value={baseShippingFee}
            onChange={(e) => setBaseShippingFee(e.target.value)}
            className="rounded border p-2"
          />
        </fieldset>

        {message && <p className="text-sm text-green-600">{message}</p>}
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={submitting}
          className="rounded bg-black p-2 text-white disabled:opacity-50"
        >
          저장
        </button>
      </form>
    </main>
  );
}
