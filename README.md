# LazoDiscs

[English](#lazodiscs) · [Русский](#русский)

Record music links onto Minecraft's music discs, then play the tracks in a jukebox. [Plasmo Voice](https://modrinth.com/plugin/plasmo-voice) makes the sound come from the jukebox, so nearby players can hear it too.

## Features

- Record music from YouTube, YouTube Music, SoundCloud, or a direct playable audio URL onto a vanilla music disc.
- Use Spotify track links to find a matching recording on YouTube Music.
- Search for songs in chat and click a result to fill in the recording command.
- Give a disc a custom track title, erase its recording, and reuse it.
- Let server owners configure playback volume, hearing range, and command access.

## Getting started

1. Hold a vanilla music disc in your main hand.
2. Run `/lazodisc burn <url> [title]` and keep holding the disc until recording finishes. The title is optional.
3. Put the recorded disc in a jukebox to play it.

| Command | What it does |
| --- | --- |
| `/lazodisc burn <url> [title]` | Records the linked track onto the disc you are holding. |
| `/lazodisc search <song name>` | Searches for songs. Click a result, then send the suggested recording command while holding a disc. |
| `/lazodisc erase` | Removes the custom recording from the disc you are holding. |

By default, these commands require operator permissions (level 2). In singleplayer, enable cheats to use them with the default settings. Server owners can change permissions, default volume, hearing range, and allowed music sources in `config/lazodiscs/config.toml`. With Voice 2, players can adjust the **Discs** volume in Plasmo Voice settings. Voice 1 uses its general listening volume.

Use `search` for a song name and `burn` for a link. Spotify support is for individual tracks: the mod reads the track information and looks for matching audio on YouTube Music. Burning stores the selected recording URL so later plays use the same track. Playback depends on a suitable recording being available. The server needs internet access to load linked music.

## Installation

- **Fabric / Forge / NeoForge:** install the file for your exact Minecraft version and loader. Fabric also needs Fabric API.
- **Paper:** prefer the native Paper file for your exact version. Keep that distribution when upgrading an existing native Paper installation.
- **Spigot / Purpur / Folia and additional Paper versions:** install `lazodiscs-bukkit-1.0.8.jar` in `plugins`. Install only one LazoDiscs plugin. Configuration is in `plugins/LazoDiscs/config.yml`; access uses Bukkit permissions.
- **Voice 2:** listeners need Plasmo Voice. LazoDiscs is optional on clients and improves recorded-disc tooltips.
- **Voice 1:** listeners need the matching client LazoDiscs mod or the Voice 1 client helper described in the compatibility guide. Use Voice 1 only where the guide selects it.
- **Singleplayer:** install LazoDiscs and its matching dependencies in the Minecraft instance.

[The full compatibility and installation guide](docs/compatibility-1.0.8.md) links the exact official Voice dependency for each platform/version. It covers **35 Fabric, 16 Forge, 17 NeoForge, 32 Paper, 35 Spigot, 30 Purpur and 13 Folia targets**. Voice 2 is preferred; Voice 1 supplies older targets and stock Bukkit 1.16.5, whose JVM limit prevents the official Voice 2 plugin from starting.

LazoBoombox has different requirements: if you use that add-on, install LazoBoombox, LazoDiscs, and Plasmo Voice on both the server and clients.

## Supported builds

LazoDiscs **1.0.8** covers the compatible versions from Minecraft 1.16.5 to 26.3, including archived stable Voice 2.0/2.1 releases and Voice 1 targets. The [complete table](docs/compatibility-1.0.8.md) lists every patch version and platform separately. Minecraft 1.7.10, 1.8.9 and 1.12.2 have no official Plasmo Voice release and are excluded.

The YouTube sign-in refusal now gives players a short English message and emits a concise console warning. Other failures keep their diagnostic details. Dependency updates retain the Java 8/16 compatibility needed by older mod builds.

Signed links remain intact on discs. Access cookies are isolated per recording load and are not saved on discs. Spotify matching requires equal song titles after known display labels are removed; a different song with a longer title is rejected.

Experimental playback on Sable / Create Aeronautics moving platforms is available through the server configuration and is disabled by default. It requires a compatible version of the platform mod.

[Modrinth](https://modrinth.com/mod/lazodiscs) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/lazodiscs) · [GitHub releases](https://github.com/eyecrasher0/LazoDiscs/releases) · [Report a bug](https://github.com/eyecrasher0/LazoDiscs/issues)

---

## Русский

LazoDiscs позволяет записать свою музыку на обычную пластинку Minecraft по ссылке и слушать её в проигрывателе. Звук передаётся через Plasmo Voice и исходит от проигрывателя, поэтому музыку слышат и игроки поблизости.

### Возможности

- Запись музыки с YouTube, YouTube Music, SoundCloud и по прямым ссылкам на доступное аудио.
- Поиск подходящей записи на YouTube Music по ссылке на трек Spotify.
- Поиск песен в чате: нажатие на результат подставляет команду записи.
- Собственное название трека, стирание записи и повторное использование пластинки.
- Настройка громкости, радиуса слышимости и доступа к командам владельцем сервера.

### Как пользоваться

1. Возьми обычную музыкальную пластинку в основную руку.
2. Введи `/lazodisc burn <ссылка> [название]` и держи пластинку до окончания записи. Название можно не указывать.
3. Вставь записанную пластинку в проигрыватель.

| Команда | Что делает |
| --- | --- |
| `/lazodisc burn <ссылка> [название]` | Записывает трек по ссылке на пластинку в руке. |
| `/lazodisc search <название песни>` | Ищет песни. Нажми на результат и отправь предложенную команду записи, держа пластинку в руке. |
| `/lazodisc erase` | Стирает пользовательскую запись с пластинки в руке. |

По умолчанию команды доступны операторам с уровнем прав 2. В одиночной игре для их использования с настройками по умолчанию нужны включённые читы. Владелец сервера может изменить права, стандартную громкость, радиус слышимости и разрешённые источники музыки в `config/lazodiscs/config.toml`. В Voice 2 игроки могут настроить громкость канала **Discs**. Voice 1 использует общую громкость прослушивания.

Для названия песни используй `search`, а для ссылки — `burn`. Поддерживаются ссылки на отдельные треки Spotify: мод получает сведения о треке и ищет подходящее аудио на YouTube Music. При записи сохраняется ссылка на выбранное аудио, чтобы повторные запуски воспроизводили тот же трек. Воспроизведение зависит от доступности подходящей записи. Для загрузки музыки серверу нужен доступ к интернету.

### Установка

- **Fabric / Forge / NeoForge:** выбери файл для точной версии Minecraft и загрузчика. Для Fabric также нужен Fabric API.
- **Paper:** используй нативную Paper-сборку для своей версии, если она есть. При обновлении существующей нативной установки сохрани этот вариант.
- **Spigot / Purpur / Folia и дополнительные версии Paper:** установи `lazodiscs-bukkit-1.0.8.jar` в `plugins`. Одновременно должен быть установлен только один плагин LazoDiscs. Конфиг — `plugins/LazoDiscs/config.yml`, доступ — через Bukkit-права.
- **Voice 2:** игрокам нужен Plasmo Voice. Клиентский LazoDiscs необязателен и улучшает подсказки записанных пластинок.
- **Voice 1:** игрокам нужен соответствующий клиентский LazoDiscs или отдельный модуль Voice 1 из инструкции. Выбирай поколение Voice по таблице.
- **Одиночная игра:** установи LazoDiscs и соответствующие зависимости в экземпляр Minecraft.

[Полная таблица и инструкция](docs/compatibility-1.0.8.md) содержит ссылки на точные версии Voice для каждой платформы: **35 Fabric, 16 Forge, 17 NeoForge, 32 Paper, 35 Spigot, 30 Purpur и 13 Folia**. Приоритет у Voice 2. Voice 1 используется на старых версиях и Bukkit 1.16.5, где ограничение Java не позволяет запустить официальный плагин Voice 2.

У дополнения LazoBoombox другие требования: для него LazoBoombox, LazoDiscs и Plasmo Voice нужно установить и на сервер, и на клиенты игроков.

### Поддерживаемые сборки

LazoDiscs **1.0.8** охватывает совместимые версии Minecraft от 1.16.5 до 26.3, включая архивные стабильные Voice 2.0/2.1 и версии с Voice 1. В [полной таблице](docs/compatibility-1.0.8.md) каждый патч и платформа указаны отдельно. Для 1.7.10, 1.8.9 и 1.12.2 официального Plasmo Voice нет, поэтому они исключены.

При отказе YouTube с требованием входа игрок получает короткое английское сообщение, а консоль — краткое предупреждение. Подробности остальных ошибок сохранены. Обновлённые зависимости сохраняют необходимую старым модам совместимость с Java 8/16.

Подписанные ссылки сохраняются в пластинках полностью. Cookies доступа изолированы для каждой загрузки записи и не сохраняются в пластинке. При подборе Spotify название песни должно совпадать после удаления известных служебных пометок; другая песня с более длинным названием отклоняется.

Экспериментальное воспроизведение на движущихся платформах Sable / Create Aeronautics включается в конфиге сервера и по умолчанию отключено. Для него нужна совместимая версия мода платформ.

[Modrinth](https://modrinth.com/mod/lazodiscs) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/lazodiscs) · [Релизы GitHub](https://github.com/eyecrasher0/LazoDiscs/releases) · [Сообщить об ошибке](https://github.com/eyecrasher0/LazoDiscs/issues)
