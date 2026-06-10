package dev.zachdehooge;

import dev.zachdehooge.Alerts.*;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.time.Clock;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;

import static net.dv8tion.jda.api.interactions.commands.OptionType.STRING;
import static net.dv8tion.jda.api.interactions.commands.build.Commands.slash;

public class Application extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);
    private static final int MAX_TRACKED_PIDS = 1000;
    private static final String CONFIG_FILE = "config.json";

    private JDA jda;

    // guild ID -> { "severe" | "tornado" | "winter" | "sws" -> channel ID }
    private final Map<Long, Map<String, Long>> guildChannels = new ConcurrentHashMap<>();

    // guild ID -> set of alert IDs already posted to that guild
    private final Map<Long, Set<String>> postedItems = new ConcurrentHashMap<>();

    // globally seen alert IDs (ordered for eviction of oldest when full)
    private final LinkedHashSet<String> globalSeenPids = new LinkedHashSet<>();

    private final ObjectMapper mapper = new ObjectMapper();

    public void Run() {
        String envPath = System.getProperty("env.path", ".");
        Dotenv env = Dotenv.configure().directory(envPath).filename(".env").load();

        loadConfig();

        logger.info("Starting Bot...");

        jda = JDABuilder.createLight(env.get("TOKEN"), EnumSet.noneOf(GatewayIntent.class))
                .addEventListeners(this)
                .setStatus(OnlineStatus.DO_NOT_DISTURB)
                .build();

        // Clock presence update
        ScheduledExecutorService presenceScheduler = Executors.newSingleThreadScheduledExecutor();
        presenceScheduler.scheduleAtFixedRate(() -> {
            LocalTime utc = LocalTime.now(Clock.systemUTC());
            jda.getPresence().setActivity(Activity.watching(
                    String.format("%02d:%02d UTC", utc.getHour(), utc.getMinute())));
        }, 0, 1, TimeUnit.MINUTES);

        // Alert polling — single thread matches NadoBot's sequential check_rss_feed loop
        ScheduledExecutorService alertScheduler = Executors.newSingleThreadScheduledExecutor();
        alertScheduler.scheduleAtFixedRate(this::checkAlerts, 0, 1, TimeUnit.MINUTES);

        logger.info("Loading Commands...");

        jda.updateCommands()
                .addCommands(slash("setseverechannel", "Sets the channel for severe thunderstorm alerts")
                        .addOption(STRING, "channel", "Channel ID or mention", true)
                        .setIntegrationTypes(IntegrationType.ALL))
                .addCommands(slash("settorchannel", "Sets the channel for tornado alerts")
                        .addOption(STRING, "channel", "Channel ID or mention", true)
                        .setIntegrationTypes(IntegrationType.ALL))
                .addCommands(slash("setwinterchannel", "Sets the channel for winter weather alerts")
                        .addOption(STRING, "channel", "Channel ID or mention", true)
                        .setIntegrationTypes(IntegrationType.ALL))
                .addCommands(slash("setswschannel", "Sets the channel for Special Weather Statements")
                        .addOption(STRING, "channel", "Channel ID or mention", true)
                        .setIntegrationTypes(IntegrationType.ALL))
                .queue();

        logger.info("Bot is ready");
    }

    private void checkAlerts() {
        if (guildChannels.isEmpty()) {
            logger.debug("No guilds configured, skipping alert check");
            return;
        }

        try {
            Map<String, List<AlertEmbed>> alertsByType = new LinkedHashMap<>();
            alertsByType.put("severe",  new SevereThunderstorm().getSvrTStorm());
            alertsByType.put("tornado", new Tornado().getTornado());
            alertsByType.put("winter",  new Winter().getWinter());
            alertsByType.put("sws",     new SpecialWeatherStatement().getSWS());

            // Collect currently active IDs and clean up expired ones from globalSeenPids
            Set<String> activeIds = new HashSet<>();
            for (List<AlertEmbed> list : alertsByType.values()) {
                for (AlertEmbed a : list) {
                    if (!a.id().isBlank()) activeIds.add(a.id());
                }
            }
            if (!activeIds.isEmpty()) {
                synchronized (globalSeenPids) {
                    globalSeenPids.removeIf(pid -> !activeIds.contains(pid));
                }
            }

            boolean dirty = false;

            for (Map.Entry<String, List<AlertEmbed>> typeEntry : alertsByType.entrySet()) {
                String alertType = typeEntry.getKey();

                for (AlertEmbed alert : typeEntry.getValue()) {
                    if (alert.id().isBlank()) continue;

                    // Skip if this alert has already been processed globally
                    synchronized (globalSeenPids) {
                        if (globalSeenPids.contains(alert.id())) continue;
                        if (globalSeenPids.size() >= MAX_TRACKED_PIDS) {
                            globalSeenPids.remove(globalSeenPids.iterator().next());
                        }
                        globalSeenPids.add(alert.id());
                    }

                    logger.info("New {} alert: {}", alertType, alert.id());

                    for (Map.Entry<Long, Map<String, Long>> guildEntry : guildChannels.entrySet()) {
                        long guildId = guildEntry.getKey();
                        Set<String> guildPosted = postedItems.computeIfAbsent(guildId, k -> ConcurrentHashMap.newKeySet());

                        if (guildPosted.contains(alert.id())) continue;

                        Long channelId = guildEntry.getValue().get(alertType);
                        if (channelId == null) continue;

                        TextChannel channel = jda.getTextChannelById(channelId);
                        if (channel == null) {
                            logger.warn("Channel {} not found for guild {} ({})", channelId, guildId, alertType);
                            continue;
                        }

                        channel.sendMessageEmbeds(alert.embed()).queue(
                                msg -> logger.info("Posted {} alert to guild {}", alertType, guildId),
                                err -> logger.error("Failed to post {} alert to guild {}: {}", alertType, guildId, err.getMessage())
                        );

                        guildPosted.add(alert.id());
                        dirty = true;
                    }
                }
            }

            if (dirty) saveConfig();

        } catch (Exception e) {
            logger.error("Error during alert check", e);
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) return;

        String alertType = switch (event.getName()) {
            case "setseverechannel" -> "severe";
            case "settorchannel"    -> "tornado";
            case "setwinterchannel" -> "winter";
            case "setswschannel"    -> "sws";
            default -> null;
        };

        if (alertType == null) {
            event.reply("Unknown command.").setEphemeral(true).queue();
            return;
        }

        String raw = event.getOption("channel").getAsString().replaceAll("[^0-9]", "");
        if (raw.isEmpty()) {
            event.reply("Please provide a valid channel ID or mention.").setEphemeral(true).queue();
            return;
        }

        long channelId = Long.parseLong(raw);
        TextChannel channel = jda.getTextChannelById(channelId);
        if (channel == null) {
            event.reply("Channel not found — make sure the bot can see that channel.").setEphemeral(true).queue();
            return;
        }

        long guildId = event.getGuild().getIdLong();
        guildChannels.computeIfAbsent(guildId, k -> new ConcurrentHashMap<>()).put(alertType, channelId);
        postedItems.computeIfAbsent(guildId, k -> ConcurrentHashMap.newKeySet());
        saveConfig();

        event.reply(channel.getAsMention() + " will now receive **" + alertType + "** weather alerts.")
                .setEphemeral(true).queue();
        logger.info("Guild {} set {} channel -> {}", guildId, alertType, channelId);
    }

    private synchronized void saveConfig() {
        try {
            ObjectNode root = mapper.createObjectNode();

            ObjectNode channels = mapper.createObjectNode();
            guildChannels.forEach((guildId, typeMap) -> {
                ObjectNode typeNode = mapper.createObjectNode();
                typeMap.forEach((k, v) -> typeNode.put(k, v));
                channels.set(String.valueOf(guildId), typeNode);
            });
            root.set("guildChannels", channels);

            ArrayNode pids = mapper.createArrayNode();
            synchronized (globalSeenPids) {
                globalSeenPids.forEach(pids::add);
            }
            root.set("globalSeenPids", pids);

            mapper.writeValue(new File(CONFIG_FILE), root);
        } catch (Exception e) {
            logger.error("Failed to save config", e);
        }
    }

    private void loadConfig() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) {
            logger.info("No config file found, seeding default guild config");
            Map<String, Long> defaultChannels = new ConcurrentHashMap<>();
            defaultChannels.put("tornado", 1514265071067467819L);
            defaultChannels.put("severe",  1514265112108863559L);
            defaultChannels.put("winter",  1514265147227508912L);
            defaultChannels.put("sws",     1514265128957116496L);
            guildChannels.put(828991980805685258L, defaultChannels);
            postedItems.put(828991980805685258L, ConcurrentHashMap.newKeySet());
            saveConfig();
            return;
        }
        try {
            JsonNode root = mapper.readTree(file);

            for (Map.Entry<String, JsonNode> guildEntry : root.path("guildChannels").properties()) {
                long guildId = Long.parseLong(guildEntry.getKey());
                Map<String, Long> typeMap = new ConcurrentHashMap<>();
                for (Map.Entry<String, JsonNode> t : guildEntry.getValue().properties()) {
                    typeMap.put(t.getKey(), t.getValue().asLong());
                }
                guildChannels.put(guildId, typeMap);
                postedItems.computeIfAbsent(guildId, k -> ConcurrentHashMap.newKeySet());
            }

            for (JsonNode pid : root.path("globalSeenPids")) {
                globalSeenPids.add(pid.asText());
            }

            logger.info("Loaded {} guild configs, {} seen PIDs", guildChannels.size(), globalSeenPids.size());
        } catch (Exception e) {
            logger.error("Failed to load config", e);
        }
    }
}
