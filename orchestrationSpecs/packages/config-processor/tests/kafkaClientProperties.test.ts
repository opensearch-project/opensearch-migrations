import {renderJavaProperties} from "../src/kafkaClientProperties";

function splitAtSeparator(line: string): [string, string] {
    let escaped = false;
    for (let i = 0; i < line.length; i++) {
        const ch = line[i];
        if (escaped) {
            escaped = false;
            continue;
        }
        if (ch === "\\") {
            escaped = true;
            continue;
        }
        if (ch === "=") {
            return [line.slice(0, i), line.slice(i + 1)];
        }
    }
    throw new Error(`No Java properties separator found in rendered line: ${line}`);
}

function decodeEscapedJavaPropertiesPart(input: string): string {
    let out = "";
    for (let i = 0; i < input.length; i++) {
        const ch = input[i];
        if (ch !== "\\") {
            out += ch;
            continue;
        }

        const escaped = input[++i];
        if (escaped === "n") out += "\n";
        else if (escaped === "r") out += "\r";
        else if (escaped === "t") out += "\t";
        else if (escaped === "f") out += "\f";
        else if (escaped === "u") {
            const hex = input.slice(i + 1, i + 5);
            out += String.fromCharCode(parseInt(hex, 16));
            i += 4;
        } else {
            out += escaped;
        }
    }
    return out;
}

function decodeRenderedProperties(propertiesText: string): Record<string, string> {
    return Object.fromEntries(propertiesText.trimEnd().split("\n").filter(Boolean).map(line => {
        const [key, value] = splitAtSeparator(line);
        return [
            decodeEscapedJavaPropertiesPart(key),
            decodeEscapedJavaPropertiesPart(value),
        ];
    }));
}

describe("Kafka client properties rendering", () => {
    it("renders deterministic Java properties text with escaped keys and scalar values", () => {
        const properties = {
            " leading key": " leading value",
            "compression.type": "lz4",
            "contains:separator": "value=with:separators",
            "emoji": "snowman-\u2603",
            "line\nbreak": "tab\tand\nnewline",
            "truthy": true,
            "max.request.size": 8388608,
            "#comment-like": "!value-like",
        };

        const rendered = renderJavaProperties(properties);

        expect(rendered).toBe([
            "\\ leading\\ key=\\ leading value",
            "\\#comment-like=\\!value-like",
            "compression.type=lz4",
            "contains\\:separator=value=with:separators",
            "emoji=snowman-\\u2603",
            "line\\nbreak=tab\\tand\\nnewline",
            "max.request.size=8388608",
            "truthy=true",
            "",
        ].join("\n"));
        expect(decodeRenderedProperties(rendered)).toEqual(
            Object.fromEntries(Object.entries(properties).map(([key, value]) => [key, String(value)]))
        );
    });
});
