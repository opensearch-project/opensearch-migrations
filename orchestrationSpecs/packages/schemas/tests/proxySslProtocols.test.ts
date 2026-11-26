import {USER_PROXY_OPTIONS} from "../src/userSchemas";

describe("proxy sslProtocols", () => {
    test("defaults to TLSv1.3,TLSv1.2", () => {
        expect(USER_PROXY_OPTIONS.parse({listenPort: 9201}).sslProtocols).toBe("TLSv1.3,TLSv1.2");
    });

    test.each([
        "TLSv1.3",
        "TLSv1.2",
        "TLSv1.3,TLSv1.2",
        "TLSv1.3, TLSv1.2, TLSv1.1",
    ])("accepts %s", (value) => {
        expect(USER_PROXY_OPTIONS.safeParse({listenPort: 9201, sslProtocols: value}).success).toBe(true);
    });

    test.each([
        "",
        "SSLv3",
        "TLSv1.3,",
        "TLSv1.3,TLSv9",
        "tlsv1.2",
    ])("rejects '%s'", (value) => {
        expect(USER_PROXY_OPTIONS.safeParse({listenPort: 9201, sslProtocols: value}).success).toBe(false);
    });
});
