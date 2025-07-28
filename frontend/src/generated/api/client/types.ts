import type { Auth } from "../core/auth";
import type { Client as CoreClient, Config as CoreConfig } from "../core/types";
import type { Middleware } from "./utils";

export interface Config<T extends ClientOptions = ClientOptions>
  extends Omit<RequestInit, "body" | "headers" | "method">,
    CoreConfig {
  /**
   * Base URL for all requests made by this client.
   */
  baseUrl?: T["baseUrl"];
  /**
   * Fetch API implementation. You can use this option to provide a custom
   * fetch instance.
   *
   * @default globalThis.fetch
   */
  fetch?: typeof fetch;
  /**
   * Return the response data parsed in a specified format. By default, `auto`
   * will infer the appropriate method from the `Content-Type` response header.
   * You can override this behavior with any of the {@link Body} methods.
   * Select `stream` if you don't want to parse response data at all.
   *
   * @default 'auto'
   */
  parseAs?:
    | "arrayBuffer"
    | "auto"
    | "blob"
    | "formData"
    | "json"
    | "stream"
    | "text";
  /**
   * Throw an error instead of returning it in the response?
   *
   * @default false
   */
  throwOnError?: T["throwOnError"];
}

export interface RequestOptions<
  ThrowOnError extends boolean = boolean,
  Url extends string = string,
> extends Config<{
    throwOnError: ThrowOnError;
  }> {
  /**
   * Any body that you want to add to your request.
   *
   * {@link https://developer.mozilla.org/docs/Web/API/fetch#body}
   */
  body?: unknown;
  path?: Record<string, unknown>;
  query?: Record<string, unknown>;
  /**
   * Security mechanism(s) to use for the request.
   */
  security?: ReadonlyArray<Auth>;
  url: Url;
}

export type RequestResult<
  TData = unknown,
  TError = unknown,
  ThrowOnError extends boolean = boolean,
> = ThrowOnError extends true
  ? Promise<{
      data: TData extends Record<string, unknown> ? TData[keyof TData] : TData;
      response: Response;
    }>
  : Promise<
      (
        | {
            data: TData extends Record<string, unknown>
              ? TData[keyof TData]
              : TData;
            error: undefined;
          }
        | {
            data: undefined;
            error: TError extends Record<string, unknown>
              ? TError[keyof TError]
              : TError;
          }
      ) & {
        response: Response;
      }
    >;

export interface ClientOptions {
  baseUrl?: string;
  throwOnError?: boolean;
}

type MethodFn = <
  TData = unknown,
  TError = unknown,
  ThrowOnError extends boolean = false,
>(
  options: Omit<RequestOptions<ThrowOnError>, "method">,
) => RequestResult<TData, TError, ThrowOnError>;

type RequestFn = <
  TData = unknown,
  TError = unknown,
  ThrowOnError extends boolean = false,
>(
  options: Omit<RequestOptions<ThrowOnError>, "method"> &
    Pick<Required<RequestOptions<ThrowOnError>>, "method">,
) => RequestResult<TData, TError, ThrowOnError>;

type BuildUrlFn = <
  TData extends {
    body?: unknown;
    path?: Record<string, unknown>;
    query?: Record<string, unknown>;
    url: string;
  },
>(
  options: Pick<TData, "url"> & Options<TData>,
) => string;

export type Client = CoreClient<RequestFn, Config, MethodFn, BuildUrlFn> & {
  interceptors: Middleware<Response, unknown, RequestOptions>;
};

/**
 * The `createClientConfig()` function will be called on client initialization
 * and the returned object will become the client's initial configuration.
 *
 * You may want to initialize your client this way instead of calling
 * `setConfig()`. This is useful for example if you're using Next.js
 * to ensure your client always has the correct values.
 */
export type CreateClientConfig<T extends ClientOptions = ClientOptions> = (
  override?: Config<ClientOptions & T>,
) => Config<Required<ClientOptions> & T>;

export interface TDataShape {
  body?: unknown;
  headers?: unknown;
  path?: unknown;
  query?: unknown;
  url: string;
}

type OmitKeys<T, K> = Pick<T, Exclude<keyof T, K>>;

export type Options<
  TData extends TDataShape = TDataShape,
  ThrowOnError extends boolean = boolean,
> = OmitKeys<RequestOptions<ThrowOnError>, "body" | "path" | "query" | "url"> &
  Omit<TData, "url">;

export type OptionsLegacyParser<
  TData = unknown,
  ThrowOnError extends boolean = boolean,
> = TData extends { body?: any }
  ? TData extends { headers?: any }
    ? OmitKeys<RequestOptions<ThrowOnError>, "body" | "headers" | "url"> & TData
    : OmitKeys<RequestOptions<ThrowOnError>, "body" | "url"> &
        TData &
        Pick<RequestOptions<ThrowOnError>, "headers">
  : TData extends { headers?: any }
    ? OmitKeys<RequestOptions<ThrowOnError>, "headers" | "url"> &
        TData &
        Pick<RequestOptions<ThrowOnError>, "body">
    : OmitKeys<RequestOptions<ThrowOnError>, "url"> & TData;
