import type { AppProps } from "next/app";
import { client } from '@/lib/client/client.gen';

export default function App({ Component, pageProps }: AppProps) {
  return <Component {...pageProps} />;
}

client.setConfig({
  baseUrl: 'http://localhost:8000',
});