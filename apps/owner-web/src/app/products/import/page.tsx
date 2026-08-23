"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { errorMessage } from "@/lib/api";

type RowError = { row: number; message: string };
type UploadSummary = { createdProducts: number; createdSkus: number };

/** Excel 상품 일괄등록. 모든 행이 유효할 때만 등록된다 (부분 등록 없음). */
export default function ProductImportPage() {
  const router = useRouter();
  const [file, setFile] = useState<File | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [summary, setSummary] = useState<UploadSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [rowErrors, setRowErrors] = useState<RowError[]>([]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!file) return;
    setSubmitting(true);
    setSummary(null);
    setError(null);
    setRowErrors([]);

    const form = new FormData();
    form.append("file", file);
    let res: Response;
    try {
      res = await fetch("/api/products/excel", { method: "POST", body: form });
    } catch {
      setSubmitting(false);
      setError("일시적인 오류로 업로드하지 못했습니다. 잠시 후 다시 시도하세요.");
      return;
    }
    setSubmitting(false);

    let body: unknown = null;
    try {
      body = await res.json();
    } catch {
      body = null;
    }
    if (res.status === 201 && body) {
      setSummary(body as UploadSummary);
    } else if (res.status === 401) {
      router.replace("/login");
    } else if (res.status === 400 && body) {
      const parsed = body as { message?: string; errors?: RowError[] };
      setError(parsed.message ?? errorMessage(body) ?? "입력값을 확인하세요.");
      setRowErrors(parsed.errors ?? []);
    } else {
      setError("일시적인 오류로 업로드하지 못했습니다. 잠시 후 다시 시도하세요.");
    }
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col gap-4 p-6">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-bold">Excel 일괄 등록</h1>
        <Link href="/products" className="text-sm text-gray-500 underline">
          상품 목록으로
        </Link>
      </header>

      <section className="flex flex-col gap-2 rounded-lg border p-4 text-sm">
        <p>
          1. 템플릿을 내려받아 <b>상품</b> 시트에 작성하세요. 한 행이 SKU 하나이며, 같은
          상품명의 행은 같은 상품으로 묶입니다.
        </p>
        <p>2. 모든 행이 유효할 때만 등록됩니다 — 오류가 있으면 행별 이유를 보여드립니다.</p>
        <a
          href="/api/products/excel/template"
          className="w-fit rounded border px-3 py-2"
          download
        >
          템플릿 내려받기 (.xlsx)
        </a>
      </section>

      <form onSubmit={submit} className="flex flex-col gap-3 rounded-lg border p-4">
        <input
          type="file"
          required
          accept=".xlsx"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          className="text-sm"
        />
        <button
          type="submit"
          disabled={submitting || !file}
          className="rounded bg-black p-2 text-white disabled:opacity-50"
        >
          {submitting ? "업로드 중…" : "업로드"}
        </button>
      </form>

      {summary && (
        <div className="flex flex-col gap-2 rounded-lg border border-green-600 p-4 text-sm">
          <p className="text-green-600">
            상품 {summary.createdProducts}개, SKU {summary.createdSkus}개가 등록되었습니다.
          </p>
          <Link href="/products" className="underline">
            상품 목록에서 확인하기
          </Link>
        </div>
      )}

      {error && (
        <div className="flex flex-col gap-2 rounded-lg border border-red-600 p-4 text-sm">
          <p className="text-red-600">{error}</p>
          {rowErrors.length > 0 && (
            <ul className="flex flex-col gap-1">
              {rowErrors.map((re, i) => (
                <li key={i}>
                  <span className="font-semibold">{re.row}행</span> — {re.message}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </main>
  );
}
