package com.eyecrasher.lazodiscs.command;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.data.CustomDiscData;
import com.eyecrasher.lazodiscs.data.DiscDataUtil;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.eyecrasher.lazodiscs.voice.AudioLoadExecutor;
import com.eyecrasher.lazodiscs.voice.LavaPcmFeeder;
import com.eyecrasher.lazodiscs.voice.LazoDiscsVoiceAddon;
import com.eyecrasher.lazodiscs.voice.SpotifyTitleResolver;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

public final class LazoDiscsCommands implements Listener {
    private static final List<String> ACTIONS = List.of("burn", "erase", "search");
    private final LazoDiscs plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public LazoDiscsCommands(LazoDiscs plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !ACTIONS.contains(args[0].toLowerCase(Locale.ROOT))) {
            sender.sendMessage(
                    Component.text(
                            "/"
                                    + LazoDiscsConfig.COMMAND_ALIAS.get()
                                    + " <burn <url> [title] | erase | search <song>>",
                            NamedTextColor.GOLD));
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!sender.hasPermission("lazodiscs." + action)) {
            sender.sendMessage(LazoDiscsText.noPermission().color(NamedTextColor.RED));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(LazoDiscsText.playersOnly().color(NamedTextColor.RED));
            return;
        }
        if (action.equals("erase")) {
            erase(player);
            return;
        }
        if (pending.containsKey(player.getUniqueId())) {
            player.sendMessage(Component.text(LazoDiscsText.audioLoadBusy(), NamedTextColor.RED));
            return;
        }
        if (action.equals("burn")) burn(player, args);
        else search(player, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
    }

    public boolean canUse(CommandSender sender) {
        return ACTIONS.stream().anyMatch(action -> sender.hasPermission("lazodiscs." + action));
    }

    public Collection<String> suggest(CommandSender sender, String[] args) {
        if (args.length > 1) return List.of();
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return ACTIONS.stream()
                .filter(a -> a.startsWith(prefix) && sender.hasPermission("lazodiscs." + a))
                .toList();
    }

    private void burn(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(LazoDiscsText.burnUsage().color(NamedTextColor.RED));
            return;
        }
        ItemStack original = player.getInventory().getItemInMainHand();
        if (!DiscDataUtil.isMusicDisc(original) || original.getAmount() != 1) {
            player.sendMessage(LazoDiscsText.holdDisc().color(NamedTextColor.RED));
            return;
        }
        String url;
        try {
            url = DiscDataUtil.validateUrl(args[1]);
        } catch (IllegalArgumentException error) {
            player.sendMessage(
                    LazoDiscsText.invalidUrl(error.getMessage()).color(NamedTextColor.RED));
            return;
        }
        String hint =
                args.length > 2
                        ? DiscDataUtil.sanitizeTitle(
                                String.join(" ", Arrays.copyOfRange(args, 2, args.length)))
                        : null;
        Pending work = new Pending(original.clone(), player.getInventory().getHeldItemSlot());
        pending.put(player.getUniqueId(), work);
        player.sendMessage(LazoDiscsText.resolvingTrack().color(NamedTextColor.GRAY));
        plugin.markAudioUsed();
        work.future =
                AudioLoadExecutor.submit(
                        () -> {
                            try {
                                var resolved = LavaPcmFeeder.resolveTrack(url, hint);
                                String playback =
                                        SpotifyTitleResolver.looksLikeSpotify(url)
                                                ? resolved.url()
                                                : url;
                                String title =
                                        hint != null
                                                ? hint
                                                : DiscDataUtil.sanitizeTitle(resolved.title());
                                plugin.onMain(
                                        () -> {
                                            if (!take(player, work)) return;
                                            ItemStack current =
                                                    player.getInventory().getItemInMainHand();
                                            if (player.getInventory().getHeldItemSlot() != work.slot
                                                    || !current.equals(work.snapshot)) {
                                                player.sendMessage(
                                                        LazoDiscsText.holdDisc()
                                                                .color(NamedTextColor.RED));
                                                return;
                                            }
                                            if (!player.hasPermission("lazodiscs.burn")) {
                                                player.sendMessage(
                                                        LazoDiscsText.noPermission()
                                                                .color(NamedTextColor.RED));
                                                return;
                                            }
                                            try {
                                                DiscDataUtil.validateUrl(url);
                                                ItemStack recorded = current.clone();
                                                DiscDataUtil.write(
                                                        recorded,
                                                        new CustomDiscData(
                                                                playback,
                                                                title,
                                                                LazoDiscsConfig.DEFAULT_RANGE.get(),
                                                                LazoDiscsConfig.DEFAULT_VOLUME
                                                                        .get()
                                                                        .floatValue(),
                                                                UUID.randomUUID()));
                                                player.getInventory().setItemInMainHand(recorded);
                                                player.sendMessage(LazoDiscsText.burned(title));
                                            } catch (IllegalArgumentException error) {
                                                player.sendMessage(
                                                        LazoDiscsText.invalidUrl(error.getMessage())
                                                                .color(NamedTextColor.RED));
                                            }
                                        });
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            } catch (Exception | LinkageError error) {
                                LazoDiscs.LOGGER.warn(
                                        "Could not burn '{}': {}", url, error.toString());
                                fail(
                                        player,
                                        work,
                                        LazoDiscsText.burnFailed(
                                                LazoDiscsVoiceAddon.messageOf(error)));
                            }
                        },
                        () ->
                                fail(
                                        player,
                                        work,
                                        LazoDiscsText.burnFailed(LazoDiscsText.audioLoadBusy())));
    }

    private void erase(Player player) {
        cancel(player.getUniqueId());
        ItemStack item = player.getInventory().getItemInMainHand();
        if (DiscDataUtil.read(item).isEmpty()) {
            player.sendMessage(LazoDiscsText.notLazoDisc().color(NamedTextColor.RED));
            return;
        }
        ItemStack erased = item.clone();
        DiscDataUtil.clear(erased);
        player.getInventory().setItemInMainHand(erased);
        player.sendMessage(LazoDiscsText.dataRemoved());
    }

    private void search(Player player, String query) {
        if (query.isBlank()) {
            player.sendMessage(LazoDiscsText.searchUsage().color(NamedTextColor.RED));
            return;
        }
        if (query.length() > 256 || looksLikeLink(query)) {
            player.sendMessage(LazoDiscsText.searchNamesOnly().color(NamedTextColor.RED));
            return;
        }
        Pending work = new Pending(null, -1);
        pending.put(player.getUniqueId(), work);
        player.sendMessage(LazoDiscsText.searching(query).color(NamedTextColor.GRAY));
        plugin.markAudioUsed();
        work.future =
                AudioLoadExecutor.submit(
                        () -> {
                            try {
                                var results = LavaPcmFeeder.search(query, 5);
                                plugin.onMain(
                                        () -> {
                                            if (!take(player, work)) return;
                                            if (!player.hasPermission("lazodiscs.search")) return;
                                            if (results.isEmpty()) {
                                                player.sendMessage(
                                                        LazoDiscsText.noSongsFound(query));
                                                return;
                                            }
                                            player.sendMessage(
                                                    LazoDiscsText.searchHeader(query)
                                                            .color(NamedTextColor.GOLD));
                                            int index = 0;
                                            for (var result : results) {
                                                String url;
                                                try {
                                                    url = DiscDataUtil.validateUrl(result.url());
                                                } catch (IllegalArgumentException forbidden) {
                                                    continue;
                                                }
                                                String title =
                                                        DiscDataUtil.sanitizeTitle(result.title());
                                                String command =
                                                        "/"
                                                                + LazoDiscsConfig.COMMAND_ALIAS
                                                                        .get()
                                                                + " burn "
                                                                + url
                                                                + " "
                                                                + title;
                                                long seconds =
                                                        Math.max(0, result.lengthMs() / 1000);
                                                player.sendMessage(
                                                        Component.text(
                                                                        ++index + ". ",
                                                                        NamedTextColor.DARK_GRAY)
                                                                .append(
                                                                        Component.text(
                                                                                        title,
                                                                                        NamedTextColor
                                                                                                .AQUA)
                                                                                .clickEvent(
                                                                                        ClickEvent
                                                                                                .suggestCommand(
                                                                                                        command))
                                                                                .hoverEvent(
                                                                                        HoverEvent
                                                                                                .showText(
                                                                                                        Component
                                                                                                                .text(
                                                                                                                        LazoDiscsText
                                                                                                                                .clickToPaste(
                                                                                                                                        command)))))
                                                                .append(
                                                                        Component.text(
                                                                                " - "
                                                                                        + DiscDataUtil
                                                                                                .sanitizeTitle(
                                                                                                        result
                                                                                                                .author())
                                                                                        + " ("
                                                                                        + seconds
                                                                                                / 60
                                                                                        + ":"
                                                                                        + String
                                                                                                .format(
                                                                                                        Locale
                                                                                                                .ROOT,
                                                                                                        "%02d",
                                                                                                        seconds
                                                                                                                % 60)
                                                                                        + ")",
                                                                                NamedTextColor
                                                                                        .GRAY)));
                                            }
                                            if (index == 0)
                                                player.sendMessage(
                                                        LazoDiscsText.noSongsFound(query));
                                        });
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            } catch (Exception | LinkageError error) {
                                fail(
                                        player,
                                        work,
                                        LazoDiscsText.searchFailed(
                                                LazoDiscsVoiceAddon.messageOf(error)));
                            }
                        },
                        () ->
                                fail(
                                        player,
                                        work,
                                        LazoDiscsText.searchFailed(LazoDiscsText.audioLoadBusy())));
    }

    private static boolean looksLikeLink(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.contains("://")
                || lower.startsWith("www.")
                || lower.contains("youtube.com/")
                || lower.contains("youtu.be/")
                || lower.contains("spotify.com/")
                || lower.contains("soundcloud.com/")
                || SpotifyTitleResolver.looksLikeSpotify(query);
    }

    private boolean take(Player player, Pending expected) {
        return pending.remove(player.getUniqueId(), expected)
                && player.isOnline()
                && plugin.getServer().getPlayer(player.getUniqueId()) == player;
    }

    private void fail(Player player, Pending work, Component message) {
        plugin.onMain(
                () -> {
                    if (take(player, work)) player.sendMessage(message.color(NamedTextColor.RED));
                });
    }

    private void cancel(UUID player) {
        Pending work = pending.remove(player);
        if (work != null && work.future != null) work.future.cancel(true);
    }

    private void cancelBurn(UUID player) {
        Pending work = pending.get(player);
        if (work != null && work.snapshot != null) {
            cancel(player);
            Player online = plugin.getServer().getPlayer(player);
            if (online != null)
                online.sendMessage(LazoDiscsText.holdDisc().color(NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent event) {
        cancelBurn(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        cancelBurn(event.getWhoClicked().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        cancelBurn(event.getWhoClicked().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        cancelBurn(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) {
        cancelBurn(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    public void shutdown() {
        List.copyOf(pending.keySet()).forEach(this::cancel);
    }

    private static final class Pending {
        private final ItemStack snapshot;
        private final int slot;
        private Future<?> future;

        private Pending(ItemStack snapshot, int slot) {
            this.snapshot = snapshot;
            this.slot = slot;
        }
    }
}
