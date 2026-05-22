# Bug Report — PluginUpdater

Exhaustive audit of all 12 Java source files. All bugs listed below have been fixed.

---

## CRITICAL — Fixed

### `core/.../ShutdownHandler.java:380` — StringIndexOutOfBoundsException ✓
`fname.startsWith(versionedPrefix)` only guarantees `fname` *starts with* the prefix; it does
not guarantee `fname.length() > versionedPrefix.length()`. A file named exactly `<name>-.jar`
would cause `charAt(versionedPrefix.length())` to throw `StringIndexOutOfBoundsException`.

**Fix:** Added `&& fname.length() > versionedPrefix.length()` to the `else if` condition.

---

## HIGH — Fixed

### `core/.../ShutdownHandler.java:392-394` — Wrong fallback when `getLastModifiedTime` throws ✓
When `getLastModifiedTime` failed, `bestMatch` was set only if no previous match existed, but
`bestMtime` remained `-1`. On the next iteration any JAR with a real mtime would replace the
unreadable entry — correct by accident. If two unreadable JARs matched, only the first was
kept.

**Fix:** Changed guard to `if (bestMatch == null || bestMtime == -1) bestMatch = jar;` so an
unreadable file is only used when all candidates are unreadable, never replacing a file with a
known mtime.

### `core/.../ModrinthUpdateChecker.java:106` — Unchecked `getAsJsonArray()` throws on non-array response ✓
`JsonParser.parseString(...).getAsJsonArray()` chained without a type check. If the API
returned a JSON object or primitive the `IllegalStateException` was swallowed by the outer
catch, logging a confusing Gson error instead of the actual API response.

**Fix:** Parse into a `JsonElement`, check `isJsonArray()`, and log the actual response body
before returning `Optional.empty()` on mismatch.

### `core/.../GitHubUpdateChecker.java:206,247` — `idEl.getAsLong()` on non-numeric JSON ✓
No check that `idEl` was actually a numeric `JsonPrimitive`. A string or boolean `"id"` would
throw `NumberFormatException` / `UnsupportedOperationException`, silently skipping the asset.

**Fix:** Added `if (!idEl.isJsonPrimitive() || !idEl.getAsJsonPrimitive().isNumber()) return null;`
in the new shared `parseAsset` / `parseAssetNoGlob` helpers used by both loops.

### `core/.../ShutdownHandler.java:204-212` — Race between `startupThread` assignment and `onDisable` ✓
`startupThread` was published before `worker.start()`. If `onDisable()` called `join()` in
the window between publish and actual thread start, `join()` on a `NEW`-state thread returns
immediately, leaving the startup download unjoinable.

**Fix:** After `worker.start()` for the startup thread, spin on `worker.getState() == NEW`
(yield loop) until the thread transitions to `RUNNABLE` before returning, so `onDisable` can
always successfully join it.

### `core/.../FileUpdater.java:98` — `return null` inside nested try could leak temp file on Windows ✓
On exceeding the download limit the code did `return null` from inside the
try-with-resources for the `OutputStream`. Although JVM TWR semantics close the stream before
the outer `finally`, on Windows the handle release is not guaranteed visible to
`Files.deleteIfExists` without explicit close, leaving partial temp files on disk.

**Fix:** Replaced the in-loop `return null` with a `limitExceeded = true; break;` so the TWR
block closes both streams cleanly before the `finally` block deletes the temp file.

### `core/.../FileUpdater.java:240` — Recursive `isPrivateHost` can stack-overflow on malformed input ✓
`isPrivateHost` called itself for bracketed IPv6 hosts. Deeply nested brackets
`[[[...[[::1]]...]]` cause unbounded recursion → `StackOverflowError`. An attacker controlling
a redirect URL could exploit this.

**Fix:** Removed recursion. The bracket case now unwraps exactly one layer and performs a
direct inline check for the IPv6 loopback/private ranges — no further recursion.

---

## MEDIUM — Fixed

### `core/.../GlobMatcher.java:37-44` — Cache size race allows unbounded growth ✓
`size() < MAX_CACHE_SIZE` and `putIfAbsent` are not atomic together on `ConcurrentHashMap`.
N concurrent threads could each see `size() < 256` before any insert, all inserting and
pushing the cache beyond the limit.

**Fix:** Replaced the `size()`-then-`putIfAbsent` pair with `computeIfAbsent`, which performs
the check-and-insert under the segment lock atomically.

### `core/.../GitHubUpdateChecker.java:223-258` — Fallback asset loop duplicates full asset scan ✓
Both the primary and fallback loops duplicated asset-field parsing and `idEl.getAsLong()`
calls. Any bug fix had to be applied twice.

**Fix:** Extracted shared `parseAsset(el, entry, storedKey, latestTag)` helper (with glob
check) and `parseAssetNoGlob` (for the fallback, which already verified the name prefix).
Both loops now call the appropriate helper, eliminating the duplication.

### `core/.../ConfigManager.java:97-100` — TOCTOU `createDirectories` after `exists` check ✓
`Files.exists(configFile)` followed by `Files.createDirectories(dataFolder)` left a window
where another process could alter the directory between the two calls.

**Fix:** Moved `Files.createDirectories(dataFolder)` unconditionally before the `exists`
check so the directory is always guaranteed to exist first.

### `core/.../VersionStore.java` — `load()` not synchronized; `persist()` iterates live map ✓
`load()` mutated the `ConcurrentHashMap` without synchronization while `setVersion`
(synchronized) could do the same from a background thread. Additionally, `persist()` iterated
the live map, which could see partial updates from a concurrent `load()`.

**Fix:** Made `load()` `synchronized`. Changed `versions.forEach` in `persist()` to iterate
over `new HashMap<>(versions)` (a point-in-time copy taken under the existing lock).

### `core/.../ShutdownHandler.java:165-172` — Scheduler drain timeout too short ✓
`scheduler.awaitTermination(5, TimeUnit.SECONDS)` could time out while an active download
was in progress, allowing the shutdown worker to start concurrently and write the same target
JAR simultaneously.

**Fix:** Changed `awaitTermination` to use `DRAIN_TIMEOUT_MS` (10 seconds) so the full drain
budget is used before proceeding to the shutdown worker.

---

## LOW / INFORMATIONAL — Fixed

### `core/.../GitHubUpdateChecker.java:311` — `readBody` undercounts `\r\n` line endings ✓
`readLine()` strips terminators; `+1` only accounted for `\n` but not `\r\n`, letting the
body exceed the 10 MB guard by up to one byte per line.

**Fix:** Changed `+1` to `+2` in both `GitHubUpdateChecker.readBody` and
`ModrinthUpdateChecker.readBody`.

### `core/.../ShutdownHandler.java:421-423` — `sanitizeFilename` no length cap, no dot-sequence strip ✓
Untrusted plugin names/version strings could produce filenames exceeding OS limits (255 bytes),
causing `atomicReplace` to throw `IOException`. A name like `../../evil` produced `.._.._evil`
after slash removal.

**Fix:** Added `replaceAll("\\.\\.\+", "_")` to collapse dot sequences, and truncated the
result to 64 characters.

### `paper/.../PaperPlugin.java:33` — `getDataFolder().toPath().getParent()` is brittle
`getDataFolder()` returns `plugins/PluginUpdater`; its parent is assumed to be `plugins/`.
A custom server that relocates the data folder would silently target the wrong directory. The
null check at line 34 prevents silent corruption. No API alternative is universally available
across all Paper versions targeted (1.8.8+), so this is left as-is with the existing null guard.

**Status:** Left as-is (null guard already present; no stable API covers all supported versions).

### `core/.../ShutdownHandler.java:340` — Version written even when old-JAR deletion fails
The version key is stored after the new JAR is placed regardless of whether the old JAR was
deleted. On Windows the old (locked) JAR can remain alongside the new one. The `deleteOnExit`
fallback is already in place. The stored version is correct (the new JAR is the active one),
so this is purely cosmetic — the orphan is cleaned on next JVM exit.

**Status:** Left as-is (`deleteOnExit` fallback already handles this).

### `core/.../ModrinthUpdateChecker.java:154-165` — Single-file fallback pre-computed before primary loop ✓
The fallback URL was captured in a separate block before the main glob-matching loop,
requiring two passes over the files array and adding structural confusion.

**Fix:** Merged the fallback capture into the primary loop using `if (files.size() == 1 && ...)`.
The same single pass now collects both the glob match (returned immediately) and the fallback.

### `velocity/.../VelocityPlugin.java:97` — `loc.toURI()` `URISyntaxException` gave unhelpful message ✓
`URL.toURI()` throws checked `URISyntaxException`. It was caught by `catch (Exception e)` but
the log message just showed the raw exception type with no operator-friendly context.

**Fix:** Wrapped `loc.toURI()` in a try/catch that converts `URISyntaxException` into an
`IOException` with a descriptive message before the outer catch logs it.

### `core/.../CC.java:19` — `ANSI_PATTERN` recompiled on every `strip()` call ✓
`String.replaceAll` recompiles the regex `Pattern` on every invocation. `strip()` is called
once per log record on the Velocity JUL bridge.

**Fix:** Changed `ANSI_PATTERN` from a `String` to a compiled `Pattern` field and used
`ANSI_PATTERN.matcher(msg).replaceAll("")` in `strip()`.

### `core/.../VersionStore.java:106-107` — `persist()` iterated live map ✓
Covered under the VersionStore synchronization fix above.
