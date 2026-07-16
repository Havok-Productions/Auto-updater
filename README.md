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


## Building instructions

./gradlew build

# Official Discord 

https://discord.gg/aT9z7q7hX8

### Folia inquisitors

[<img src="https://github.com/Folia-Inquisitors.png" width=80 alt="Folia-Inquisitors">](https://github.com/orgs/Folia-Inquisitors/repositories)
