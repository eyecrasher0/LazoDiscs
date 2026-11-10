# LazoDiscs Changelog

## [1.0.7 Paper] - 2026-11-10

### Added

- First Paper plugin release: 21 exact server targets from 1.17.1 through 26.3, matching available Paper builds in stable Plasmo Voice 2.1.17's compatibility list. The existing mod builds are unchanged.
- Normal server startup on the documented Java runtimes, without Java-version override flags. Paper 1.16.5 is excluded because its supported Java runtime is incompatible with the current Plasmo Voice plugin.
- Burn, search and erase commands with Bukkit permissions, persistent item recordings, configurable language and playback settings, and positional Plasmo Voice audio using the existing 1.0.7 audio engine.
- Version-specific jukebox adapters: legacy PDC items through 1.21.3, silent datapack songs from 1.21.4 through 26.2, and registry songs on 26.3. Native hopper insertion/extraction is supported where the game provides jukebox inventories (1.19.4+).
- Isolated, relocated audio libraries for old Paper servers, cancellation of pending playback on ejection/unload, protection checks, and cleanup of sources, workers and commands at shutdown.
- Hash-pinned disposable-server integration checks and a GitHub Actions compatibility matrix. See `paper/README.md` for requirements and legacy behavior differences.

## [1.0.7] - 2026-10-13

### Fixed

- Store the selected playable track URL when burning a Spotify disc, keeping repeated playback stable instead of repeating Spotify searches. Spotify matching now rejects unrelated track titles even when their duration is close.
- Fixed SoundCloud playback for AAC/fMP4 HLS streams, including shared on.soundcloud.com links, while retaining support for older audio formats.
- Restored custom-disc playback in Sophisticated Backpacks on Fabric versions that use SophisticatedCore's SoundHandler API. Recorded discs play their custom track instead of the original Minecraft song.
- Kept support for the earlier SophisticatedCore disc-handler API on Forge and NeoForge, including releases whose stop callback takes a server world.
- Kept backpack playback cleanup and track-finished callbacks working when stopping, replacing or finishing a recording.

### Changed

- Removed LavaPlayer's deprecated YouTube implementation and its third-party Google account authentication code from release files. YouTube playback and search continue to use youtube-source.
- Registered the remaining LavaPlayer sources explicitly, preserving SoundCloud and direct audio playback.

The 52 Minecraft/loader builds and the public interfaces used by LazoBoombox are unchanged. Requires Plasmo Voice; Fabric builds also require Fabric API.

## [1.0.6] - 2026-10-07

### Added

- Added Forge support.
- Added builds for more Minecraft versions, starting at 1.16.5, following stable Plasmo Voice 2.1.17 support. There are 52 builds: 25 Fabric, 15 Forge and 12 NeoForge.

### Fixed

- Fixed jukebox handling when inserting and removing custom discs on supported 1.21.x versions.
- Removed unnecessary music stops and restarts when an attempted disc removal leaves the disc in the jukebox.

### Changed

- Updated the mod icon to the new 256×256 image in mod menus and Plasmo Voice.
- Release builds now follow the Minecraft versions supported by stable Plasmo Voice 2.1.17. Versions supported only by old or beta Plasmo Voice releases are not included.

Requires Plasmo Voice. Fabric builds also require Fabric API.

## [1.0.5] - 2026-10-05

### Fixed

- Enforce configured burn, erase, search and playback permission levels, including the Minecraft 1.21.11 permission API. Failed permission lookups no longer grant access.
- Keep asynchronous burning bound to the original, unchanged disc and recheck permissions before writing the result.
- Validate stored URLs before audio loading, including Spotify URI conversion and configured scheme/domain restrictions.
- Prevent late callbacks and concurrent start/stop operations from stopping a replacement source or leaving audio resources behind.
- Drain buffered audio frames before finishing a track.
- Resume saved Fabric and NeoForge jukeboxes after chunk load when Plasmo Voice is ready; clear pending work on unload and server shutdown. NeoForge waits for fully loaded chunks without forcing loads or restarting an identical active track.
- Avoid consuming a disc before validating the jukebox block entity.
- Allow ordinary far-away jukeboxes when Sable is not installed.
- Close Spotify HTTP connections on success and failure.
- Register NeoForge client tooltip handling and reject non-finite Fabric volume settings.
- Validate language identifiers to prevent language files escaping their configuration directory; invalid values fall back to `en_us`.
- Stop Sophisticated backpack playback sources when the Plasmo Voice addon shuts down.

### Improved

- Bound pending audio-load requests; rejected requests show a localized retry message and finish playback callbacks instead of leaving them waiting.
- Cancel queued work during shutdown and keep executor reconfiguration restart-safe.
- Update LavaPlayer to 2.2.7 and the pinned YouTube-source snapshot to 2be8e542d3f6f178e048dca565892684c2e40177.
- Update privately relocated HttpClient to 4.5.14 and Jackson through BOM 2.21.7 without replacing Minecraft's library versions.
- Update bundled Rhino/rhino-engine to 1.7.15.1 and JSON-java to 20260719 for upstream security fixes, keeping these audio libraries off Minecraft's compile classpath.
- Align dependency metadata, resource/data pack formats and addon version identifiers across all source ports.
- Standardize Java formatting and improve lifecycle/compatibility comments.
- Add behavioral regressions, project/resource checks, full-matrix CI, reproducible archive settings and release packaging checks.
- Preserve complete bundled third-party licenses/notices, including colliding Jackson, Apache, Jsoup and Rhino resources, and verify their contents in final release JARs.

### Compatibility

Java 21; Plasmo Voice 2.1.8 or newer. Install matching Minecraft/loader builds. LazoBoombox 0.1.2 requires LazoDiscs 1.0.5 or newer.

Source ports exist for Minecraft 1.21.1–1.21.11 on Fabric and NeoForge. Official Plasmo Voice 2.1.17 metadata covers 1.21.1, 1.21.4, 1.21.6–1.21.8 and 1.21.11. Other ports require a separately compatible Plasmo Voice build; compilation alone does not establish runtime support.


## [1.0.2] - 2026-08-25

### Fixed
- **YouTube playback**: Fixed "Must find action functions from script" / "no actions match" error caused by YouTube player script changes
  - Updated `dev.lavalink.youtube:v2` to working commit `f45bbb7aebfcbc1c553769e04af6cd43afa8b7c3-SNAPSHOT` (same as working Fabric builds)
  - Added `mergeServiceFiles()` in ShadowJar to properly handle relocated Jackson ServiceLoader descriptors
  - Removed problematic `exclude 'META-INF/services/com.fasterxml.jackson.*'` that broke Jackson initialization
- **Jackson version conflicts**: Added proper excludes for Jackson in `dev.arbjerg:lavaplayer` to prevent conflicts with Minecraft's pinned Jackson 2.13.x

### Changed
- Repository restructured: all versions now in single `main` branch under `fabric/<version>/` and `neoforge/<version>/`
- GitHub Actions workflow added for automated matrix builds and releases
- NeoForge dependency versions updated to latest stable for each MC version:
  - 1.21.1: 21.1.10
  - 1.21.2: 21.2.1-beta
  - 1.21.3: 21.3.69
  - 1.21.4: 21.4.121
  - 1.21.5: 21.5.87
  - 1.21.6: 21.6.20-beta
  - 1.21.7: 21.7.25-beta
  - 1.21.8: 21.8.50
  - 1.21.9: 21.9.16-beta
  - 1.21.10: 21.10.50-beta
  - 1.21.11: 21.11.45

### Added
- All 11 Minecraft versions (1.21.1 – 1.21.11) for both Fabric and NeoForge in single repository
- Automated CI/CD via GitHub Actions (matrix build on tag push)
- Single release containing all 22 JARs (11 Fabric + 11 NeoForge)

## [1.0.1] - 2026-07-06

### Added
- Fabric builds for Minecraft 1.21.1–1.21.11
- NeoForge builds for Minecraft 1.21.1–1.21.11
- Improved Spotify and YouTube Music playback reliability
- Fixed custom discs not playing when YouTube rejects one playback client
- Improved jukebox playback stability and disc insert/eject handling

## [1.0.0] - 2026-06-19

### Added
- Initial release
- Burn custom audio links onto vanilla music discs
- Play through Plasmo Voice positional audio
- Spotify track link support via metadata lookup + YouTube Music matching
- YouTube, YouTube Music, SoundCloud, direct audio URLs via LavaPlayer streaming
- Russian/English server-side messages via `display.language`
- `/lazodisc burn`, `/lazodisc erase`, `/lazodisc search` commands
- Jukebox restart cooldown to prevent TPS harm from spam

### Note
Minecraft 1.21.2 not included in 1.0.0 (port not verified at the time)
