import type { NextConfig } from "next";

// Server API를 같은 Origin으로 노출하는 Proxy (Owner Web과 같은 규칙).
// CORS가 필요 없고, 응답의 Cache-Control이 CDN 캐시에 그대로 적용된다.
const API_PROXY_TARGET = process.env.API_PROXY_TARGET ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${API_PROXY_TARGET}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;
