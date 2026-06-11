package dev.zachdehooge.Alerts;

import dev.zachdehooge.AlertEmbed;
import net.dv8tion.jda.api.EmbedBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.awt.*;
import java.net.URL;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class PDS {

    private static final String TORNADO_URL = "https://api.weather.gov/alerts/active?status=actual&message_type=alert,update&event=tornado%20warning,tornado%20emergency";
    private static final String TSTORM_URL  = "https://api.weather.gov/alerts/active?status=actual&message_type=alert,update&event=severe%20thunderstorm%20warning,severe%20thunderstorm%20watch,thunderstorm%20warning,thunderstorm%20watch";
    private static final String WINTER_URL  = "https://api.weather.gov/alerts/active?status=actual&message_type=alert,update&event=winter%20storm%20warning,winter%20storm%20watch,blizzard%20warning,blizzard%20watch,ice%20storm%20warning,ice%20storm%20watch,heavy%20snow%20warning,snow%20squall%20warning,lake%20effect%20snow%20warning,freezing%20rain%20advisory,wind%20chill%20warning";
    private static final String FLOOD_URL   = "https://api.weather.gov/alerts/active?status=actual&message_type=alert,update&event=flash%20flood%20emergency,flash%20flood%20warning,flash%20flood%20watch,flood%20warning,flood%20watch";

    @FunctionalInterface
    private interface ColorResolver {
        Color resolve(String event, String description);
    }

    public List<AlertEmbed> getPDS() {
        CompletableFuture<List<AlertEmbed>> tornadoCf = CompletableFuture.supplyAsync(() ->
                fetchAlerts(TORNADO_URL, "🌪️", (event, desc) -> {
                    String d = desc.toLowerCase();
                    if (d.contains("confirmed") || d.contains("destructive") || desc.contains("Damaging") || d.contains("observed"))
                        return new Color(0xAA00FF);
                    return event.toLowerCase().contains("warning") ? Color.RED : Color.ORANGE;
                }));

        CompletableFuture<List<AlertEmbed>> winterCf = CompletableFuture.supplyAsync(() ->
                fetchAlerts(WINTER_URL, "❄", (event, desc) -> {
                    if (event.toLowerCase().contains("blizzard")) return new Color(0xAA00FF);
                    return event.toLowerCase().contains("warning") ? Color.RED : Color.ORANGE;
                }));

        CompletableFuture<List<AlertEmbed>> tstormCf = CompletableFuture.supplyAsync(() ->
                fetchAlerts(TSTORM_URL, "🌩️", (event, desc) -> {
                    String d = desc.toLowerCase();
                    if (d.contains("confirmed") || d.contains("destructive") || d.contains("considerable")
                            || desc.contains("Damaging") || d.contains("observed") || d.contains("pds") || d.contains("emergency"))
                        return new Color(0xAA00FF);
                    return event.toLowerCase().contains("warning") ? Color.RED : Color.ORANGE;
                }));

        CompletableFuture<List<AlertEmbed>> floodCf = CompletableFuture.supplyAsync(() ->
                fetchAlerts(FLOOD_URL, "🌊", (event, desc) -> {
                    String e = event.toLowerCase();
                    String d = desc.toLowerCase();
                    if (e.contains("emergency") || d.contains("catastrophic") || d.contains("life-threatening"))
                        return new Color(0xAA00FF);
                    return e.contains("warning") ? Color.RED : Color.ORANGE;
                }));

        List<AlertEmbed> embeds = new ArrayList<>();
        for (CompletableFuture<List<AlertEmbed>> cf : List.of(tornadoCf, winterCf, tstormCf, floodCf)) {
            try {
                embeds.addAll(cf.get(30, TimeUnit.SECONDS));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return embeds;
    }

    private List<AlertEmbed> fetchAlerts(String url, String emoji, ColorResolver colorResolver) {
        List<AlertEmbed> embeds = new ArrayList<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new URL(url).openStream());
            JsonNode features = root.get("features");

            for (JsonNode feature : features) {
                JsonNode props = feature.get("properties");

                String alertId    = feature.path("id").asText("");
                String event      = props.get("event").asText();
                String areaDesc   = props.get("areaDesc").asText();
                String description = props.get("description").asText();
                String expiresRaw = props.path("expires").asText(null);

                String truncatedDesc = description.length() > 500
                        ? description.substring(0, 500) + "..."
                        : description;

                Color color = colorResolver.resolve(event, description);

                if (!color.equals(new Color(0xAA00FF))) continue;

                String expiresValue = "Unknown";
                OffsetDateTime expiresTime = null;
                if (expiresRaw != null && !expiresRaw.isBlank()) {
                    expiresTime = OffsetDateTime.parse(expiresRaw);
                    expiresValue = "<t:" + expiresTime.toEpochSecond() + ":R>";
                }

                EmbedBuilder builder = new EmbedBuilder()
                        .setTitle(emoji + " " + event, url)
                        .setDescription("**Area:** " + areaDesc + "\n\n" + truncatedDesc)
                        .setColor(color)
                        .addField("Expires:", expiresValue, false);

                if (expiresTime != null) {
                    builder.setTimestamp(expiresTime);
                }

                embeds.add(new AlertEmbed(alertId, builder.build()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return embeds;
    }
}
