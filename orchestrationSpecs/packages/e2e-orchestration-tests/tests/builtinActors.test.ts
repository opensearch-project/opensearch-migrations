import { BUILTIN_ACTOR_NAMES, NotImplementedActorError, builtinActors } from "../src/builtinActors";
import { ActorRegistry } from "../src/actors";

describe("builtinActors", () => {
    it("lists the actor names the design doc's example specs reference", () => {
        expect(BUILTIN_ACTOR_NAMES).toEqual(
            expect.arrayContaining([
                "delete-target-indices",
                "delete-source-snapshots",
            ]),
        );
    });

    it("produces one actor per name, each with the matching name", () => {
        const actors = builtinActors();
        expect(actors.map((a) => a.name).sort()).toEqual([...BUILTIN_ACTOR_NAMES].sort());
    });

    it("each stub throws NotImplementedActorError when invoked", async () => {
        for (const actor of builtinActors()) {
            await expect(actor.run({} as never)).rejects.toBeInstanceOf(
                NotImplementedActorError,
            );
        }
    });

    it("registers cleanly into a fresh ActorRegistry", () => {
        const reg = new ActorRegistry();
        for (const a of builtinActors()) reg.register(a);
        for (const name of BUILTIN_ACTOR_NAMES) {
            expect(reg.has(name)).toBe(true);
        }
    });

    it("can be layered with extra actors without collision", () => {
        const reg = new ActorRegistry();
        for (const a of builtinActors()) reg.register(a);
        reg.register({ name: "extra-one", run: async () => {} });
        expect(reg.has("extra-one")).toBe(true);
        expect(reg.has("delete-target-indices")).toBe(true);
    });
});
