import { ServerStatus } from "./server-status";

export default function Home() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 p-8">
      <h1 className="text-2xl font-bold">Live Republic — Customer Web</h1>
      <p className="text-sm text-gray-500">
        Live를 시청하고 상품을 주문하는 화면의 골격입니다.
      </p>
      <ServerStatus />
    </main>
  );
}
