import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  allowedDevOrigins: [
    '127.0.0.1',
    'localhost',
    '*.ngrok-free.app',
    '*.ngrok-free.dev',
    '*.ngrok.io',
  ],
  devIndicators: false,
};

export default nextConfig;
