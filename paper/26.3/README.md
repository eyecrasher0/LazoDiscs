# LazoDiscs for Paper — detailed guide

For all 22 versions and legacy behavior differences, see [Paper compatibility](../README.md).

[English](#installation) · [Русский](#установка)

Record YouTube, YouTube Music, SoundCloud, Spotify track links, or direct audio URLs
onto Minecraft music discs. Playback uses the Plasmo Voice **Discs** volume channel
and originates at the jukebox. Spotify tracks resolve to a matching YouTube Music
recording, whose URL is saved on the disc.

## Installation

1. Run a [supported Paper version](../README.md#supported-targets) on its required Java version.
2. Install the Paper build of [Plasmo Voice](https://modrinth.com/plugin/plasmo-voice).
3. Put `lazodiscs-paper-1.0.7+<minecraft>.jar` in `plugins/` and restart the server.
4. Players need the Plasmo Voice client mod to hear music. No LazoDiscs client mod
   or resource pack is needed. Configure Plasmo Voice's UDP connection as usual.

Use the shaded release JAR; `-thin`, `-sources`, and `-integration` JARs are not
server releases. This module targets Paper; Spigot and Folia have not been verified.

Hold **one** vanilla music disc in your main hand:

| Command | Action | Permission (default) |
| --- | --- | --- |
| `/lazodisc burn <url> [title]` | Record a link, optionally with your title. | `lazodiscs.burn` (op) |
| `/lazodisc search <song name>` | Search YouTube Music; click a result to suggest a burn command. | `lazodiscs.search` (op) |
| `/lazodisc erase` | Remove the recording and restore the previous name and song. | `lazodiscs.erase` (op) |
| Insert a recorded disc | Play its music from the jukebox. | `lazodiscs.play` (everyone) |

Keep holding the disc until burning finishes. Changing slots, dropping it,
moving items in an inventory, swapping hands, or disconnecting cancels the burn.
Each player can have one pending burn/search. Network work runs off the server
thread with a bounded queue; player/item state and permissions are checked again
before writing the recording.

On 1.19.4+, hoppers insert recorded discs and extract them after playback ends. Ejecting,
breaking or exploding a jukebox, unloading its chunk/world, or disabling the
plugin stops its audio. Recorded discs in reloaded chunks start from the beginning.
Original comparator strength, unrelated item data and lore are preserved.
On 1.21.4+, the vanilla song is replaced by a silent registry entry while the disc is recorded. See the compatibility guide for legacy behavior.

## Configuration

Settings are in `plugins/LazoDiscs/config.yml`; restart after changing them.

- `commandAlias`: root command; `/lazodisc` also remains an alias.
- `playback`: default/max hearing range, volume, client channel default, and the
  interval for validating active jukeboxes. The timer only checks active sources.
- `lavaplayer`: load timeout, Spotify matching, track length limit, and concurrent
  audio loads.
- `security`: HTTP/HTTPS switches and a domain allowlist. HTTP is disabled by
  default. An empty allowlist permits any domain; Spotify matching also needs
  the selected YouTube recording's domain. The policy is checked when burning
  and each time playback loads a track.
- `language`: server message language, with English defaults. Edit/add
  `plugins/LazoDiscs/lang/<language>.toml`; message file edits reload automatically.
  Permissions use Bukkit permission nodes rather than Minecraft permission levels.

The server needs outbound internet access for online sources. Their availability
and service restrictions can affect searches and playback.

Paper recordings use Bukkit persistent item data and server registry songs. They
are independent of Fabric/Forge/NeoForge recordings; this port does not migrate
modded-world discs. Keep LazoDiscs installed in worlds that contain its recordings;
erase them before removing it. LazoBoombox, Sophisticated Backpacks and Sable/Create
Aeronautics integration belong to the mod builds and are not part of this plugin.

## Build and verify

From this directory, with `JAVA_HOME` pointing to a JDK 25 (the default target is 26.3):

```sh
./gradlew build integrationPlugin
python3 tools/integration.py
python3 tools/integration.py --plasmo beta
```

The build shares the existing Fabric 26.3 audio resolver, Spotify matching and
SoundCloud AAC sources, while using Paper adapters for commands, item data,
configuration, jukebox events and Plasmo world/source lifecycle. No Minecraft
internals or packet interception are used by the plugin.

The integration runner downloads hash-pinned Paper builds from `../targets.json` and Plasmo
Voice **2.1.17** (or **2.2.0-beta.3**), accepts the Minecraft EULA for the disposable
test instance, binds Minecraft/voice/fixture HTTP to loopback, generates its own
WAV, and stops the server when done. Run it only if you agree to the
[Minecraft EULA](https://www.minecraft.net/eula). Logs, worlds and JSON results stay
under `build/integration/`. It tests real item serialization, all vanilla discs,
comparator strength, exact world resolution, HTTP-to-Opus decoding, hoppers,
natural completion, ejection during a delayed load, chunk unloading, load failure,
and cancelled inventory events. It does not replace a listening test with a
Plasmo Voice client or guarantee availability of external music services.

The automated matrix runs real Paper servers for each advertised target. It checks
actual world unloading and plugin shutdown with active loads: sources must be
removed, pending commands cleared, aliases unregistered and loader workers stopped.

## Установка

Нужны [поддерживаемые Paper и Java](../README.md) и серверный плагин **Plasmo Voice**.
Положи `lazodiscs-paper-1.0.7+<minecraft>.jar` в `plugins/` и перезапусти сервер.
Игрокам нужен клиентский Plasmo Voice; LazoDiscs на клиенте и ресурспак не требуются.

Возьми одну обычную пластинку в основную руку:

- `/lazodisc burn <ссылка> [название]` — записать трек.
- `/lazodisc search <название песни>` — найти треки; нажатие подставляет команду записи.
- `/lazodisc erase` — стереть запись и вернуть прежнее имя и стандартную песню.

Запись, поиск и стирание по умолчанию доступны операторам. Для выдачи доступа
используй `lazodiscs.burn`, `lazodiscs.search`, `lazodiscs.erase`. Воспроизведение
разрешено всем через `lazodiscs.play`. Настройки — в `plugins/LazoDiscs/config.yml`;
громкость канала **Discs** каждый игрок меняет у себя в Plasmo Voice.

Держи пластинку до завершения записи: смена слота, перемещение предметов,
выбрасывание, смена рук и выход отменяют операцию. Воронки работают; звук
останавливается при извлечении, разрушении проигрывателя и выгрузке чанка.
После повторной загрузки чанка трек начинается заново.

Пластинки Paper хранят данные отдельно от модовых; переноса старых модовых
пластинок нет. LazoBoombox и совместимость с модовыми рюкзаками/платформами сюда
не входят. Перед удалением плагина сотри его записи с пластинок.
