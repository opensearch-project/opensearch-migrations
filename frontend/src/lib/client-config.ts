import { client } from "@/generated/api/client.gen";

client.setConfig({
  baseUrl: process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://127.0.0.1:8000",
});
