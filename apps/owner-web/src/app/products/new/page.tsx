"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, type Product } from "@/lib/api";

type OptionGroupForm = { name: string; optionsText: string };

/** 상품 등록. Option 조합은 서버가 SKU로 생성한다. */
export default function NewProductPage() {
  const router = useRouter();
  const [name, setName] = useState("");
  const [price, setPrice] = useState("");
  const [description, setDescription] = useState("");
  const [groups, setGroups] = useState<OptionGroupForm[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  function setGroup(index: number, patch: Partial<OptionGroupForm>) {
    setGroups((prev) => prev.map((g, i) => (i === index ? { ...g, ...patch } : g)));
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    const optionGroups = groups.map((g) => ({
      name: g.name.trim(),
      options: g.optionsText
        .split(",")
        .map((o) => o.trim())
        .filter((o) => o.length > 0),
    }));
    if (optionGroups.some((g) => g.name === "" || g.options.length === 0)) {
      setError("Option Group 이름과 쉼표로 구분한 Option을 입력하세요.");
      return;
    }

    setSubmitting(true);
    const res = await api<Product>("/api/products", {
      method: "POST",
      json: {
        name,
        price: Number(price),
        description,
        optionGroups,
      },
    });
    if (res.status === 201 && res.body) {
      // 등록 직후 SKU별 수량을 입력하도록 상세로 이동한다.
      router.replace(`/products/${res.body.id}`);
      return;
    }
    setSubmitting(false);
    if (res.status === 401) router.replace("/login");
    else if (res.status === 400) setError("입력값을 확인하세요. 중복 Option은 사용할 수 없습니다.");
    else setError("일시적인 오류로 등록하지 못했습니다. 잠시 후 다시 시도하세요.");
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col gap-4 p-6">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-bold">새 상품 등록</h1>
        <Link href="/products" className="text-sm text-gray-500 underline">
          상품 목록으로
        </Link>
      </header>

      <form onSubmit={submit} className="flex flex-col gap-4">
        <label className="flex flex-col gap-1 text-sm">
          상품명
          <input
            type="text"
            required
            maxLength={200}
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="rounded border p-2"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          판매가격 (원)
          <input
            type="number"
            required
            min={0}
            value={price}
            onChange={(e) => setPrice(e.target.value)}
            className="rounded border p-2"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          설명 (선택)
          <textarea
            maxLength={2000}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="rounded border p-2"
            rows={3}
          />
        </label>

        <fieldset className="flex flex-col gap-3 rounded-lg border p-4">
          <legend className="px-1 text-sm font-semibold">Option (선택, 최대 3그룹)</legend>
          {groups.map((g, i) => (
            <div key={i} className="flex flex-col gap-2 rounded border p-3">
              <div className="flex items-center justify-between">
                <span className="text-sm font-medium">Option Group {i + 1}</span>
                <button
                  type="button"
                  onClick={() => setGroups((prev) => prev.filter((_, j) => j !== i))}
                  className="text-sm text-red-600 underline"
                >
                  삭제
                </button>
              </div>
              <input
                type="text"
                placeholder="그룹 이름 (예: 색상)"
                maxLength={50}
                value={g.name}
                onChange={(e) => setGroup(i, { name: e.target.value })}
                className="rounded border p-2"
              />
              <input
                type="text"
                placeholder="옵션들, 쉼표로 구분 (예: 빨강, 파랑)"
                value={g.optionsText}
                onChange={(e) => setGroup(i, { optionsText: e.target.value })}
                className="rounded border p-2"
              />
            </div>
          ))}
          {groups.length < 3 && (
            <button
              type="button"
              onClick={() => setGroups((prev) => [...prev, { name: "", optionsText: "" }])}
              className="rounded border p-2 text-sm"
            >
              + Option Group 추가
            </button>
          )}
          <p className="text-xs text-gray-500">
            Option 조합마다 SKU가 만들어지고 SKU별로 재고를 관리합니다 (상품당 최대 100개
            조합). Option 이름에는 &apos;/&apos;와 &apos;,&apos;를 쓸 수 없습니다.
          </p>
        </fieldset>

        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={submitting}
          className="rounded bg-black p-2 text-white disabled:opacity-50"
        >
          등록하고 재고 입력하기
        </button>
      </form>
    </main>
  );
}
