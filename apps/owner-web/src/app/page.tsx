"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, type Me, type Shop } from "@/lib/api";
import { ServerStatus } from "./server-status";

/** Owner 운영 홈. 로그인·Shop 유무에 따라 필요한 화면으로 안내한다. */
export default function Home() {
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const [shop, setShop] = useState<Shop | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [logoutFailed, setLogoutFailed] = useState(false);

  useEffect(() => {
    (async () => {
      const meRes = await api<Me>("/api/auth/me");
      // 로그인 이동은 401에만 적용한다. 통신 단절·서버 오류는 불러오기 오류로 처리해
      // 서버 장애를 인증 문제로 오인하지 않게 한다.
      if (meRes.status === 401) {
        router.replace("/login");
        return;
      }
      if (meRes.status !== 200 || !meRes.body) {
        setLoadError(true);
        setLoading(false);
        return;
      }
      setMe(meRes.body);
      if (meRes.body.shopId === null) {
        router.replace("/shop/new");
        return;
      }
      const shopRes = await api<Shop>("/api/shops/my");
      if (shopRes.status !== 200 || !shopRes.body) {
        setLoadError(true);
        setLoading(false);
        return;
      }
      setShop(shopRes.body);
      setLoading(false);
    })();
  }, [router]);

  const logout = useCallback(async () => {
    // 서버 세션 무효화가 확인된 경우에만 로그아웃으로 처리한다.
    const res = await api("/api/auth/logout", { method: "POST" });
    if (res.status === 204) {
      router.replace("/login");
    } else {
      setLogoutFailed(true);
    }
  }, [router]);

  if (loadError) {
    return (
      <main className="flex min-h-screen flex-col items-center justify-center gap-3 p-8">
        <p className="text-sm text-red-600">정보를 불러오지 못했습니다.</p>
        <button
          onClick={() => window.location.reload()}
          className="rounded border px-4 py-2 text-sm"
        >
          다시 시도
        </button>
      </main>
    );
  }

  if (loading || !me || !shop) {
    return <main className="p-8 text-sm text-gray-500">불러오는 중…</main>;
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col gap-6 p-6">
      <header className="flex items-center justify-between">
        <h1 className="text-xl font-bold">{shop.name}</h1>
        <button onClick={logout} className="text-sm text-gray-500 underline">
          로그아웃
        </button>
      </header>
      {logoutFailed && (
        <p className="text-sm text-red-600">
          로그아웃에 실패했습니다. 잠시 후 다시 시도하세요.
        </p>
      )}
      <p className="text-sm text-gray-500">{me.name} 님, Shop 운영 화면입니다.</p>

      <section className="rounded-lg border p-4">
        <h2 className="mb-2 font-semibold">입금 계좌</h2>
        {shop.bankName ? (
          <p className="text-sm">
            {shop.bankName} {shop.bankAccountNumber} ({shop.bankAccountHolder})
          </p>
        ) : (
          <p className="text-sm text-amber-600">
            아직 설정되지 않았습니다. Customer에게 안내할 계좌를 등록하세요.
          </p>
        )}
      </section>

      <section className="rounded-lg border p-4">
        <h2 className="mb-2 font-semibold">기본 배송정보</h2>
        {shop.courierName || shop.baseShippingFee !== null ? (
          <p className="text-sm">
            {shop.courierName ?? "택배사 미지정"} · 기본 배송비{" "}
            {shop.baseShippingFee !== null
              ? `${shop.baseShippingFee.toLocaleString()}원`
              : "미지정"}
          </p>
        ) : (
          <p className="text-sm text-amber-600">아직 설정되지 않았습니다.</p>
        )}
      </section>

      <Link
        href="/settings"
        className="rounded-lg bg-black px-4 py-2 text-center text-white"
      >
        Shop 설정 수정
      </Link>
      <ServerStatus />
    </main>
  );
}
