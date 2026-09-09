package dev.zachdehooge;

import dev.zachdehooge.Alerts.*;
import dev.zachdehooge.Utilities.Database;
import dev.zachdehooge.Utilities.Outlooks;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.IntegrationType;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.awt.Color;
import java.io.File;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static net.dv8tion.jda.api.interactions.commands.OptionType.STRING;
import static net.dv8tion.jda.api.interactions.commands.build.Commands.slash;

public class Application extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);
    private static final int MAX_TRACKED_PIDS = 1000;
    private static final String CONFIG_FILE = "config.json";
    private static final DateTimeFormatter UTC_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter OUTLOOK_THREAD_DATE =
            DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneOffset.UTC);
    private final Database db = Database.getInstance();
    private JDA jda;
    private final Map<Long, Map<String, Long>> guildChannels = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> postedItems = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> globalSeenPids = new LinkedHashSet<>();
    private final Map<Integer, String> outlookIssuance = new ConcurrentHashMap<>();
    /** guildId -> (UTC date "yyyy-MM-dd" -> the Discord thread that day's outlook updates are posted in). */
    private final Map<Long, Map<String, Long>> outlookThreads = new ConcurrentHashMap<>();
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
        }, 0, 30, TimeUnit.SECONDS);

        if (globalSeenPids.isEmpty()) {
            seedSeenAlerts();
        }

        ScheduledExecutorService alertScheduler = Executors.newSingleThreadScheduledExecutor();
        alertScheduler.scheduleAtFixedRate(this::checkAlerts, 0, 40, TimeUnit.SECONDS);

        ScheduledExecutorService reportScheduler = Executors.newSingleThreadScheduledExecutor();
        reportScheduler.scheduleAtFixedRate(this::postDailyReport,
                secondsUntilNextReportUTC(), 86400, TimeUnit.SECONDS);

        ScheduledExecutorService outlookScheduler = Executors.newSingleThreadScheduledExecutor();
        outlookScheduler.scheduleAtFixedRate(this::checkOutlooks, 20, 300, TimeUnit.SECONDS);

        logger.info("Loading Commands...");

        CommandListUpdateAction commandUpdate = jda.updateCommands()
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
                .addCommands(slash("settorpdschannel", "Sets the channel for TORPDS alerts (tornado)")
                        .addOption(STRING, "channel", "Channel ID or mention", true)
                        .setIntegrationTypes(IntegrationType.ALL))
                .addCommands(slash("setsvrpdschannel", "Sets the channel for SVRPDS alerts (severe thunderstorms)")
                        .addOption(STRING, "channel", "Channel ID or mention", true)
                        .setIntegrationTypes(IntegrationType.ALL))
                .addCommands(slash("setfloodchannel", "Sets the channel for flood alerts")
                        .addOption(STRING, "channel", "Channel ID or mention", true)
                        .setIntegrationTypes(IntegrationType.ALL))
                .addCommands(slash("setwatchchannel", "Sets the channel for watch alerts")
                        .addOption(STRING, "channel", "Channel ID or mention", true)
                        .setIntegrationTypes(IntegrationType.ALL))
                .addCommands(slash("gettempestobs", "Get Tempest OBS")
                        .setIntegrationTypes(IntegrationType.ALL))
                .addCommands(slash("runreport", "Daily report: alert counts and Tempest min/max metrics")
                        .setIntegrationTypes(IntegrationType.ALL))
                .addCommands(slash("setreportchannel", "Sets the channel for the automated 11:59 PM UTC daily report")
                        .addOption(STRING, "channel", "Channel ID or mention", true)
                        .setIntegrationTypes(IntegrationType.ALL))
                .addCommands(slash("setoutlookchannel", "Sets the channel for automatic SPC Day 1-3 Outlook updates")
                        .addOption(STRING, "channel", "Channel ID or mention", true)
                        .setIntegrationTypes(IntegrationType.ALL));

        for (Outlooks.Day day : Outlooks.Day.values()) {
            commandUpdate.addCommands(slash(day.command(), day.description())
                    .setIntegrationTypes(IntegrationType.ALL));
        }

        commandUpdate.queue();

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
            allTypes.put("torpds",  new TORPDS().getPDS());
            allTypes.put("svrpds",  new SVRPDS().getPDS());
            allTypes.put("flood",   new Flood().getFlood());
            allTypes.put("watch",   new Watches().getWatch());
            allTypes.put("tempest", new Tempest().getObs());

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
            alertsByType.put("torpds",     new TORPDS().getPDS());
            alertsByType.put("svrpds",     new SVRPDS().getPDS());
            alertsByType.put("flood",   new Flood().getFlood());
            alertsByType.put("watch",   new Watches().getWatch());

            Tempest tempest = new Tempest();
            alertsByType.put("tempest", tempest.getObs());
            tempest.getRawObs().ifPresent(obs ->
                db.logTempestObs(obs.airTemp(), obs.feelsLike(), obs.humidity(), obs.pressure(),
                        obs.dewPoint(), obs.windAvg(), obs.windGust(), obs.rainAccumLocal(), obs.lightningCount()));

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
                    db.logAlert(alert.id(), alert.eventName());

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

                        List<MessageEmbed> embedsToSend = new ArrayList<>();
                        embedsToSend.add(firstPage);
                        if (alert.historyImageUrl() != null) {
                            embedsToSend.add(buildHistoryEmbed(alert.embed(), alert.historyImageUrl()));
                        }

                        if (pages.size() > 1) {
                            pageCache.put(sessionId, new PageSession(alert.embed(), pages, new AtomicInteger(0)));
                            channel.sendMessageEmbeds(embedsToSend)
                                    .setComponents(buildButtons(sessionId, 0, pages.size()))
                                    .queue(
                                            msg -> logger.info("Posted {} alert to guild {}", alertType, guildId),
                                            err -> logger.error("Failed to post {} alert to guild {}: {}", alertType, guildId, err.getMessage())
                                    );
                        } else {
                            channel.sendMessageEmbeds(embedsToSend)
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

    /**
     * Polls the SPC Day 1-3 categorical outlooks and posts the updated image to each guild's
     * "outlook" channel whenever a new outlook is issued. The first observation of each day is
     * recorded silently so a restart doesn't replay the current outlooks.
     */
    private void checkOutlooks() {
        if (guildChannels.isEmpty()) return;

        try {
            boolean dirty = false;
            for (Outlooks.Day day : List.of(Outlooks.Day.ONE, Outlooks.Day.TWO, Outlooks.Day.THREE)) {
                String issuance = Outlooks.fetchIssuanceId(day);
                if (issuance == null) continue;

                String previous = outlookIssuance.put(day.number(), issuance);
                if (previous == null) {
                    dirty = true;
                    continue;
                }
                if (issuance.equals(previous)) continue;

                dirty = true;
                logger.info("New SPC Day {} outlook issued: {} (was {})", day.number(), issuance, previous);

                byte[] image;
                try {
                    image = Outlooks.fetch(day);
                } catch (Exception e) {
                    logger.error("Failed to fetch Day {} outlook image: {}", day.number(), e.getMessage());
                    continue;
                }

                Instant issued = Outlooks.issuanceInstant(issuance);
                String message = buildOutlookMessage(day, issued, true);

                for (Map.Entry<Long, Map<String, Long>> guildEntry : guildChannels.entrySet()) {
                    long guildId = guildEntry.getKey();
                    Long channelId = guildEntry.getValue().get("outlook");
                    if (channelId == null) continue;

                    MessageCreateAction target;
                    ThreadChannel thread = resolveDailyOutlookThread(channelId, guildId);
                    if (thread != null) {
                        target = thread.sendMessage(message);
                    } else {
                        TextChannel channel = jda.getTextChannelById(channelId);
                        if (channel == null) {
                            logger.warn("Outlook channel {} not usable for guild {}", channelId, guildId);
                            continue;
                        }
                        target = channel.sendMessage(message);
                    }
                    target.addFiles(FileUpload.fromData(image, day.fileName()))
                            .queue(
                                    msg -> logger.info("Posted Day {} outlook to guild {}", day.number(), guildId),
                                    err -> logger.error("Failed to post Day {} outlook to guild {}: {}", day.number(), guildId, err.getMessage())
                            );
                }
            }

            if (dirty) saveConfig();

        } catch (Exception e) {
            logger.error("Error during outlook check", e);
        }
    }

    /**
     * Builds the text that accompanies an outlook image. Includes a Discord dynamic timestamp
     * ({@code <t:…>}, which each viewer sees in their own local time) plus a fixed UTC string so
     * the exact issuance/post time is searchable.
     */
    private String buildOutlookMessage(Outlooks.Day day, Instant issued, boolean updated) {
        StringBuilder sb = new StringBuilder("**SPC Day ").append(day.number())
                .append(" Convective Outlook**");
        if (updated) sb.append(" — updated");
        sb.append('\n');

        if (issued != null) {
            long epoch = issued.getEpochSecond();
            sb.append("**Issued:** <t:").append(epoch).append(":F> (<t:").append(epoch).append(":R>) · `")
                    .append(UTC_STAMP.format(issued)).append('`');
        } else {
            Instant now = Instant.now();
            sb.append("**Posted:** <t:").append(now.getEpochSecond()).append(":F> · `")
                    .append(UTC_STAMP.format(now)).append('`');
        }
        return sb.toString();
    }

    /**
     * Returns the thread that the given guild's outlook updates for the current UTC day should be
     * posted into, creating it on first use. Works whether the configured "outlook" channel is a
     * forum channel (a forum post is created) or a text channel (a public thread is created).
     * Returns {@code null} if no thread can be created, in which case the caller should fall back to
     * posting in the channel directly.
     */
    private ThreadChannel resolveDailyOutlookThread(long channelId, long guildId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String dateKey = today.toString();
        Map<String, Long> byDate = outlookThreads.computeIfAbsent(guildId, k -> new ConcurrentHashMap<>());

        Long existingId = byDate.get(dateKey);
        if (existingId != null) {
            ThreadChannel existing = jda.getThreadChannelById(existingId);
            if (existing != null) return existing;
        }

        String name = "SPC Outlooks — " + OUTLOOK_THREAD_DATE.format(today);
        try {
            ThreadChannel thread;
            ForumChannel forum = jda.getForumChannelById(channelId);
            if (forum != null) {
                String intro = "Daily SPC Day 1–3 Convective Outlook thread for "
                        + OUTLOOK_THREAD_DATE.format(today)
                        + ". Each update below is timestamped in your local time and in UTC.";
                thread = forum.createForumPost(name, MessageCreateData.fromContent(intro))
                        .complete()
                        .getThreadChannel();
            } else {
                TextChannel text = jda.getTextChannelById(channelId);
                if (text == null) {
                    logger.warn("Outlook channel {} is neither a forum nor a text channel (guild {})", channelId, guildId);
                    return null;
                }
                thread = text.createThreadChannel(name)
                        .setAutoArchiveDuration(ThreadChannel.AutoArchiveDuration.TIME_1_WEEK)
                        .complete();
            }

            byDate.keySet().removeIf(k -> {
                try {
                    return LocalDate.parse(k).isBefore(today.minusDays(7));
                } catch (RuntimeException e) {
                    return true;
                }
            });
            byDate.put(dateKey, thread.getIdLong());
            saveConfig();
            logger.info("Created daily outlook thread '{}' for guild {}", name, guildId);
            return thread;
        } catch (Exception e) {
            logger.error("Failed to create daily outlook thread for guild {}: {}", guildId, e.getMessage());
            return null;
        }
    }

    private static double toF(double celsius) {
        return celsius * 9.0 / 5.0 + 32.0;
    }

    private MessageEmbed buildAlertReportEmbed(String today, Map<String, Integer> alertCounts) {
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("Daily Weather Report — " + today)
                .setColor(new Color(0xE74C3C));

        if (alertCounts.isEmpty()) {
            eb.setDescription("No alerts logged today.");
        } else {
            StringBuilder sb = new StringBuilder();
            alertCounts.forEach((name, count) ->
                    sb.append("**").append(name).append("**: ").append(count).append("\n"));
            eb.setDescription(sb.toString().strip());
        }

        return eb.build();
    }

    private MessageEmbed buildTempestReportEmbed(String today, Database.TempestStats s) {
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("Tempest Station — " + today)
                .setColor(new Color(0x3498DB));

        if (!s.hasData()) {
            eb.setDescription("No observations logged yet.");
            return eb.build();
        }

        eb.addField("Temperature",
                String.format("%.1f°F – %.1f°F", toF(s.minAirTemp()), toF(s.maxAirTemp())), true)
          .addField("Feels Like",
                String.format("%.1f°F – %.1f°F", toF(s.minFeelsLike()), toF(s.maxFeelsLike())), true)
          .addField("Dew Point",
                String.format("%.1f°F – %.1f°F", toF(s.minDewPoint()), toF(s.maxDewPoint())), true)
          .addField("Wind (Low – Gust)",
                String.format("%.1f – %.1f mph", s.minWindAvg() * 2.23694, s.maxWindGust() * 2.23694), true)
          .addField("Rainfall",
                String.format("%.2f in", s.totalRain() * 0.0393701), true)
          .addField("Lightning",
                s.totalLightning() + " strikes", true)
          .addField("Humidity",
                String.format("%.0f%% – %.0f%%", s.minHumidity(), s.maxHumidity()), true)
          .addField("Sea Level Pressure",
                String.format("%.1f – %.1f mb", s.minPressure(), s.maxPressure()), true);

        return eb.build();
    }

    private void postDailyReport() {
        String today = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));
        MessageEmbed alertEmbed   = buildAlertReportEmbed(today, db.getDailyAlertCounts());
        MessageEmbed tempestEmbed = buildTempestReportEmbed(today, db.getDailyTempestStats());

        for (Map.Entry<Long, Map<String, Long>> guildEntry : guildChannels.entrySet()) {
            Long channelId = guildEntry.getValue().get("report");
            if (channelId == null) continue;
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                logger.warn("Report channel {} not found for guild {}", channelId, guildEntry.getKey());
                continue;
            }
            long guildId = guildEntry.getKey();
            channel.sendMessageEmbeds(alertEmbed).queue(
                    msg -> channel.sendMessageEmbeds(tempestEmbed).queue(
                            msg2 -> logger.info("Posted daily report to guild {}", guildId),
                            err  -> logger.error("Failed to post Tempest report to guild {}: {}", guildId, err.getMessage())
                    ),
                    err -> logger.error("Failed to post alert report to guild {}: {}", guildId, err.getMessage())
            );
        }
    }

    private long secondsUntilNextReportUTC() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime next = now.toLocalDate().atTime(23, 59).atZone(ZoneOffset.UTC);
        if (!now.isBefore(next)) next = next.plusDays(1);
        return Duration.between(now, next).getSeconds();
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

    private MessageEmbed buildHistoryEmbed(MessageEmbed base, String historyImageUrl) {
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("Storm Based Warning History")
                .setImage(historyImageUrl);
        if (base.getColor() != null) eb.setColor(base.getColor());
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
        if ("runreport".equals(event.getName())) {
            event.deferReply().queue();
            String today = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("MMMM d, yyyy"));
            MessageEmbed alertEmbed   = buildAlertReportEmbed(today, db.getDailyAlertCounts());
            MessageEmbed tempestEmbed = buildTempestReportEmbed(today, db.getDailyTempestStats());
            event.getHook().sendMessageEmbeds(alertEmbed).queue(
                    msg -> event.getHook().sendMessageEmbeds(tempestEmbed).queue(),
                    err -> logger.error("Failed to send alert report embed: {}", err.getMessage())
            );
            return;
        }

        Outlooks.Day outlookDay = Outlooks.Day.fromCommand(event.getName());
        if (outlookDay != null) {
            event.deferReply().queue();
            try {
                byte[] image = Outlooks.fetch(outlookDay);
                Instant issued = Outlooks.issuanceInstant(Outlooks.fetchIssuanceId(outlookDay));
                event.getHook()
                        .sendMessage(buildOutlookMessage(outlookDay, issued, false))
                        .addFiles(FileUpload.fromData(image, outlookDay.fileName()))
                        .queue();
            } catch (Exception e) {
                logger.error("Failed to fetch Day {} outlook: {}", outlookDay.number(), e.getMessage());
                event.getHook().sendMessage("Couldn't fetch the Day " + outlookDay.number()
                        + " outlook right now. Try again shortly.").setEphemeral(true).queue();
            }
            return;
        }

        if (event.getGuild() == null) return;

        if ("gettempestobs".equals(event.getName())) {
            event.deferReply().queue();
            List<AlertEmbed> obs = new Tempest().getObs();
            if (obs.isEmpty()) {
                event.getHook().sendMessage("No observations available.").setEphemeral(true).queue();
            } else {
                event.getHook().sendMessageEmbeds(obs.get(0).embed()).queue();
            }
            return;
        }

        String alertType = switch (event.getName()) {
            case "setseverechannel"  -> "severe";
            case "settorchannel"     -> "tornado";
            case "setwinterchannel"  -> "winter";
            case "setswschannel"     -> "sws";
            case "settorpdschannel"     -> "torpds";
            case "setsvrpdschannel"     -> "svrpds";
            case "setfloodchannel"   -> "flood";
            case "setwatchchannel"   -> "watch";
            case "setreportchannel"  -> "report";
            case "setoutlookchannel" -> "outlook";
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
        GuildChannel channel = jda.getGuildChannelById(channelId);
        boolean forumOk = "outlook".equals(alertType) && channel instanceof ForumChannel;
        if (channel == null || !(channel instanceof TextChannel || forumOk)) {
            event.reply("Channel not found or unsupported — pick a text channel the bot can see"
                    + " (the outlook channel may also be a forum channel).").setEphemeral(true).queue();
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

            ObjectNode outlooks = mapper.createObjectNode();
            outlookIssuance.forEach((day, id) -> outlooks.put(String.valueOf(day), id));
            root.set("outlookIssuance", outlooks);

            ObjectNode threads = mapper.createObjectNode();
            outlookThreads.forEach((guildId, byDate) -> {
                ObjectNode dateNode = mapper.createObjectNode();
                byDate.forEach(dateNode::put);
                threads.set(String.valueOf(guildId), dateNode);
            });
            root.set("outlookThreads", threads);

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

                for (Map.Entry<String, JsonNode> e : root.path("outlookIssuance").properties()) {
                    outlookIssuance.put(Integer.parseInt(e.getKey()), e.getValue().asText());
                }

                for (Map.Entry<String, JsonNode> guildEntry : root.path("outlookThreads").properties()) {
                    long guildId = Long.parseLong(guildEntry.getKey());
                    Map<String, Long> byDate = new ConcurrentHashMap<>();
                    for (Map.Entry<String, JsonNode> d : guildEntry.getValue().properties()) {
                        byDate.put(d.getKey(), d.getValue().asLong());
                    }
                    outlookThreads.put(guildId, byDate);
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
        channelDefaults.put("torpds",     1529920418000142466L);
        channelDefaults.put("svrpds",     1529919999597088829L);
        channelDefaults.put("flood",   1514663611035943073L);
        channelDefaults.put("watch",   1518727400278458440L);
        channelDefaults.put("report",  1519027873686487101L);
        channelDefaults.put("outlook", 1547277391678668810L);

        long defaultGuildId = 828991980805685258L;
        Map<String, Long> guildMap = guildChannels.computeIfAbsent(defaultGuildId, k -> new ConcurrentHashMap<>());
        postedItems.computeIfAbsent(defaultGuildId, k -> ConcurrentHashMap.newKeySet());

        boolean changed = false;

        // Outlook posting moved to the forum channel; migrate configs still pointing at the old text channel.
        for (Map<String, Long> typeMap : guildChannels.values()) {
            if (Long.valueOf(1546921737319354498L).equals(typeMap.get("outlook"))) {
                typeMap.put("outlook", 1547277391678668810L);
                logger.info("Migrated outlook channel to forum channel 1547277391678668810");
                changed = true;
            }
        }

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
