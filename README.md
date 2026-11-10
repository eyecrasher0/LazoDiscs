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

By default, these commands require operator permissions (level 2). In singleplayer, enable cheats to use them with the default settings. Server owners can change permissions, default volume, hearing range, and allowed music sources in `config/lazodiscs/config.toml`. Players can also adjust the **Discs** volume in their Plasmo Voice settings.

Use `search` for a song name and `burn` for a link. Spotify support is for individual tracks: the mod reads the track information and looks for matching audio on YouTube Music. Burning stores the selected recording URL so later plays use the same track. Playback depends on a suitable recording being available. The server needs internet access to load linked music.

## Installation

**Paper:** server plugins are available for **22 Paper versions, from 1.16.5 to 26.3**.
See the [Paper compatibility table and installation guide](paper/README.md).
They use Bukkit permissions and `plugins/LazoDiscs/config.yml`; players only need
Plasmo Voice. Install the file for your exact server version.

- **Required:** Plasmo Voice. Fabric builds also require [Fabric API](https://modrinth.com/mod/fabric-api).
- **Singleplayer:** install LazoDiscs and its dependencies in your Minecraft instance.
- **Multiplayer server:** install LazoDiscs and Plasmo Voice on the server, plus Fabric API if it uses Fabric.
- **Players:** install Plasmo Voice to hear the music. LazoDiscs is optional on clients; it removes the original vanilla song description from recorded-disc tooltips, leaving the custom title. Fabric clients need Fabric API when installing the Fabric builds.

LazoBoombox has different requirements: if you use that add-on, install LazoBoombox, LazoDiscs, and Plasmo Voice on both the server and clients.

## Supported builds

LazoDiscs **1.0.7** provides the following builds, based on the stable Plasmo Voice **2.1.17** compatibility list. Download the file for your exact Minecraft version and loader, with matching dependencies.

| Minecraft | Fabric | Forge | NeoForge |
| --- | :---: | :---: | :---: |
| 1.16.5, 1.17.1, 1.18.2 | ✓ | ✓ | — |
| 1.19, 1.19.1, 1.19.2, 1.19.3, 1.19.4 | ✓ | ✓ | — |
| 1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4 | ✓ | ✓ | — |
| 1.21, 1.21.1 | ✓ | ✓ | ✓ |
| 1.21.4, 1.21.6, 1.21.7, 1.21.8, 1.21.11 | ✓ | — | ✓ |
| 26.1, 26.1.1, 26.1.2, 26.2, 26.3 | ✓ | — | ✓ |

Experimental playback on Sable / Create Aeronautics moving platforms is available through the server configuration and is disabled by default. It requires a compatible version of the platform mod.

[Modrinth](https://modrinth.com/mod/lazodiscs) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/lazodiscs) · [GitHub releases](https://github.com/EyeCrasher07/LazoDiscs/releases) · [Report a bug](https://github.com/EyeCrasher07/LazoDiscs/issues)

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

По умолчанию команды доступны операторам с уровнем прав 2. В одиночной игре для их использования с настройками по умолчанию нужны включённые читы. Владелец сервера может изменить права, стандартную громкость, радиус слышимости и разрешённые источники музыки в `config/lazodiscs/config.toml`. Игроки также могут настроить громкость канала **Discs** в Plasmo Voice.

Для названия песни используй `search`, а для ссылки — `burn`. Поддерживаются ссылки на отдельные треки Spotify: мод получает сведения о треке и ищет подходящее аудио на YouTube Music. При записи сохраняется ссылка на выбранное аудио, чтобы повторные запуски воспроизводили тот же трек. Воспроизведение зависит от доступности подходящей записи. Для загрузки музыки серверу нужен доступ к интернету.

### Установка

**Paper:** доступны серверные плагины для **22 версий Paper, от 1.16.5 до 26.3**.
[Совместимость и установка](paper/README.md). Скачай файл для точной версии сервера.
Настройки — в `plugins/LazoDiscs/config.yml`, доступ — через Bukkit-права.
Игрокам нужен только Plasmo Voice.

- **Зависимости:** Plasmo Voice для всех загрузчиков; для Fabric также нужен Fabric API.
- **Одиночная игра:** установи LazoDiscs и зависимости в свой экземпляр Minecraft.
- **Сервер:** установи LazoDiscs и Plasmo Voice, а для Fabric — ещё и Fabric API.
- **Игроки:** для музыки нужен Plasmo Voice. LazoDiscs на клиенте необязателен: он убирает стандартное название песни Minecraft из подсказки к записанной пластинке, оставляя пользовательское название. При установке Fabric-сборок на клиент также нужен Fabric API.

У дополнения LazoBoombox другие требования: для него LazoBoombox, LazoDiscs и Plasmo Voice нужно установить и на сервер, и на клиенты игроков.

### Поддерживаемые сборки

В LazoDiscs **1.0.7** доступны следующие сборки по списку совместимости стабильного Plasmo Voice **2.1.17**. Выбирай файл и зависимости для своей точной версии Minecraft и загрузчика.

| Minecraft | Fabric | Forge | NeoForge |
| --- | :---: | :---: | :---: |
| 1.16.5, 1.17.1, 1.18.2 | ✓ | ✓ | — |
| 1.19, 1.19.1, 1.19.2, 1.19.3, 1.19.4 | ✓ | ✓ | — |
| 1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4 | ✓ | ✓ | — |
| 1.21, 1.21.1 | ✓ | ✓ | ✓ |
| 1.21.4, 1.21.6, 1.21.7, 1.21.8, 1.21.11 | ✓ | — | ✓ |
| 26.1, 26.1.1, 26.1.2, 26.2, 26.3 | ✓ | — | ✓ |

Экспериментальное воспроизведение на движущихся платформах Sable / Create Aeronautics включается в конфиге сервера и по умолчанию отключено. Для него нужна совместимая версия мода платформ.

[Modrinth](https://modrinth.com/mod/lazodiscs) · [CurseForge](https://www.curseforge.com/minecraft/mc-mods/lazodiscs) · [Релизы GitHub](https://github.com/EyeCrasher07/LazoDiscs/releases) · [Сообщить об ошибке](https://github.com/EyeCrasher07/LazoDiscs/issues)
