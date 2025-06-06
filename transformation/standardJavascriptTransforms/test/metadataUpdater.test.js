const { main } = require("../src/metadataUpdater.js");

let templateDescription = {
  template: {
    mappings: {
      properties: {
        timestamp: {
          type: "date",
        },
        ecs: {
          properties: {
            version: {
              ignore_above: 1024,
              type: "keyword",
            },
          },
        },
        data_stream: {
          properties: {
            namespace: {
              type: "constant_keyword",
            },
            dataset: {
              type: "constant_keyword",
            },
          },
        },
        host: {
          type: "object",
        },
      },
    },
  },
  version: 1,
  _meta: {
    managed: true,
    description: "general mapping conventions for data streams",
  },
};

let indexDescription = {
  mappings: [
    {
      _doc: {
        dynamic: "strict",
        properties: {
          all_modifiers: {
            type: "flattened",
          },
          creator: {
            properties: {
              alias: {
                type: "keyword",
                index: false,
              },
              avatar_url: {
                properties: {
                  web: {
                    type: "text",
                    index: false,
                    fields: {
                      keyword: {
                        type: "keyword",
                        ignore_above: 2048,
                      },
                    },
                  },
                },
              },
              email: {
                type: "text",
                fields: {
                  keyword: {
                    type: "keyword",
                    ignore_above: 256,
                  },
                },
              },
              name: {
                type: "text",
                fields: {
                  keyword: {
                    type: "keyword",
                    ignore_above: 256,
                  },
                },
              },
              profile_link: {
                type: "flattened",
                index: false,
              },
              uuid: {
                type: "text",
                fields: {
                  keyword: {
                    type: "keyword",
                    ignore_above: 256,
                  },
                },
              },
            },
          },
        },
      },
    },
  ],
};

const flattenToFlatObjectConfig = {
  rules: [
    {
      when: { type: "flattened" },
      set: { type: "flat_object" },
      remove: ["index"],
    },
  ],
};

const constantKeywordToKeywordConfig = {
  rules: [
    {
      when: { type: "constant_keyword" },
      set: { type: "keyword" },
    },
  ],
};

const multipleCriteriaConfig = {
  rules: [
    {
      when: { type: "keyword", ignore_above: 2048 },
      set: { type: "keyword", ignore_above: 999 },
    },
  ],
};

function wrapAsDoc(obj) {
  return { type: "index", name: "doc_name", body: obj };
}

function clone(obj) {
  return JSON.parse(JSON.stringify(obj));
}

describe("Metadata Updater", () => {
  test("null context throws", () => {
    expect(() => main(null)).toThrow();
  });

  test("Non conforming doc is ignored", () => {
    const original = clone(templateDescription);
    const instance = main(constantKeywordToKeywordConfig);
    const result = instance(templateDescription);
    expect(result).toStrictEqual(original);
  });

  test("Constant_keywords are replaced", () => {
    const instance = main(constantKeywordToKeywordConfig);
    const result = instance(wrapAsDoc(clone(templateDescription))).body;

    expect(result).not.toStrictEqual(templateDescription);
  });

  test("Flattened are replaced", () => {
    const instance = main(flattenToFlatObjectConfig);
    const result = instance(wrapAsDoc(clone(indexDescription))).body;
    expect(result).not.toStrictEqual(indexDescription);
  });

  test("Multiple criteria are matched", () => {
    const instance = main(multipleCriteriaConfig);
    const result = instance(wrapAsDoc(clone(indexDescription))).body;
    expect(result).not.toStrictEqual(indexDescription);
    const resultAsString = JSON.stringify(result, null, 2);
    expect(resultAsString).toEqual(expect.not.stringContaining("2048"));
    expect(resultAsString).toEqual(expect.stringContaining("999"));
  });
});

describe("Metadata updater internals", () => {
  let simpleConfig = {
    rules: [{ when: { a: 1 }, set: { a: 2 }, remove: ["c"] }],
  };

  test("Decomposes arrays", () => {
    const updaterInstance = main(simpleConfig);

    const testObj = [{ a: 1 }, { a: 1, c: 1 }];
    const result = updaterInstance(wrapAsDoc(testObj)).body;

    expect(result).toEqual([{ a: 2 }, { a: 2 }]);
  });

  test("Decomposes Maps", () => {
    const updaterInstance = main(simpleConfig);

    const testObj = new Map([
      ["a", 1],
      ["c", 1],
    ]);
    const result = updaterInstance(wrapAsDoc(testObj)).body;
    expect(result).toEqual(new Map([["a", 2]]));
  });

  test("Decomposes Objects", () => {
    const updaterInstance = main(simpleConfig);

    const testObj = { a: 1, c: 1 };
    const result = updaterInstance(wrapAsDoc(testObj)).body;
    expect(result).toEqual({ a: 2 });
  });

  test("Removal only occurs with matches", () => {
    const updaterInstance = main(simpleConfig);

    const testObj = [{ c: 1 }, { a: 1, c: 1 }];
    const result = updaterInstance(wrapAsDoc(testObj)).body;

    expect(result).toEqual([{ c: 1 }, { a: 2 }]);
  });
});
