package com.eyecrasher.lazodiscs.command;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.voice.YoutubeLoginFailure;
import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.data.CustomDiscData;
import com.eyecrasher.lazodiscs.data.DiscDataUtil;
import com.eyecrasher.lazodiscs.server.LazoDiscsPermissions;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.eyecrasher.lazodiscs.voice.AudioLoadExecutor;
import com.eyecrasher.lazodiscs.voice.LavaPcmFeeder;
import com.eyecrasher.lazodiscs.voice.SpotifyTitleResolver;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class LazoDiscsCommands {
    private static final int SEARCH_MAX_RESULTS = 5;

    private LazoDiscsCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> register(dispatcher));
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        String alias = LazoDiscsConfig.COMMAND_ALIAS.get();
        if (alias == null || alias.isBlank()) alias = "lazodisc";
        alias = alias.trim();
        dispatcher.register(
                Commands.literal(alias)
                        .then(
                                Commands.literal("burn")
                                        .executes(ctx -> burn(ctx.getSource(), ""))
                                        .then(
                                                Commands.argument(
                                                                "input",
                                                                StringArgumentType.greedyString())
                                                        .executes(
                                                                ctx ->
                                                                        burn(
                                                                                ctx.getSource(),
                                                                                StringArgumentType
                                                                                        .getString(
                                                                                                ctx,
                                                                                                "input")))))
                        .then(Commands.literal("erase").executes(ctx -> erase(ctx.getSource())))
                        .then(
                                Commands.literal("search")
                                        .executes(ctx -> search(ctx.getSource(), ""))
                                        .then(
                                                Commands.argument(
                                                                "query",
                                                                StringArgumentType.greedyString())
                                                        .executes(
                                                                ctx ->
                                                                        search(
                                                                                ctx.getSource(),
                                                                                StringArgumentType
                                                                                        .getString(
                                                                                                ctx,
                                                                                                "query"))))));
    }

    private static int burn(CommandSourceStack source, String input) {
        if (!LazoDiscsPermissions.canBurn(source)) {
            source.sendFailure(LazoDiscsText.noPermission());
            return 0;
        }

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(LazoDiscsText.playersOnly());
            return 0;
        }

        ItemStack stack = player.getMainHandItem();
        if (!DiscDataUtil.isMusicDisc(stack)) {
            com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                    player, LazoDiscsText.holdDisc());
            return 0;
        }

        BurnInput burnInput = parseBurnInput(input);
        if (burnInput == null) {
            com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                    player, LazoDiscsText.burnUsage().withStyle(ChatFormatting.RED));
            return 0;
        }

        String url;
        try {
            url = DiscDataUtil.validateUrl(burnInput.url());
        } catch (IllegalArgumentException e) {
            com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                    player, LazoDiscsText.invalidUrl(e.getMessage()));
            return 0;
        }

        ItemStack originalStack = stack;
        ItemStack originalSnapshot = stack.copy();
        String titleHint = burnInput.title();
        com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                player, LazoDiscsText.resolvingTrack().withStyle(ChatFormatting.GRAY));

        var server = player.createCommandSourceStack().getServer();
        AudioLoadExecutor.submit(
                () -> {
                    try {
                        LavaPcmFeeder.ResolvedTrack resolved =
                                LavaPcmFeeder.resolveTrack(url, titleHint);
                        String playbackUrl =
                                SpotifyTitleResolver.looksLikeSpotify(url) ? resolved.url() : url;
                        String title = chooseBurnTitle(url, titleHint, resolved);
                        server.execute(
                                () ->
                                        finishBurn(
                                                player,
                                                originalStack,
                                                originalSnapshot,
                                                url,
                                                playbackUrl,
                                                title));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (Exception t) {
                        LazoDiscs.LOGGER.warn(
                                "LazoDiscs could not burn '{}': {}", url, (YoutubeLoginFailure.matches(t) ? LazoDiscsText.youtubeLoginRequired() : t.toString()));
                        server.execute(
                                () ->
                                        com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                                                player,
                                                LazoDiscsText.burnFailed(messageOf(t))
                                                        .withStyle(ChatFormatting.RED)));
                    }
                },
                () ->
                        com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                                player,
                                LazoDiscsText.burnFailed(LazoDiscsText.audioLoadBusy())
                                        .withStyle(ChatFormatting.RED)));
        return 1;
    }

    private static void finishBurn(
            ServerPlayer player,
            ItemStack originalStack,
            ItemStack originalSnapshot,
            String originalUrl,
            String url,
            String title) {
        if (player.isRemoved()) {
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (stack != originalStack
                || !ItemStack.matches(stack, originalSnapshot)
                || !DiscDataUtil.isMusicDisc(stack)) {
            com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                    player, LazoDiscsText.holdDisc());
            return;
        }
        if (!LazoDiscsPermissions.canBurn(player.createCommandSourceStack())) {
            com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                    player, LazoDiscsText.noPermission());
            return;
        }
        try {
            DiscDataUtil.validateUrl(originalUrl);
            url = DiscDataUtil.validateUrl(url);
        } catch (IllegalArgumentException e) {
            com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                    player, LazoDiscsText.invalidUrl(e.getMessage()));
            return;
        }

        CustomDiscData data =
                new CustomDiscData(
                        url,
                        title,
                        DiscDataUtil.clampRange(LazoDiscsConfig.DEFAULT_RANGE.get()),
                        LazoDiscsConfig.DEFAULT_VOLUME.get().floatValue(),
                        UUID.randomUUID());
        DiscDataUtil.write(stack, data);
        com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(player, LazoDiscsText.burned(title));
    }

    private static int erase(CommandSourceStack source) {
        if (!LazoDiscsPermissions.canErase(source)) {
            source.sendFailure(LazoDiscsText.noPermission());
            return 0;
        }

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(LazoDiscsText.playersOnly());
            return 0;
        }

        ItemStack stack = player.getMainHandItem();
        if (!DiscDataUtil.hasCustomDisc(stack)) {
            com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                    player, LazoDiscsText.notLazoDisc());
            return 0;
        }
        DiscDataUtil.clear(stack);
        com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(player, LazoDiscsText.dataRemoved());
        return 1;
    }

    private static int search(CommandSourceStack source, String query) {
        if (!LazoDiscsPermissions.canSearch(source)) {
            source.sendFailure(LazoDiscsText.noPermission());
            return 0;
        }

        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(LazoDiscsText.playersOnly());
            return 0;
        }

        String cleanQuery = query == null ? "" : query.trim();
        if (cleanQuery.isBlank()) {
            com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                    player, LazoDiscsText.searchUsage());
            return 0;
        }
        if (looksLikeLink(cleanQuery)) {
            com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                    player, LazoDiscsText.searchNamesOnly().withStyle(ChatFormatting.RED));
            return 0;
        }
        com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                player, LazoDiscsText.searching(cleanQuery).withStyle(ChatFormatting.GRAY));

        var server = source.getServer();
        AudioLoadExecutor.submit(
                () -> {
                    try {
                        List<LavaPcmFeeder.SearchResult> results =
                                LavaPcmFeeder.search(cleanQuery, SEARCH_MAX_RESULTS);
                        server.execute(() -> sendSearchResults(player, cleanQuery, results));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (Exception t) {
                        LazoDiscs.LOGGER.warn(
                                "LazoDiscs search failed for '{}': {}", cleanQuery, (YoutubeLoginFailure.matches(t) ? LazoDiscsText.youtubeLoginRequired() : t.toString()));
                        server.execute(
                                () ->
                                        com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                                                player,
                                                LazoDiscsText.searchFailed(messageOf(t))
                                                        .withStyle(ChatFormatting.RED)));
                    }
                },
                () ->
                        com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                                player,
                                LazoDiscsText.searchFailed(LazoDiscsText.audioLoadBusy())
                                        .withStyle(ChatFormatting.RED)));
        return 1;
    }

    private static void sendSearchResults(
            ServerPlayer player, String query, List<LavaPcmFeeder.SearchResult> results) {
        if (player.isRemoved()) return;
        if (results.isEmpty()) {
            com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                    player, LazoDiscsText.noSongsFound(query).withStyle(ChatFormatting.RED));
            return;
        }

        com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(
                player, LazoDiscsText.searchHeader(query).withStyle(ChatFormatting.GOLD));
        int end = Math.min(results.size(), SEARCH_MAX_RESULTS);
        for (int i = 0; i < end; i++) {
            LavaPcmFeeder.SearchResult result = results.get(i);
            String title = sanitizeTitle(result.title());
            String author = sanitizeTitle(result.author());
            String alias = LazoDiscsConfig.COMMAND_ALIAS.get();
            if (alias == null || alias.isBlank()) alias = "lazodisc";
            String burnCommand = "/" + alias.trim() + " burn " + result.url() + " " + title;
            Component line =
                    new net.minecraft.network.chat.TextComponent((i + 1) + ". ")
                            .withStyle(ChatFormatting.DARK_GRAY)
                            .append(
                                    new net.minecraft.network.chat.TextComponent(title)
                                            .withStyle(
                                                    style ->
                                                            style.withColor(ChatFormatting.AQUA)
                                                                    .withClickEvent(
                                                                            new ClickEvent(
                                                                                    ClickEvent
                                                                                            .Action
                                                                                            .SUGGEST_COMMAND,
                                                                                    burnCommand))
                                                                    .withHoverEvent(
                                                                            new HoverEvent(
                                                                                    HoverEvent
                                                                                            .Action
                                                                                            .SHOW_TEXT,
                                                                                    new net
                                                                                            .minecraft
                                                                                            .network
                                                                                            .chat
                                                                                            .TextComponent(
                                                                                            LazoDiscsText
                                                                                                    .clickToPaste(
                                                                                                            burnCommand))))))
                            .append(
                                    new net.minecraft.network.chat.TextComponent(
                                                    " - "
                                                            + author
                                                            + " "
                                                            + formatDuration(result.lengthMs()))
                                            .withStyle(ChatFormatting.GRAY));
            com.eyecrasher.lazodiscs.compat.MinecraftMessages.send(player, line);
        }
    }

    private static boolean looksLikeLink(String value) {
        if (SpotifyTitleResolver.looksLikeSpotify(value)) return true;
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("www.")
                || lower.contains("://")
                || lower.contains("youtube.com/")
                || lower.contains("youtu.be/")
                || lower.contains("spotify.com/")
                || lower.contains("soundcloud.com/");
    }

    private static String sanitizeTitle(String value) {
        if (value == null || value.isBlank()) return LazoDiscsText.unknown();
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static BurnInput parseBurnInput(String input) {
        if (input == null) return null;
        String clean = input.trim();
        if (clean.isBlank()) return null;

        int splitAt = firstWhitespace(clean);
        if (splitAt < 0) {
            return new BurnInput(clean, null);
        }

        String url = clean.substring(0, splitAt);
        String title = clean.substring(splitAt).trim();
        return new BurnInput(url, title.isBlank() ? null : title);
    }

    private static int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String chooseBurnTitle(
            String url, String titleHint, LavaPcmFeeder.ResolvedTrack resolved) {
        if (titleHint != null && !titleHint.isBlank()) {
            return titleHint.trim();
        }
        if (resolved != null
                && resolved.title() != null
                && !resolved.title().isBlank()
                && !resolved.title().equalsIgnoreCase(LazoDiscsText.unknown())) {
            return sanitizeTitle(resolved.title());
        }
        if (SpotifyTitleResolver.looksLikeSpotify(url)) {
            return SpotifyTitleResolver.resolveTitle(url).orElse(url);
        }
        return url;
    }

    private static String messageOf(Throwable t) {
        if (YoutubeLoginFailure.matches(t)) return LazoDiscsText.youtubeLoginRequired();
        if (t == null) return LazoDiscsText.unknown();
        String message = t.getMessage();
        Throwable cause = t.getCause();
        if ((message == null || message.isBlank()) && cause != null) return messageOf(cause);
        if (cause != null && message != null && message.equals(cause.toString()))
            return messageOf(cause);
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private static String formatDuration(long lengthMs) {
        if (lengthMs <= 0) return "";
        long totalSeconds = lengthMs / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return "(" + minutes + ":" + (seconds < 10 ? "0" : "") + seconds + ")";
    }

    private record BurnInput(String url, String title) {}
}
