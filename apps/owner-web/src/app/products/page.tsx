"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, type Product } from "@/lib/api";

/** 상품 목록. SKU별 판매 가능 수량의 합을 함께 보여준다. */
export default function ProductsPage() {
  const router = useRouter();
  const [products, setProducts] = useState<Product[] | null>(null);
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    (async () => {
      const res = await api<Product[]>("/api/products");
      if (res.status === 401) {
        router.replace("/login");
        return;
      }
      if (res.status !== 200 || !res.body) {
        setLoadError(true);
        return;
      }
      setProducts(res.body);
    })();
  }, [router]);

  if (loadError) {
    return (
      <main className="flex min-h-screen flex-col items-center justify-center gap-3 p-8">
        <p className="text-sm text-red-600">상품을 불러오지 못했습니다.</p>
        <button
          onClick={() => window.location.reload()}
          className="rounded border px-4 py-2 text-sm"
        >
          다시 시도
        </button>
      </main>
    );
  }

  if (products === null) {
    return <main className="p-8 text-sm text-gray-500">불러오는 중…</main>;
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col gap-4 p-6">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-bold">상품 관리</h1>
        <Link href="/" className="text-sm text-gray-500 underline">
          운영 화면으로
        </Link>
      </header>

      <Link
        href="/products/new"
        className="rounded-lg bg-black px-4 py-2 text-center text-white"
      >
        새 상품 등록
      </Link>

      {products.length === 0 ? (
        <p className="text-sm text-gray-500">
          등록된 상품이 없습니다. Live에서 판매할 상품을 등록하세요.
        </p>
      ) : (
        <ul className="flex flex-col gap-3">
          {products.map((p) => {
            const totalAvailable = p.skus.reduce((sum, s) => sum + s.available, 0);
            return (
              <li key={p.id}>
                <Link
                  href={`/products/${p.id}`}
                  className="flex items-center justify-between rounded-lg border p-4"
                >
                  <div>
                    <p className="font-semibold">{p.name}</p>
                    <p className="text-sm text-gray-500">
                      {p.price.toLocaleString()}원 · SKU {p.skus.length}개
                    </p>
                  </div>
                  <p className="text-sm">
                    판매 가능 <span className="font-semibold">{totalAvailable}</span>
                  </p>
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </main>
  );
}
