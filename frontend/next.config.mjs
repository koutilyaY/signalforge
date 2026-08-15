/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Standalone output copies only the files the server actually needs, which
  // keeps the runtime image small instead of shipping all of node_modules.
  output: "standalone",
  poweredByHeader: false,
};

export default nextConfig;
