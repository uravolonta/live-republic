"use client";

import { useEffect, useState } from "react";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

/** Preview에서 Server 연결을 확인하기 위한 상태 표시. */
export function ServerStatus() {
  const [status, setStatus] = useState<"loading" | "ok" | "unreachable">(
    "loading",
  );

  useEffect(() => {
    fetch(`${API_URL}/api/status`)
      .then((res) => (res.ok ? res.json() : Promise.reject()))
      .then((body: { status: string }) =>
        setStatus(body.status === "ok" ? "ok" : "unreachable"),
      )
      .catch(() => setStatus("unreachable"));
  }, []);

  return (
    <p data-testid="server-status" className="text-sm">
      Server:{" "}
      {status === "loading" && "확인 중…"}
      {status === "ok" && <span className="text-green-600">연결됨</span>}
      {status === "unreachable" && (
        <span className="text-red-600">연결되지 않음</span>
      )}
    </p>
  );
}
