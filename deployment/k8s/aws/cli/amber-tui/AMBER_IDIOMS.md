# Amber 0.6.0-alpha porting cheat-sheet (compiler-verified)

Authoritative for this port. The published docs LAG the alpha — every rule here was checked against the installed `amber 0.6.0-alpha` compiler and `amber grammar-ebnf`. When in doubt, `amber check file.ab`.

## Toolchain
- `amber check src/foo.ab` — type/parse check, no output. **Run this on every file you write.**
- `amber build src/foo.ab build/foo.sh` — compile to Bash. Add `--target bash-3.2` for the macOS floor.
- `amber test test/test_foo.ab` — run `test "..." { }` blocks. `amber test .` recurses.
- `amber run f.ab` — compile + run.

## Hard syntax rules (things that WILL bite you)
- Comments are `//` — **NOT `#`**.
- `let x = e` takes **NO type annotation**. `let x: Int = 5` is a PARSE ERROR. Types live only on `fun` signatures: `fun f(a: Int): Text { }`.
- `const X = e` for immutables. `pub const` to export a constant. **`pub let` is illegal** ("Public variables must be constants").
- Command args must be **quoted**: `echo "hi"`, not `echo hi` (bare word → parse error).
- Inside `$ ... $` commands, `{expr}` is **Amber interpolation**. So you **cannot** write raw bash `${VAR:-default}` — the `{` is captured by Amber. Use `env_var_get("VAR")` from `std/env` instead.
- A `$cmd$` that can fail MUST have a handler or be `trust`-ed, else: *"The command can potentially fail."* Use `trust $cmd$`, `$cmd$ failed { }`, `$cmd$?`, or `silent $cmd$ ...`.
- **`silent` redirects fd 2** → compiles to `cmd 2>/dev/null`. NEVER use `silent` with `test -t 2` / anything that depends on fd 2: `silent $test -t 2$` becomes `test -t 2>/dev/null 2>&1` and the `2` operand is eaten. Use bare `trust $test -t 2$` then read `status`.

## Control flow
- `if c { } else { }`.
- **if_chain replaces match/switch** (there is NO `match`/`switch` keyword):
  ```
  if {
      s == "A" { return "x" }
      s == "B" { return "y" }
      else     { return "z" }
  }
  ```
- Ternary: `let r = cond then a else b`.
- Loops: `for x in arr { }`, `for i, v in arr { }`, `for i in 0..n { }` (excl) / `0..=n` (incl), `while c { }`, infinite `loop { }`. `break` / `continue`.

## Builtins (keywords — do NOT import)
`echo e` · `len e` (UTF-8 **codepoint** count — this is why we drop the byte/codepoint bash workarounds) · `lines e` (split Text on `\n` → `[Text]`) · `status` (last command exit code) · `exit e` · `sleep e` · `nameof` · `cd/cp/mv/rm/touch`.

## Commands & capture
- Capture stdout: `let out = trust $some cmd$` or `let out = $cmd$ failed { "" }`.
- Exit code: run the command, then read `status` on the next line.
- Modifiers stack: `silent trust sudo $cmd$`. `trust` = ignore failure; `silent`/`suppress` = mute output.
- Handlers: `failed { }`, `failed(e) { }` (e = captured stderr), `succeeded { }`, `exited(code) { }`.

## Colors / ESC bytes (TUI core)
- Mint a real ESC once and cache it: `let E = trust $printf '\x1b'$`. (`\x1b` hex works; `\033` octal stays literal in raw commands.)
- Build SGR from it: `let RED = "{E}[31m"`, `let RESET = "{E}[0m"`.
- Emit to stderr (the `%s` keeps payload literal — ESC, `%`, quotes all pass through):
  ```
  pub fun eprintln(s: Text): Null { trust $printf '%s\n' "{s}" >&2$ }
  ```
- **Use `src/core.ab`'s `esc()`, `eprint()`, `eprintln()`, `term_interactive()` — do not re-mint.**

## Arrays
- `[1,2,3]`, append `xs += [4]`, index `xs[0]`. Empty typed: `let xs: [Int] = []` is illegal (no let-types) → use `let xs = [0]` then clear, or build via funcs.
- **Nested arrays are FORBIDDEN** (*"Arrays cannot be nested due to the Bash limitations"*). Model tables/maps as **PARALLEL arrays** (keys[], vals[]) — exactly what the bash `__DASH_*_KEYS` / `_VALS` design did. Or pack a record into one `Text` with a `|` delimiter and `split` it.
- Module-level `let` arrays/scalars **persist across function calls** (compiled to bash globals). Use this for in-memory stores (dashboard rows, seen-sets). `ref` params mutate the caller's variable.

## stdlib (FREE functions — `lowercase(s)`, NOT `s.lower()`)
- `import { ... } from "std/text"`: `lowercase uppercase capitalized reversed trim trim_left trim_right split split_lines split_words split_chars join slice(t,start,len) lpad(t,pad,len) rpad cpad zfill starts_with ends_with text_contains text_contains_any text_contains_all replace replace_one replace_regex match_regex match_regex_any count_chars count_lines count_words char_at`
- `import { ... } from "std/array"`: `array_contains array_find array_find_all array_first array_last array_pop(ref) array_shift(ref) array_remove_at(ref) sort(ref) sorted math_sum`
- `import { ... } from "std/env"`: **`printf(fmt, [args])`** `env_var_get env_var_set env_var_test env_var_unset is_command input_prompt input_confirm input_hidden kill(pid,sig) pgrep pkill`
- `import { ... } from "std/fs"`: `file_read file_write file_append file_exists dir_create dir_exists file_glob symlink_create file_chmod`
- `import { assert, assert_eq } from "std/test"` — assertions for `test` blocks.
- `import { ... } from "std/math"`: `math_floor math_ceil math_round math_abs math_sum`
- repeat-a-char (replaces bash `printf '%*s' n ''` + `//`): `rpad("", "█", n)` → n copies of `█`.

## Testing discipline (this port is "all the way with testing")
- Keep PURE formatters (`fmt_*` return Text) separate from impure emitters (write stderr). Assert on the returned Text — you can check it contains the ESC sequences, the right counts, truncation, padding, etc., without a TTY.
- Importing a module does NOT run its `main` — so `test_foo.ab` can `import { ... } from "../src/foo.ab"` freely.
- Mirror the original bats test names/intent so the behavior contract is preserved.
- Every module ships `test/test_<module>.ab`. CI: `amber test test/`.

## USE THESE 0.6 FEATURES (verified against the compiler — they cut complexity)
- **Empty arrays:** `let xs = []` works for LOCALS (type inferred from first `+= [v]`). BUT a **module-level** `let xs = []` does NOT propagate its element type across function boundaries — `for x in xs { if x == s }` in another fn sees `Generic` and fails *when imported*. For module-level parallel arrays: declare with a **typed seed** `let xs = [""]` (or `[0]`) to anchor the type, then `xs = []` in your init/clear to empty it, `+= [v]` to append, `len xs` for count. (Still far simpler than the old seed-slot + `__count` dance — no counter, no index juggling.)
- **Array destructuring:** `let [a, b, c] = split(rec, "|")` — replaces `index_of("|")`+`slice`×N record unpacking.
- **Background jobs:** `trust $ cmd & $` + `let p = pid()` + `await(p)?` + `disown()`. Watchers/parallel deploys don't *have* to be rewritten synchronous.
- **`lock(path)?`** = flock. **`suppress $cmd$?`** mutes stderr. **`ls(dir, all, recursive)` → [Text]**, `pwd()`, `cp/mv/rm/touch(...)?`.
- **`uname_os()` / `uname_machine()` / `uname_kernel_name()`** (std/env) — don't hand-parse `uname -s`.
- **`fetch(url, method, data, headers)`** (std/http) — don't shell to curl for HTTP.
- **Recursion works.** Union types `Int | Text` for multi-type params. Default params `x: Text = "d"`.
- Prefer stdlib over pipelines: `match_regex`/`replace`/`replace_regex`/`split_lines`/`split`/`join`/`sort` over sed/awk/grep/tr/paste.
- STILL no native equivalent (keep raw `trust $…$`): `trap`, `exec`, complex multi-filter `jq`. That's it.

## HARD-WON GOTCHAS (each cost a debugging cycle — heed them)
1. **Reserved keywords as identifiers fail silently-ish.** Never name a var/param `status`, `failed`, `lines`, `pid`, `len`, `clear`, `is`, `as`, `in`, `not`, `and`, `or`. A param `status: Text` parses as the `status` keyword (Int) → baffling `Cannot assign 'Int' to array of 'Text'`. Use `st`, `n_failed`, etc.
2. **Define-before-use ACROSS import boundaries.** A private (non-`pub`) helper must be defined ABOVE the `pub` fn that calls it, else `Function 'x' does not exist` — but ONLY when the module is imported (works via `amber run` on the file itself). Amber tree-shakes per imported symbol and keeps only deps textually preceding the entry. Put ALL private helpers at the top. Same applies to helpers used by `test` blocks in a test file.
3. **`import { x as y }` aliasing is BROKEN.** Aliased fns return empty/no-op. Import under the real name; rename your own symbol to avoid the clash. You also CANNOT redeclare an imported name (`import {trim}` + `pub fun trim` → "Cannot redeclare"). Either re-export (`pub fun my_trim(s){return trim(s)}`) or implement natively under your own name and don't import the std one.
4. ~~Empty-array seed + first-append pattern~~ **SUPERSEDED**: `let xs = []` works (type inferred from first `+=`). Use it; `len xs` is the count. (Older modules still carry the seed-slot pattern — being refactored out.)
5. **`sort(ref arr, desc)` not `sorted(arr, desc)`** — `sorted` returns `[Generic]` you can't `==` against Text. `sort` mutates in place, preserves `[Text]`.
6. **No raw `${VAR}` in `$...$`** — `{` is Amber interpolation. Use `env_var_get("VAR") failed { "default" }`.
7. **`silent` eats fd 2** (`cmd 2>/dev/null`). Don't use it with `test -t 2` / anything fd-2-sensitive — use bare `trust $test -t 2$` then read `status()`.
8. **Splitting a Text on newlines:** `split_lines(s)` from std/text (NOT the `lines` builtin, which only works on a command). `split_lines` gives the bash count_lines contract: "a\nb\nc"→3, "a\nb\n"→2, ""→0, "x"→1.
9. **Building sed/regex backrefs:** construct `let backref = "\\{n as Text}"` as a SEPARATE Text, then interpolate `{backref}` — inline `"\\{n}"` in a `$...$` mis-lowers the `\\` + var ref.
10. **`status` builtin: call as `status()`** to avoid the deprecation warning.

## Test-runner flake (don't chase ghosts)
`amber 0.6.0-alpha`'s parallel `amber test test/` is **nondeterministic on a COLD cache** — racing on temp-compiled binaries it can misreport counts and even attribute phantom test names to the wrong file. It converges to stable green on warm runs. AUTHORITATIVE check = run each file individually (`amber test test/test_<m>.ab`) or run the full suite 2–3× and take the warm result. Don't "fix" a failure that only appears on the first cold run and names a test that exists in no file.

## Project layout
```
amber-tui/
  src/      *.ab modules (core.ab is the proven foundation)
  test/     test_*.ab (amber test blocks)
  build/    compiled *.sh (gitignored)
  Makefile  build + test targets
```
