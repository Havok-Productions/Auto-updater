# Auto-Updater
The other auto updaters were too confusing for me. This is meant to be a simple, auto updater that auto updates the server jar, and the plugin used.

## Directions

Use "auto-updater.jar" as the startup jar

This is meant to be a simple standalone launcher jar for Minecraft servers. It runs first, updates configured jars, backs up anything it replaces, and then starts the real server jar.

## What It Supports

- Folia, Paper, Velocity
- Hangar plugin downloads.
- GitHub release jar downloads. Will compile jars.
- Modrinth plugin downloads.
- Direct jar URLs and local jar paths.
- Scheduled restarts with warning commands.
- Per-plugin `autoUpdate` opt-out.
- Failure memory for plugin jars that fail startup. It will restart with previously working jars.
- Trusted Git source build fallback with Gradle/Maven auto-detection.

## Config Notes

- `discovery.enabled: false` disables normal `check`, `update`, and `run` discovery side effects, including installed-plugin auto-add, missing-source auto-switching, stale discovered-source rewrites, and stale proof refresh. The explicit `discover` command can still run discovery.
- `server.pinBuild` is optional. Leave it blank for normal PaperMC behavior: `updater.lock.yml` remembers the last installed build for rollback, but newer builds within the locked `gameVersion` can still install. Set `pinBuild` only when you intentionally want one exact Paper/Folia/Velocity build.
- `type: github-source` on a plugin primary source means "build this Git repo first." Hosted fallback sources are only tried after that primary Git build fails or is skipped.
- `sourceOrigin` records ownership of plugin sources: `manual` means user-owned, `discovered` means machine-selected with an active matching source proof, `discovered-unverified` means machine-selected without descriptor proof, and `unresolved` means discovery has not found a reliable source yet.
- `newPluginLinks` is a paste-here inbox for plugin URLs you find manually. On the next update/discovery run, each link becomes a manual plugin catalog entry, matching entries are updated instead of duplicated, unknown links become `UnknownPlugin` entries, and consumed links are removed from the inbox.
- Existing configs are auto-refreshed with safe schema defaults, such as `newPluginLinks: []` and newer `discovery` keys, when the updater starts. Existing values and plugin entries are not overwritten by this refresh.
- `discovery.pruneMissingInstalledPlugins: true` removes auto-managed plugin entries from `updater.yml` when their `installAs` jar no longer exists under `plugins/`. Manual sources are never pruned by this cleanup. Set it to `false` if you want deleted plugin entries to stay listed.
- `discovery.retryDeferredAfterStartup: true` lets `run` mode retry source-only discovery after the server has started when earlier discovery was deferred by GitHub rate limits or source backoff. This can save newly found URLs to `updater.yml`, but it does not install jars or restart the live server.
- `discovery.preferredOwners` defaults to empty. Add GitHub owners such as `Folia-Inquisitors` or `Inquisitors-transfers` when you intentionally use a Folia/custom fork ecosystem. Preferred owners are search hints only: they are probed before broad GitHub search for missing or weak sources, but they do not override manual sources or bypass descriptor validation, Folia proof, or downgrade protection.
- When the PaperMC server jar lock changes, plugin-side retry memory is cleared. That lets plugins with known-bad jars, failed source builds, or deferred/not-found discovery states search again against the new server version/build.
- `discovery.pathfindingDebug: true` writes a linear decision trace to `architecture-pathfinding.debug`. Use `pathfindingDebugPlugin: ChatFilter` to trace one plugin while debugging config normalization, lock-file memory, discovery skips, provider search order, archived GitHub decisions, fallback ordering, startup rollback, candidate scoring, update choices, and validation.
- Pathfinding traces include a run id, `PATHFINDING TRACE START/END` markers, per-target headers, and `[PHASE ...]` markers so repeated updater runs are easier to scan.
- Boolean config values are strict. Use `true/false`, `yes/no`, `on/off`, or `1/0`; typos stop parsing instead of silently disabling safety settings.
- On Folia, auto-discovered plugin updates must prove Folia support with `folia-supported: true`. Manual sources may still install generic Paper/Bukkit jars, but the updater warns.
## Default Config

```
mode: hosted-safe
onFailure: keep-current
userAgent: "Auto-Updater/0.3.0 (contact: your-email@example.com)"

newPluginLinks: []
# Paste plugin links here, then run the updater. Examples:
# newPluginLinks:
#   - https://github.com/Folia-Inquisitors/ExamplePlugin
#   - https://modrinth.com/plugin/example/versions
#   - https://hangar.papermc.io/Owner/Example/versions

discovery:
  enabled: true
  mode: suggest
  sourcePriority: github-release, hangar, modrinth
  checkAlternateSourcesWhenOutdated: true
  outdatedThresholdDays: 14
  autoSwitchSource: true
  saveDiscoveredSources: true
  scanInstalledPlugins: true
  pruneMissingInstalledPlugins: true
  retryDeferredAfterStartup: true
  pathfindingDebug: false
  pathfindingDebugPlugin: ""
  pathfindingDebugFile: architecture-pathfinding.debug
  # preferredOwners:
  #   - Folia-Inquisitors
  #   - Inquisitors-transfers

buildFromSource:
  enabled: auto
  onlyTrusted: true
  preferHostedIfSameVersion: true
  trustedGithubOrgs: PaperMC, GeyserMC, ViaVersion
  trustedGithubRepos: Inquisitors-transfers/MyCustomPlugin

failureMemory:
  enabled: true
  retryBadAfter: never

server:
  name: auto
  source: auto
  type: auto
  installAs: auto
  gameVersion: auto
  changeVersion: false
  pinBuild: ""
  java: java
  javaArgs: "-Xms16G -Xmx32G"
  args: ""

plugins:
  - name: ViaVersion
    source: https://github.com/ViaVersion/ViaVersion
    type: auto
    autoUpdate: true
    githubRepo: ViaVersion/ViaVersion
    platform: paper
    fallbackSources: https://hangar.papermc.io/ViaVersion/ViaVersion/versions, https://modrinth.com/plugin/viaversion/versions
    installAs: plugins/ViaVersion.jar
    required: false

  - name: ViaBackwards
    source: https://github.com/ViaVersion/ViaBackwards
    type: auto
    autoUpdate: true
    githubRepo: ViaVersion/ViaBackwards
    platform: paper
    fallbackSources: https://hangar.papermc.io/ViaVersion/ViaBackwards/versions, https://modrinth.com/plugin/viabackwards/versions
    installAs: plugins/ViaBackwards.jar
    required: false

  - name: ViaRewind
    source: https://github.com/ViaVersion/ViaRewind
    type: auto
    autoUpdate: true
    githubRepo: ViaVersion/ViaRewind
    platform: paper
    fallbackSources: https://hangar.papermc.io/ViaVersion/ViaRewind/versions, https://modrinth.com/plugin/viarewind/versions
    installAs: plugins/ViaRewind.jar
    required: false

restart:
  enabled: true
  interval: 7d
  stopCommand: stop
  gracefulStopSeconds: 60
  startupRollbackPolicy: rollbackBatch
  warnings:
    - before: 2h
      command: "say Server restart in 2 hours for updates."
    - before: 30m
      command: "say Server restart in 30 minutes for updates."
    - before: 5m
      command: "say Server restart in 5 minutes for updates."
    - before: 1m
      command: "say Server restart in 1 minute for updates."

```


## Pre-upgrade Startup Test

`upgradeTest.enabled: true` prepares changes before replacing live jars. When the
server jar changes, it launches the proposed server and plugins in a temporary
flat world, with separate ports and copies of plugin/Paper/Folia configuration.
Pinned plugins (`autoUpdate: false`) are included using their installed jars.
Previously failed plugin candidates are reconsidered for the proposed server;
an identified failing candidate may be retried with its installed jar once.

Only successful startup, the expected plugin enable messages, a short observation
window, and clean shutdown approve installation. Scheduled preparation happens
while the live server stays online. Failed combinations are remembered by hashes
of the proposed jars and copied configuration; changed inputs are tested again.
Preparation or sandbox failures postpone the upgrade without changing live jars.
An installation failure restores any jars already replaced before restarting.

On Debian/Ubuntu, the updater uses an existing `bubblewrap` installation or
automatically obtains a private copy through the host's signed APT repositories.
APT verifies signatures and package hashes; bubblewrap and its libcap runtime are
extracted under `cache/tools/bubblewrap`, without sudo or system package changes.
The private copy is reused on subsequent runs. Setup diagnostics are in that
directory's `setup.log`. The host must permit unprivileged user namespaces.
The test has no external network and can write only its temporary directory.
PaperMC's trusted patch-only bootstrap may download runtime artifacts
before the network-isolated test, without enabling plugins. Unsupported hosts or
blocked namespaces or failed automatic setup postpone upgrades; no unisolated
fallback is attempted.

Only configuration files (YAML, JSON, TOML, CONF, properties, XML) are copied, at
most 2 MiB per file and 64 MiB per directory tree. World data, database files, symlinks,
logs, backups and caches are excluded. Runtime JARs may be reused for bootstrap.
Some data-dependent or network-dependent plugins cannot enable under these
conditions and will block the test. Explicit `upgradeTest.excludePlugins` names
can exclude their jars, with exclusions reported, but their dependants may fail.
No plugin is silently treated as tested. Configuration changes during a running
test are not covered; large data-specific and gameplay failures remain outside
this startup check. Configure `startupSeconds`, `observeSeconds`, `stopSeconds`
and the separate test `javaArgs` as needed; the test adds memory/CPU load.

Reports are retained under `cache/upgrade-tests/test-*/` (`result.txt`,
`console.log`, `bootstrap.log`); disposable worlds and configuration copies are
removed after each test. Reports may contain plugin output; keep them private.
This is a preflight check, not a world backup or a guarantee of safe world-format
rollback. The existing live startup rollback behavior still applies.

## Building instructions

./gradlew build

# Official Discord 

https://discord.gg/aT9z7q7hX8

### Folia inquisitors

[<img src="https://github.com/Folia-Inquisitors.png" width=80 alt="Folia-Inquisitors">](https://github.com/orgs/Folia-Inquisitors/repositories)
