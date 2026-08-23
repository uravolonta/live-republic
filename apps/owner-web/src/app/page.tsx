import { ServerStatus } from "./server-status";

export default function Home() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 p-8">
      <h1 className="text-2xl font-bold">Live Republic — Owner Web</h1>
      <p className="text-sm text-gray-500">
        Shop, 상품, Live, 주문과 배송을 운영하는 화면의 골격입니다.
      </p>
      <ServerStatus />
    </main>
  );
}
