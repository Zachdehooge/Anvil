package dev.zachdehooge;

import dev.zachdehooge.Alerts.*;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
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
import java.util.concurrent.atomic.AtomicInteger;

import static net.dv8tion.jda.api.interactions.commands.OptionType.STRING;
import static net.dv8tion.jda.api.interactions.commands.build.Commands.slash;

public class Application extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);
    private static final int MAX_TRACKED_PIDS = 1000;
    private static final String CONFIG_FILE = "config.json";
    private JDA jda;
    private final Map<Long, Map<String, Long>> guildChannels = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> postedItems = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> globalSeenPids = new LinkedHashSet<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, PageSession> pageCache = new ConcurrentHashMap<>();

    private record PageSession(MessageEmbed baseEmbed, List<String> pages, AtomicInteger index) {}

    public void Run() {
        String envPath = System.getProperty("env.path", ".");
        Dotenv env = Dotenv.configure().directory(envPath).filename(".env").load();

        loadConfig();

        logger.info("Starting Bot...");

        jda = JDABuilder.createLight(env.get("TOKEN"), EnumSet.noneOf(GatewayIntent.class))
                .addEventListeners(this)
                .setStatus(OnlineStatus.DO_NOT_DISTURB)
                .build();

        ScheduledExecutorService presenceScheduler = Executors.newSingleThreadScheduledExecutor();
        presenceScheduler.scheduleAtFixedRate(() -> {
            LocalTime utc = LocalTime.now(Clock.systemUTC());
            jda.getPresence().setActivity(Activity.watching(
                    String.format("%02d:%02d UTC", utc.getHour(), utc.getMinute())));
        }, 0, 1, TimeUnit.MINUTES);

        if (globalSeenPids.isEmpty()) {
            seedSeenAlerts();
        }

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
                .addCommands(slash("setpdschannel", "Sets the channel for PDS alerts (tornado, severe, winter, flood)")
                        .addOption(STRING, "channel", "Channel ID or mention", true)
                        .setIntegrationTypes(IntegrationType.ALL))
                .addCommands(slash("setfloodchannel", "Sets the channel for flood alerts")
                        .addOption(STRING, "channel", "Channel ID or mention", true)
                        .setIntegrationTypes(IntegrationType.ALL))
                .queue();

        logger.info("Bot is ready");
    }

    private void seedSeenAlerts() {
        logger.info("Fresh start detected — seeding seen PIDs from current NWS alerts (nothing will be posted)");
        try {
            Map<String, List<AlertEmbed>> allTypes = new LinkedHashMap<>();
            allTypes.put("severe",  new SevereThunderstorm().getSvrTStorm());
            allTypes.put("tornado", new Tornado().getTornado());
            allTypes.put("winter",  new Winter().getWinter());
            allTypes.put("sws",     new SpecialWeatherStatement().getSWS());
            allTypes.put("pds",     new PDS().getPDS());
            allTypes.put("flood",   new Flood().getFlood());

            int count = 0;
            for (Map.Entry<String, List<AlertEmbed>> typeEntry : allTypes.entrySet()) {
                for (AlertEmbed alert : typeEntry.getValue()) {
                    if (alert.id().isBlank()) continue;
                    String seenKey = typeEntry.getKey() + ":" + alert.id();
                    globalSeenPids.add(seenKey);
                    for (long guildId : guildChannels.keySet()) {
                        postedItems.computeIfAbsent(guildId, k -> ConcurrentHashMap.newKeySet()).add(seenKey);
                    }
                    count++;
                }
            }

            saveConfig();
            logger.info("Seeded {} existing alert IDs — only new alerts from this point will be posted", count);
        } catch (Exception e) {
            logger.error("Failed to seed seen alerts", e);
        }
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
            alertsByType.put("pds",     new PDS().getPDS());
            alertsByType.put("flood",   new Flood().getFlood());

            Set<String> activeKeys = new HashSet<>();
            for (Map.Entry<String, List<AlertEmbed>> e : alertsByType.entrySet()) {
                for (AlertEmbed a : e.getValue()) {
                    if (!a.id().isBlank()) activeKeys.add(e.getKey() + ":" + a.id());
                }
            }
            if (!activeKeys.isEmpty()) {
                synchronized (globalSeenPids) {
                    globalSeenPids.removeIf(key -> !activeKeys.contains(key));
                }
            }

            boolean dirty = false;
            for (Map.Entry<String, List<AlertEmbed>> typeEntry : alertsByType.entrySet()) {
                String alertType = typeEntry.getKey();

                for (AlertEmbed alert : typeEntry.getValue()) {
                    if (alert.id().isBlank()) continue;

                    String seenKey = alertType + ":" + alert.id();
                    boolean isNew;
                    synchronized (globalSeenPids) {
                        isNew = !globalSeenPids.contains(seenKey);
                        if (isNew) {
                            if (globalSeenPids.size() >= MAX_TRACKED_PIDS) {
                                globalSeenPids.remove(globalSeenPids.iterator().next());
                            }
                            globalSeenPids.add(seenKey);
                        }
                    }
                    if (!isNew) continue;

                    dirty = true;

                    logger.info("New {} alert: {}", alertType, alert.id());

                    for (Map.Entry<Long, Map<String, Long>> guildEntry : guildChannels.entrySet()) {
                        long guildId = guildEntry.getKey();
                        Set<String> guildPosted = postedItems.computeIfAbsent(guildId, k -> ConcurrentHashMap.newKeySet());

                        if (guildPosted.contains(seenKey)) continue;

                        Long channelId = guildEntry.getValue().get(alertType);
                        if (channelId == null) continue;

                        TextChannel channel = jda.getTextChannelById(channelId);
                        if (channel == null) {
                            logger.warn("Channel {} not found for guild {} ({})", channelId, guildId, alertType);
                            continue;
                        }

                        String sessionId = UUID.randomUUID().toString();
                        List<String> pages = splitIntoPages(alert.fullDescription(), alert.embed().getDescription());
                        MessageEmbed firstPage = buildPagedEmbed(alert.embed(), pages.get(0), 1, pages.size());

                        if (pages.size() > 1) {
                            pageCache.put(sessionId, new PageSession(alert.embed(), pages, new AtomicInteger(0)));
                            channel.sendMessageEmbeds(firstPage)
                                    .setComponents(buildButtons(sessionId, 0, pages.size()))
                                    .queue(
                                            msg -> logger.info("Posted {} alert to guild {}", alertType, guildId),
                                            err -> logger.error("Failed to post {} alert to guild {}: {}", alertType, guildId, err.getMessage())
                                    );
                        } else {
                            channel.sendMessageEmbeds(firstPage)
                                    .queue(
                                            msg -> logger.info("Posted {} alert to guild {}", alertType, guildId),
                                            err -> logger.error("Failed to post {} alert to guild {}: {}", alertType, guildId, err.getMessage())
                                    );
                        }

                        guildPosted.add(seenKey);
                    }
                }
            }

            if (dirty) saveConfig();

        } catch (Exception e) {
            logger.error("Error during alert check", e);
        }
    }

    private List<String> splitIntoPages(String text, String existingDesc) {
        int prefixLen = (existingDesc != null ? existingDesc.length() + 2 : 0);
        int maxLen = 4096 - prefixLen;
        List<String> pages = new ArrayList<>();
        text = text.strip();
        while (text.length() > maxLen) {
            int cut = text.lastIndexOf('\n', maxLen);
            if (cut <= 0) cut = maxLen;
            pages.add(text.substring(0, cut).strip());
            text = text.substring(cut).strip();
        }
        if (!text.isBlank()) pages.add(text);
        return pages.isEmpty() ? List.of("") : pages;
    }

    private MessageEmbed buildPagedEmbed(MessageEmbed base, String page, int pageNum, int total) {
        EmbedBuilder eb = new EmbedBuilder(base);
        String desc = (base.getDescription() != null ? base.getDescription() + "\n\n" : "") + page;
        eb.setDescription(desc);
        if (total > 1) eb.setFooter("Page " + pageNum + " of " + total);
        return eb.build();
    }

    private ActionRow buildButtons(String sessionId, int page, int total) {
        Button prev = Button.secondary("prev:" + sessionId, "◀").withDisabled(page == 0);
        Button next = Button.secondary("next:" + sessionId, "▶").withDisabled(page >= total - 1);
        return ActionRow.of(prev, next);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String[] parts = event.getComponentId().split(":", 2);
        if (parts.length != 2) return;

        String action = parts[0];
        String sessionId = parts[1];

        PageSession session = pageCache.get(sessionId);
        if (session == null) {
            event.reply("Pages are no longer available (bot may have restarted).").setEphemeral(true).queue();
            return;
        }

        int newPage = switch (action) {
            case "prev" -> Math.max(0, session.index().get() - 1);
            case "next" -> Math.min(session.pages().size() - 1, session.index().get() + 1);
            default -> session.index().get();
        };
        session.index().set(newPage);

        MessageEmbed updated = buildPagedEmbed(session.baseEmbed(), session.pages().get(newPage), newPage + 1, session.pages().size());
        event.editMessageEmbeds(updated)
                .setComponents(buildButtons(sessionId, newPage, session.pages().size()))
                .queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) return;

        String alertType = switch (event.getName()) {
            case "setseverechannel" -> "severe";
            case "settorchannel"    -> "tornado";
            case "setwinterchannel" -> "winter";
            case "setswschannel"    -> "sws";
            case "setpdschannel"    -> "pds";
            case "setfloodchannel"  -> "flood";
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

            ObjectNode posted = mapper.createObjectNode();
            postedItems.forEach((guildId, ids) -> {
                ArrayNode idsNode = mapper.createArrayNode();
                ids.forEach(idsNode::add);
                posted.set(String.valueOf(guildId), idsNode);
            });
            root.set("postedItems", posted);

            mapper.writeValue(new File(CONFIG_FILE), root);
        } catch (Exception e) {
            logger.error("Failed to save config", e);
        }
    }

    private void loadConfig() {
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
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

                for (Map.Entry<String, JsonNode> postedEntry : root.path("postedItems").properties()) {
                    long guildId = Long.parseLong(postedEntry.getKey());
                    Set<String> ids = ConcurrentHashMap.newKeySet();
                    for (JsonNode id : postedEntry.getValue()) {
                        ids.add(id.asText());
                    }
                    postedItems.put(guildId, ids);
                }

                logger.info("Loaded {} guild configs, {} seen PIDs", guildChannels.size(), globalSeenPids.size());
            } catch (Exception e) {
                logger.error("Failed to load config", e);
            }
        }

        Map<String, Long> channelDefaults = new LinkedHashMap<>();
        channelDefaults.put("tornado", 1514265071067467819L);
        channelDefaults.put("severe",  1514265112108863559L);
        channelDefaults.put("winter",  1514265147227508912L);
        channelDefaults.put("sws",     1514265128957116496L);
        channelDefaults.put("pds",     1514376160765808740L);
        channelDefaults.put("flood",   1514663611035943073L);

        long defaultGuildId = 828991980805685258L;
        Map<String, Long> guildMap = guildChannels.computeIfAbsent(defaultGuildId, k -> new ConcurrentHashMap<>());
        postedItems.computeIfAbsent(defaultGuildId, k -> ConcurrentHashMap.newKeySet());

        boolean changed = false;
        for (Map.Entry<String, Long> def : channelDefaults.entrySet()) {
            if (!guildMap.containsKey(def.getKey())) {
                guildMap.put(def.getKey(), def.getValue());
                logger.info("Added missing '{}' channel to guild config", def.getKey());
                changed = true;
            }
        }
        if (changed) saveConfig();
    }
}
