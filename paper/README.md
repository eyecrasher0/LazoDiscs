# LazoDiscs 1.0.7 — Paper

[English](#installation) · [Русский](#установка)

Record music links onto vanilla Minecraft discs and play positional audio through
Plasmo Voice. YouTube, YouTube Music, SoundCloud, Spotify track matching and direct
audio URLs use the same audio engine as the 1.0.7 mod builds.

## Installation

1. Pick the JAR for your **exact Paper version** from the table below.
2. Use Java **17** for 1.17.1–1.20.4, **21** for 1.21–1.21.11, or **25** for 26.x.
3. Install the server plugin **Plasmo Voice 2.1.17**, then LazoDiscs in `plugins/`.
4. Restart the server. Players need the **Plasmo Voice client mod** and a working
   voice connection. They do not need a LazoDiscs client mod or a resource pack.

This release targets **Paper**. Spigot, Folia and third-party forks have not been
verified. Use the shaded release JAR, not `-thin`, `-sources` or `-integration` files.

## Supported targets

The 21 targets cover available Paper versions that start normally on the documented Java runtime with the
Paper/Spigot build of stable Plasmo Voice 2.1.17. Exact test builds and download
checksums are recorded in [`targets.json`](targets.json). Paper did not publish
standalone 1.21.2 or 26.1 server builds, so they are not advertised here.

| Paper | Java runtime | Jukebox adapter |
| --- | ---: | --- |
| 1.17.1, 1.18.2 | 17 | Legacy item PDC |
| 1.19.2, 1.19.4, 1.20.1, 1.20.4 | 17 | Legacy item PDC |
| 1.21, 1.21.1, 1.21.3 | 21 | Legacy item PDC |
| 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11 | 21 | Silent song datapack |
| 26.1.1, 26.1.2, 26.2 | 25 | Silent song datapack |
| 26.3 | 25 | Song registry |

Files are named `lazodiscs-paper-1.0.7+<minecraft>.jar`.

Paper 1.16.5 is not included: stock Paper requires Java 16 or older, while the
current Plasmo Voice server plugin requires Java 17. This release does not require
a Java-version override or a modified dependency.

## Commands and permissions

Hold **one** vanilla music disc in your main hand.

| Command/action | Permission | Default |
| --- | --- | --- |
| `/lazodisc burn <url> [title]` | `lazodiscs.burn` | Operators |
| `/lazodisc search <song name>` | `lazodiscs.search` | Operators |
| `/lazodisc erase` | `lazodiscs.erase` | Operators |
| Insert a recorded disc | `lazodiscs.play` | Everyone |

Search results suggest a recording command when clicked. Keep holding the disc
until recording finishes. Moving it, changing slots, dropping it, swapping hands
or disconnecting cancels the operation. Ejection, destruction, chunk/world unload
and plugin shutdown stop custom audio and pending loads.

## Configuration and compatibility

Configure `plugins/LazoDiscs/config.yml`: command alias, language, volume/range,
audio load concurrency and timeout, Spotify matching, and HTTP/HTTPS/domain policy.
HTTP is disabled by default. Language files in `plugins/LazoDiscs/lang/` reload
automatically; other settings require a restart. Players can adjust **Discs** in
their Plasmo Voice volume settings. See the [detailed configuration guide](26.3/README.md).

Paper 1.21.4+ replaces the vanilla song with a silent, long-lived registry entry,
preserving comparator strength and the native playing state until custom audio ends.
The bundled datapack on 1.21.4–26.2 is installed and enabled by the plugin bootstrap.

On 1.17.1–1.21.3, the plugin keeps the original item type and stops vanilla audio.
Native hopper insertion/extraction is supported from **1.19.4**, including retaining
the disc while custom audio loads/plays. Earlier game versions have no vanilla
jukebox inventory. Legacy playback does not keep the native "playing" redstone
signal for the custom track's duration; a hopper insertion can briefly begin the
vanilla sound before it is stopped on the next tick. Manual insertion sends its
start/stop in the same tick. A real-client listening test is still recommended.

Recordings are separate from Fabric/Forge/NeoForge recordings. This release does
not migrate modded-world discs. When moving legacy recordings to a modern Paper
adapter, erase and re-burn them to install the silent song. Erase recordings before
removing the modern plugin/datapack. LazoBoombox, Sophisticated Backpacks and
Sable/Create Aeronautics integrations belong to the mod builds.

## Build and verification

The build module remains in `paper/26.3`; it shares audio sources with Fabric 26.3
and selects only the relevant Paper adapters. With a **JDK 25**:

```sh
./paper/26.3/gradlew -p paper/26.3 --project-cache-dir paper/26.3/build/1.21.4/gradle-cache -PminecraftTarget=1.21.4 build integrationPlugin
python3 paper/26.3/tools/integration.py --minecraft 1.21.4 --java /path/to/java21/bin/java
python3 paper/26.3/tools/matrix.py --jobs 2
```

For the matrix, set `JAVA_17_HOME`, `JAVA_21_HOME` and `JAVA_25_HOME` (or the standard
`JAVA_HOME_17_X64` etc.). `--plasmo beta --test-only` tests existing JARs with Plasmo
Voice 2.2.0-beta.3; use it only for 1.21+ targets advertised by that beta.

The integration runner accepts the [Minecraft EULA](https://www.minecraft.net/eula)
only for disposable test servers, binds game/voice/fixture HTTP to loopback, creates
its own WAV, and stops the server. Tests cover real item serialization, vanilla
discs, URL policy, HTTP-to-Opus decoding, exact world lookup, extraction during
pending loads, failure/unload cleanup and plugin shutdown. Modern adapters also
test native playing/comparator data and hopper cancellation; legacy adapters test
permissions, protected insertion, inventory consumption, duplicate hand events and
native hoppers where available. Logs and results remain under `build/<version>/`.
These checks do not replace listening with a game client or guarantee availability
of public music services.

## Установка

Нужны Paper точной версии из таблицы, указанная версия Java и серверный
**Plasmo Voice 2.1.17**. Положи `lazodiscs-paper-1.0.7+<версия>.jar` в `plugins/`
и перезапусти сервер. Игрокам нужен только клиентский Plasmo Voice.

Держи одну пластинку в основной руке: `/lazodisc burn <ссылка> [название]` — запись,
`/lazodisc search <песня>` — поиск, `/lazodisc erase` — стирание. Права и настройки
приведены выше. Настройки — в `plugins/LazoDiscs/config.yml`.

На 1.21.4+ используются бесшумные песни; на старых версиях плагин останавливает
ванильный звук. Воронки поддерживаются с 1.19.4; на старом адаптере ванильный сигнал
«проигрывается» не удерживается на всю длительность трека, а при вставке воронкой
ванильная песня может кратко начаться до остановки. При переносе старых Paper-
пластинок на современный адаптер сотри и запиши их заново. Перенос модовых
пластинок и интеграции LazoBoombox/рюкзаков/подвижных платформ в этот плагин не входят.
