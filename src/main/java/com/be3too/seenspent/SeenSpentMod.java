package com.be3too.seenspent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class SeenSpentMod implements DedicatedServerModInitializer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_FILE = FabricLoader.getInstance().getConfigDir().resolve("seen_and_spent.json");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private static final Map<String, PlayerData> PLAYER_CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    public static class PlayerData {
        public String name;
        public long lastJoinEpochMilli;
        public long lastSeenEpochMilli;
        public long totalPlaytimeMillis;

        public PlayerData(String name, long lastJoinEpochMilli, long lastSeenEpochMilli, long totalPlaytimeMillis) {
            this.name = name;
            this.lastJoinEpochMilli = lastJoinEpochMilli;
            this.lastSeenEpochMilli = lastSeenEpochMilli;
            this.totalPlaytimeMillis = totalPlaytimeMillis;
        }
    }

    @Override
    public void onInitializeServer() {
        loadData();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            String name = player.getGameProfile().name();
            long now = System.currentTimeMillis();

            ACTIVE_SESSIONS.put(player.getUUID(), now);

            PLAYER_CACHE.compute(name.toLowerCase(), (key, existing) -> {
                long savedTime = (existing != null) ? existing.totalPlaytimeMillis : 0L;
                return new PlayerData(name, now, now, savedTime);
            });
            saveData();
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            closeSession(handler.getPlayer());
            saveData();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                closeSession(player);
            }
            saveData();
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("seen")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests(this::suggestPlayers)
                    .executes(context -> executeSeen(context, StringArgumentType.getString(context, "player")))
                )
            );

            dispatcher.register(Commands.literal("playtime")
                .executes(context -> {
                    ServerPlayer self = context.getSource().getPlayerOrException();
                    return executePlaytime(context, self.getGameProfile().name());
                })
                .then(Commands.literal("top")
                    .executes(this::executeTop)
                )
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests(this::suggestPlayers)
                    .executes(context -> executePlaytime(context, StringArgumentType.getString(context, "player")))
                )
            );
        });
    }

    private void closeSession(ServerPlayer player) {
        String name = player.getGameProfile().name();
        long now = System.currentTimeMillis();
        Long joinTime = ACTIVE_SESSIONS.remove(player.getUUID());
        long sessionDuration = (joinTime != null) ? Math.max(0L, now - joinTime) : 0L;

        PLAYER_CACHE.compute(name.toLowerCase(), (key, existing) -> {
            long previousTotal = (existing != null) ? existing.totalPlaytimeMillis : 0L;
            long lastJoin = (joinTime != null) ? joinTime : ((existing != null) ? existing.lastJoinEpochMilli : now);
            return new PlayerData(name, lastJoin, now, previousTotal + sessionDuration);
        });
    }

    private long getTotalPlaytime(MinecraftServer server, String targetName) {
        PlayerData data = PLAYER_CACHE.get(targetName.toLowerCase());
        long total = (data != null) ? data.totalPlaytimeMillis : 0L;

        ServerPlayer online = server.getPlayerList().getPlayerByName(targetName);
        if (online != null) {
            Long joinTime = ACTIVE_SESSIONS.get(online.getUUID());
            if (joinTime != null) {
                total += Math.max(0L, System.currentTimeMillis() - joinTime);
            }
        }
        return total;
    }
    private int executeSeen(CommandContext<CommandSourceStack> context, String targetName) {
        MinecraftServer server = context.getSource().getServer();
        ServerPlayer onlinePlayer = server.getPlayerList().getPlayerByName(targetName);
        PlayerData data = PLAYER_CACHE.get(targetName.toLowerCase());
        long totalMillis = getTotalPlaytime(server, targetName);
        String formattedPlaytime = formatDuration(Duration.ofMillis(totalMillis));

        if (onlinePlayer != null) {
            String realName = onlinePlayer.getGameProfile().name();
            Long joinTime = ACTIVE_SESSIONS.get(onlinePlayer.getUUID());
            String joinedStr = (joinTime != null) ? FORMATTER.format(Instant.ofEpochMilli(joinTime)) : "Unknown";
            String sessionAgo = (joinTime != null)
                    ? formatDuration(Duration.between(Instant.ofEpochMilli(joinTime), Instant.now()))
                    : "0m";

            context.getSource().sendSuccess(() -> Component.literal(
                "\u00a7aPlayer \u00a7e" + realName + " \u00a7ais currently online!\n" +
                "\u00a77Last joined: \u00a7f" + joinedStr + " \u00a78(" + sessionAgo + " ago)\n" +
                "\u00a77Total playtime: \u00a7b" + formattedPlaytime
            ), false);
            return 1;
        }

        if (data == null) {
            context.getSource().sendFailure(Component.literal("\u00a7cNo records found for player \u00a7e" + targetName + "\u00a7c."));
            return 0;
        }

        String lastJoinDate = (data.lastJoinEpochMilli > 0)
                ? FORMATTER.format(Instant.ofEpochMilli(data.lastJoinEpochMilli))
                : "Unknown";
        String lastSeenDate = FORMATTER.format(Instant.ofEpochMilli(data.lastSeenEpochMilli));
        String timeAgo = formatDuration(Duration.between(Instant.ofEpochMilli(data.lastSeenEpochMilli), Instant.now()));

        context.getSource().sendSuccess(() -> Component.literal(
            "\u00a77Player \u00a7e" + data.name + " \u00a77is \u00a7coffline\u00a77.\n" +
            "\u00a77Last joined: \u00a7f" + lastJoinDate + "\n" +
            "\u00a77Last seen: \u00a7f" + lastSeenDate + " \u00a78(" + timeAgo + " ago)\n" +
            "\u00a77Total playtime: \u00a7b" + formattedPlaytime
        ), false);
        return 1;
    }

    private int executePlaytime(CommandContext<CommandSourceStack> context, String targetName) {
        MinecraftServer server = context.getSource().getServer();
        ServerPlayer onlinePlayer = server.getPlayerList().getPlayerByName(targetName);
        PlayerData data = PLAYER_CACHE.get(targetName.toLowerCase());

        if (onlinePlayer == null && data == null) {
            context.getSource().sendFailure(Component.literal("\u00a7cNo records found for player \u00a7e" + targetName + "\u00a7c."));
            return 0;
        }

        String displayName = (onlinePlayer != null) ? onlinePlayer.getGameProfile().name() : data.name;
        long totalMillis = getTotalPlaytime(server, targetName);
        String formattedPlaytime = formatDuration(Duration.ofMillis(totalMillis));

        context.getSource().sendSuccess(() -> Component.literal(
            "\u00a77Total playtime for \u00a7e" + displayName + "\u00a77: \u00a7b" + formattedPlaytime
        ), false);
        return 1;
    }

    private int executeTop(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        Map<String, Long> totals = new HashMap<>();
        Map<String, String> displayNames = new HashMap<>();

        for (Map.Entry<String, PlayerData> entry : PLAYER_CACHE.entrySet()) {
            totals.put(entry.getKey(), entry.getValue().totalPlaytimeMillis);
            displayNames.put(entry.getKey(), entry.getValue().name);
        }

        long now = System.currentTimeMillis();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            String realName = online.getGameProfile().name();
            String key = realName.toLowerCase();
            displayNames.put(key, realName);
            Long joinTime = ACTIVE_SESSIONS.get(online.getUUID());
            long currentSession = (joinTime != null) ? Math.max(0L, now - joinTime) : 0L;
            totals.merge(key, currentSession, Long::sum);
        }

        if (totals.isEmpty()) {
            context.getSource().sendFailure(Component.literal("\u00a7cNo playtime records found yet."));
            return 0;
        }

        List<Map.Entry<String, Long>> sorted = new ArrayList<>(totals.entrySet());
        sorted.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));

        StringBuilder sb = new StringBuilder("\u00a76\u00a7l=== Top Playtime Leaderboard ===");
        int limit = Math.min(10, sorted.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, Long> entry = sorted.get(i);
            String name = displayNames.getOrDefault(entry.getKey(), entry.getKey());
            String timeStr = formatDuration(Duration.ofMillis(entry.getValue()));
            sb.append("\n\u00a7e#").append(i + 1).append(" \u00a7f").append(name).append(" \u00a77- \u00a7b").append(timeStr);
        }

        String message = sb.toString();
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        for (String onlineName : context.getSource().getServer().getPlayerNames()) {
            builder.suggest(onlineName);
        }
        PLAYER_CACHE.values().forEach(data -> builder.suggest(data.name));
        return builder.buildFuture();
    }

    private void loadData() {
        if (!Files.exists(DATA_FILE)) return;
        try (Reader reader = Files.newBufferedReader(DATA_FILE)) {
            Type type = new TypeToken<Map<String, PlayerData>>() {}.getType();
            Map<String, PlayerData> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                PLAYER_CACHE.putAll(loaded);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveData() {
        try (Writer writer = Files.newBufferedWriter(DATA_FILE)) {
            GSON.toJson(PLAYER_CACHE, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();

        if (days > 0) return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0) return hours + "h " + minutes + "m";
        return Math.max(1, minutes) + "m";
    }
}
