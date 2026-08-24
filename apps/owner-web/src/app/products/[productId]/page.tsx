"use client";

import { useCallback, useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { api, errorMessage, type Product, type Sku } from "@/lib/api";

/** 상품 상세: 기본정보 수정과 SKU별 재고 관리. */
export default function ProductDetailPage() {
  const router = useRouter();
  const params = useParams<{ productId: string }>();
  const productId = params.productId;

  const [product, setProduct] = useState<Product | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [name, setName] = useState("");
  const [price, setPrice] = useState("");
  const [description, setDescription] = useState("");
  const [infoMessage, setInfoMessage] = useState<string | null>(null);
  const [infoError, setInfoError] = useState<string | null>(null);
  const [savingInfo, setSavingInfo] = useState(false);
  const [onHandInputs, setOnHandInputs] = useState<Record<number, string>>({});
  const [skuMessage, setSkuMessage] = useState<string | null>(null);
  const [skuError, setSkuError] = useState<string | null>(null);
  const [savingSkuId, setSavingSkuId] = useState<number | null>(null);
  const [groupsForm, setGroupsForm] = useState<{ name: string; optionsText: string }[]>([]);
  const [structureMessage, setStructureMessage] = useState<string | null>(null);
  const [structureError, setStructureError] = useState<string | null>(null);
  const [savingStructure, setSavingStructure] = useState(false);

  const load = useCallback(async () => {
    const res = await api<Product>(`/api/products/${productId}`);
    if (res.status === 401) {
      router.replace("/login");
      return;
    }
    if (res.status === 404) {
      router.replace("/products");
      return;
    }
    if (res.status !== 200 || !res.body) {
      setLoadError(true);
      return;
    }
    setProduct(res.body);
    setName(res.body.name);
    setPrice(String(res.body.price));
    setDescription(res.body.description ?? "");
    setOnHandInputs(
      Object.fromEntries(res.body.skus.map((s) => [s.id, String(s.onHand)])),
    );
    setGroupsForm(
      (res.body.optionGroups ?? []).map((g) => ({
        name: g.name,
        optionsText: g.options.join(", "),
      })),
    );
  }, [productId, router]);

  useEffect(() => {
    // load는 첫 await(fetch) 이후에만 상태를 변경하므로 동기 setState가 아니다.
    void Promise.resolve().then(load);
  }, [load]);

  async function saveInfo(e: React.FormEvent) {
    e.preventDefault();
    setInfoMessage(null);
    setInfoError(null);
    setSavingInfo(true);
    const res = await api<Product>(`/api/products/${productId}`, {
      method: "PUT",
      json: { name, price: Number(price), description },
    });
    setSavingInfo(false);
    if (res.status === 200 && res.body) {
      setProduct(res.body);
      setInfoMessage("저장되었습니다.");
    } else if (res.status === 401) {
      router.replace("/login");
    } else if (res.status === 400) {
      setInfoError(errorMessage(res.body) ?? "입력값을 확인하세요.");
    } else {
      setInfoError("일시적인 오류로 저장하지 못했습니다. 잠시 후 다시 시도하세요.");
    }
  }

  async function saveOnHand(sku: Sku) {
    setSkuMessage(null);
    setSkuError(null);
    setSavingSkuId(sku.id);
    const res = await api<Sku>(
      `/api/products/${productId}/skus/${sku.id}/inventory`,
      { method: "PUT", json: { onHand: Number(onHandInputs[sku.id] ?? "0") } },
    );
    setSavingSkuId(null);
    if (res.status === 200 && res.body) {
      const updated = res.body;
      setProduct((prev) =>
        prev
          ? { ...prev, skus: prev.skus.map((s) => (s.id === updated.id ? updated : s)) }
          : prev,
      );
      setSkuMessage(`${updated.optionLabel} 수량이 저장되었습니다.`);
    } else if (res.status === 401) {
      router.replace("/login");
    } else if (res.status === 400) {
      setSkuError(errorMessage(res.body) ?? "수량을 확인하세요.");
    } else {
      setSkuError("일시적인 오류로 저장하지 못했습니다. 잠시 후 다시 시도하세요.");
    }
  }

  async function saveStructure(e: React.FormEvent) {
    e.preventDefault();
    setStructureMessage(null);
    setStructureError(null);
    const optionGroups = groupsForm.map((g) => ({
      name: g.name.trim(),
      options: g.optionsText
        .split(",")
        .map((o) => o.trim())
        .filter((o) => o.length > 0),
    }));
    if (optionGroups.some((g) => g.name === "" || g.options.length === 0)) {
      setStructureError("Option Group 이름과 쉼표로 구분한 Option을 입력하세요.");
      return;
    }
    setSavingStructure(true);
    const res = await api<Product>(`/api/products/${productId}/options`, {
      method: "PUT",
      json: { optionGroups },
    });
    setSavingStructure(false);
    if (res.status === 200 && res.body) {
      setProduct(res.body);
      setOnHandInputs(
        Object.fromEntries(res.body.skus.map((s) => [s.id, String(s.onHand)])),
      );
      setGroupsForm(
        (res.body.optionGroups ?? []).map((g) => ({
          name: g.name,
          optionsText: g.options.join(", "),
        })),
      );
      setStructureMessage("Option 구조가 변경되었습니다. 사라진 조합은 보관되고 이력이 유지됩니다.");
    } else if (res.status === 401) {
      router.replace("/login");
    } else if (res.status === 400 || res.status === 409) {
      setStructureError(errorMessage(res.body) ?? "입력값을 확인하세요.");
    } else {
      setStructureError("일시적인 오류로 변경하지 못했습니다. 잠시 후 다시 시도하세요.");
    }
  }

  async function deleteProduct() {
    if (
      !window.confirm(
        "이 상품을 삭제할까요? 판매 화면에서 숨겨지며, 이미 발생한 주문·판매 이력은 보존됩니다.",
      )
    ) {
      return;
    }
    setStructureError(null);
    const res = await api(`/api/products/${productId}`, { method: "DELETE" });
    if (res.status === 204) {
      router.replace("/products");
    } else if (res.status === 401) {
      router.replace("/login");
    } else if (res.status === 409) {
      setStructureError(errorMessage(res.body) ?? "확보 수량이 남아 있어 삭제할 수 없습니다.");
    } else {
      setStructureError("일시적인 오류로 삭제하지 못했습니다. 잠시 후 다시 시도하세요.");
    }
  }

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

  if (!product) {
    return <main className="p-8 text-sm text-gray-500">불러오는 중…</main>;
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col gap-6 p-6">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-bold">{product.name}</h1>
        <Link href="/products" className="text-sm text-gray-500 underline">
          상품 목록으로
        </Link>
      </header>

      <form onSubmit={saveInfo} className="flex flex-col gap-3 rounded-lg border p-4">
        <h2 className="font-semibold">기본 정보</h2>
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
          설명
          <textarea
            maxLength={2000}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="rounded border p-2"
            rows={3}
          />
        </label>
        {infoMessage && <p className="text-sm text-green-600">{infoMessage}</p>}
        {infoError && <p className="text-sm text-red-600">{infoError}</p>}
        <button
          type="submit"
          disabled={savingInfo}
          className="rounded bg-black p-2 text-white disabled:opacity-50"
        >
          기본 정보 저장
        </button>
      </form>

      <section className="flex flex-col gap-3 rounded-lg border p-4">
        <h2 className="font-semibold">SKU별 재고</h2>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-gray-500">
                <th className="py-2">Option</th>
                <th className="py-2">보유 (On Hand)</th>
                <th className="py-2 text-right">확보 (입금대기)</th>
                <th className="py-2 text-right">판매 확정</th>
                <th className="py-2 text-right">판매 가능</th>
                <th className="py-2"></th>
              </tr>
            </thead>
            <tbody>
              {product.skus.map((sku) => (
                <tr key={sku.id} className="border-b">
                  <td className="py-2">{sku.optionLabel}</td>
                  <td className="py-2">
                    <input
                      type="number"
                      min={0}
                      value={onHandInputs[sku.id] ?? ""}
                      onChange={(e) =>
                        setOnHandInputs((prev) => ({ ...prev, [sku.id]: e.target.value }))
                      }
                      className="w-20 rounded border p-1"
                    />
                  </td>
                  <td className="py-2 text-right">{sku.reserved}</td>
                  <td className="py-2 text-right">{sku.sold}</td>
                  <td className="py-2 text-right font-semibold">{sku.available}</td>
                  <td className="py-2 text-right">
                    <button
                      onClick={() => saveOnHand(sku)}
                      disabled={savingSkuId === sku.id}
                      className="rounded border px-3 py-1 disabled:opacity-50"
                    >
                      저장
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {skuMessage && <p className="text-sm text-green-600">{skuMessage}</p>}
        {skuError && <p className="text-sm text-red-600">{skuError}</p>}
      </section>

      <form onSubmit={saveStructure} className="flex flex-col gap-3 rounded-lg border p-4">
        <h2 className="font-semibold">Option 구조 변경</h2>
        <p className="text-xs text-gray-500">
          유지되는 조합의 재고는 그대로 남습니다. 사라지는 조합은 보관되어 판매 이력이
          유지되며, 확보(입금대기) 수량이 있으면 없앨 수 없습니다.
        </p>
        {groupsForm.map((g, i) => (
          <div key={i} className="flex flex-col gap-2 rounded border p-3">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium">Option Group {i + 1}</span>
              <button
                type="button"
                onClick={() => setGroupsForm((prev) => prev.filter((_, j) => j !== i))}
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
              onChange={(e) =>
                setGroupsForm((prev) =>
                  prev.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)),
                )
              }
              className="rounded border p-2"
            />
            <input
              type="text"
              placeholder="옵션들, 쉼표로 구분 (예: 빨강, 파랑)"
              value={g.optionsText}
              onChange={(e) =>
                setGroupsForm((prev) =>
                  prev.map((x, j) => (j === i ? { ...x, optionsText: e.target.value } : x)),
                )
              }
              className="rounded border p-2"
            />
          </div>
        ))}
        {groupsForm.length < 3 && (
          <button
            type="button"
            onClick={() =>
              setGroupsForm((prev) => [...prev, { name: "", optionsText: "" }])
            }
            className="rounded border p-2 text-sm"
          >
            + Option Group 추가
          </button>
        )}
        {structureMessage && <p className="text-sm text-green-600">{structureMessage}</p>}
        {structureError && <p className="text-sm text-red-600">{structureError}</p>}
        <button
          type="submit"
          disabled={savingStructure}
          className="rounded bg-black p-2 text-white disabled:opacity-50"
        >
          구조 변경 저장
        </button>
      </form>

      <button
        onClick={deleteProduct}
        className="rounded-lg border border-red-600 p-2 text-red-600"
      >
        상품 삭제
      </button>
    </main>
  );
}
