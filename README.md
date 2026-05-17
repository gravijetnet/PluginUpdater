# PluginUpdater

Automatically keeps your server's plugins up to date. On startup PluginUpdater checks every configured plugin against GitHub Releases or Modrinth, downloads any newer JARs in the background, and swaps the files on disk. Updated plugins become active after the next server restart — no runtime reload is performed.

Supports both **Paper** (1.8.8+) and **Velocity** proxy servers.

---

## Features

- **Startup check** — immediately after the server starts, all configured plugins are checked and any available updates are downloaded in the background.
- **Periodic checks** — optionally re-check for updates at a configurable interval while the server is running (default: every 30 minutes). Set to `0` to disable.
- **Shutdown check** — a final check runs on every clean shutdown (`/stop`) and also on forced kills (SIGKILL), so updates released between periodic checks are never missed.
- **GitHub source** — fetches the latest release from any public or private GitHub repository. Uses asset `updated_at` timestamps as the change key, so re-uploads under the same tag name are also detected.
- **Modrinth source** — fetches the latest version from any Modrinth project by slug or ID. Uses the unique Modrinth version ID as the change key.
- **Glob asset matching** — `asset-pattern` uses glob syntax (e.g. `EssentialsX-*.jar`) to select the correct JAR when a release has multiple assets.
- **Self-update** — PluginUpdater can update its own JAR. The replaced file becomes active after the next restart.
- **Missing JAR recovery** — if the JAR for a tracked plugin is deleted from disk, PluginUpdater re-downloads it automatically.
- **Per-plugin token override** — each plugin entry can use its own access token instead of the global one, useful when mixing public and private repositories.
- **No external HTTP libraries** — all network calls use the standard Java `HttpURLConnection`.

---

## Installation

1. Drop the correct JAR into your `plugins/` folder:
   - `PluginUpdater-paper-<version>.jar` for Paper servers
   - `PluginUpdater-velocity-<version>.jar` for Velocity proxies
2. Start the server once to generate `plugins/PluginUpdater/config.yml`.
3. Edit the config to add the plugins you want to track (see [Configuration](#configuration)).
4. Restart the server. PluginUpdater will run an initial check immediately.

---

## Configuration

File location: `plugins/PluginUpdater/config.yml`

```yaml
# Global GitHub personal access token.
# Leave empty ("") for public repos — no Authorization header is sent.
github-access-token: ""

# Global Modrinth token.
# Optional — only needed for private projects or to increase the rate limit.
modrinth-access-token: ""

# How often (in minutes) to check for updates while the server is running.
# Set to 0 to disable periodic checks (updates only happen on startup and shutdown).
check-interval-minutes: 30

plugins:
  - name: "EssentialsX"
    source: github                        # "github" (default) or "modrinth"
    repo: "EssentialsX/Essentials"        # GitHub: "owner/repo"
    asset-pattern: "EssentialsX-*.jar"    # glob matched against release asset filenames

  - name: "LuckPerms"
    source: github
    repo: "LuckPerms/LuckPerms"
    asset-pattern: "LuckPerms-Bukkit-*.jar"
    access-token: "ghp_yourPrivateToken"  # optional: overrides the global token

  - name: "Geyser"
    source: modrinth
    repo: "geyser"                        # Modrinth project slug or numeric ID
    asset-pattern: "Geyser-Spigot.jar"
```

### Config fields

| Field | Required | Description |
|---|---|---|
| `github-access-token` | No | Global GitHub token for all GitHub entries. Leave empty for public repos. |
| `modrinth-access-token` | No | Global Modrinth token for all Modrinth entries. |
| `check-interval-minutes` | No | Periodic check interval in minutes. `0` disables periodic checks. Default: `30`. |
| `plugins[].name` | Yes | Display name used in logs and as the JAR filename prefix. |
| `plugins[].source` | No | `github` or `modrinth`. Defaults to `github`. |
| `plugins[].repo` | Yes | `owner/repo` for GitHub; project slug or ID for Modrinth. |
| `plugins[].asset-pattern` | Yes | Glob pattern matched against release asset filenames (e.g. `MyPlugin-*.jar`). |
| `plugins[].access-token` | No | Per-plugin token; overrides the global token for this entry only. |

### Glob pattern syntax

`asset-pattern` uses standard glob syntax:

| Pattern | Matches |
|---|---|
| `*` | Any sequence of characters (within a filename) |
| `?` | Any single character |
| `EssentialsX-*.jar` | `EssentialsX-2.20.1.jar`, `EssentialsX-2.21.0.jar`, … |
| `Geyser-Spigot.jar` | Exactly `Geyser-Spigot.jar` |

---

## How updates are applied

1. The new JAR is downloaded to a temporary file.
2. The temporary file is atomically moved to the plugins folder, replacing the old JAR.
3. On Windows, if the JAR is locked by the JVM, a `deleteOnExit` fallback ensures the old file is cleaned up on the next server exit.
4. The updated version key is saved to `plugins/PluginUpdater/versions.yml`.
5. A log message confirms the update: `[PluginUpdater] ● PluginName updated to x.y.z (active on next start)`.

Updated JARs are **not** hot-reloaded. Restart the server to load the new version.

---

## Files

| File | Description |
|---|---|
| `plugins/PluginUpdater/config.yml` | Main configuration — plugins list, tokens, check interval. |
| `plugins/PluginUpdater/versions.yml` | Tracks the last downloaded version key for each plugin. Do not edit manually. |

---

## Permissions

PluginUpdater runs entirely as a server-side background process and does not expose any player-facing commands or permissions. No permission nodes need to be configured.

---

## Supported platforms

| Platform | Version |
|---|---|
| Paper | 1.8.8+ |
| Velocity | 3.x |

---

## Notes

- **First run:** if a plugin is added to the config and no version is stored yet but the JAR already exists on disk, PluginUpdater will download the latest release to sync its internal state.
- **Private repositories:** supply a GitHub personal access token with `repo` scope (GitHub) or a user token (Modrinth) either globally or per-plugin.
- **Rate limits:** unauthenticated GitHub API requests are limited to 60/hour. Add a token to increase this to 5,000/hour. Modrinth's public rate limit is generous but a token can raise it further.
