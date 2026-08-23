import type { NextConfig } from "next";

// Server API를 같은 Origin으로 노출하는 Proxy.
// Session Cookie가 First-party로 동작하고 CORS가 필요 없어진다.
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
