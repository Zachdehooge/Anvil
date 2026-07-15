package dev.zachdehooge.Alerts;

import dev.zachdehooge.AlertEmbed;
import dev.zachdehooge.AmbientColors;
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

    private static final String TORNADO_URL = "https://api.weather.gov/alerts/active?status=actual&message_type=alert,update&event=tornado%20warning,tornado%20emergency,tornado%20watch";
    private static final String TSTORM_URL  = "https://api.weather.gov/alerts/active?status=actual&message_type=alert,update&event=severe%20thunderstorm%20warning,severe%20thunderstorm%20watch,thunderstorm%20warning,thunderstorm%20watch";
    private static final String WINTER_URL  = "https://api.weather.gov/alerts/active?status=actual&message_type=alert,update&event=winter%20storm%20warning,winter%20storm%20watch,blizzard%20warning,blizzard%20watch,ice%20storm%20warning,ice%20storm%20watch,heavy%20snow%20warning,snow%20squall%20warning,lake%20effect%20snow%20warning,freezing%20rain%20advisory,wind%20chill%20warning";
    private static final String FLOOD_URL   = "https://api.weather.gov/alerts/active?status=actual&message_type=alert,update&event=flash%20flood%20emergency,flash%20flood%20warning,flash%20flood%20watch,flood%20warning,flood%20watch";

    @FunctionalInterface
    private interface ColorResolver {
        Color resolve(String event, String description, JsonNode parameters);
    }

    public List<AlertEmbed> getPDS() {
        CompletableFuture<List<AlertEmbed>> tornadoCf = CompletableFuture.supplyAsync(() ->
                fetchAlerts(TORNADO_URL, "🌪️", (_, description, params) -> {
                    String tornadoDetect = getParam(params, "tornadoDetection").toLowerCase();
                    String tornadoDamage = getParam(params, "tornadoDamageThreat").toLowerCase();
                    if (tornadoDetect.contains("observed") || tornadoDamage.contains("considerable") || tornadoDamage.contains("catastrophic"))
                        return AmbientColors.PDS_WARNING;
                    return description.toLowerCase().contains("warning") ? AmbientColors.WARNING : AmbientColors.WATCH;
                }));

        CompletableFuture<List<AlertEmbed>> winterCf = CompletableFuture.supplyAsync(() ->
                fetchAlerts(WINTER_URL, "❄", (_, description, _) -> {
                    if (description.toLowerCase().contains("blizzard")) return AmbientColors.PDS_WARNING;
                    return description.toLowerCase().contains("warning") ? AmbientColors.WARNING : AmbientColors.WATCH;
                }));

        CompletableFuture<List<AlertEmbed>> tstormCf = CompletableFuture.supplyAsync(() ->
                fetchAlerts(TSTORM_URL, "🌩️", (_, description, params) -> {
                    String p = getParam(params, "thunderstormDamageThreat").toLowerCase();
                    String tornado = getParam(params, "tornadoDetection").toLowerCase();
                    if (p.contains("considerable") || p.contains("destructive") || tornado.contains("possible"))
                        return AmbientColors.PDS_WARNING;
                    return description.toLowerCase().contains("warning") ? AmbientColors.WARNING : AmbientColors.WATCH;
                }));

//        CompletableFuture<List<AlertEmbed>> floodCf = CompletableFuture.supplyAsync(() ->
//                fetchAlerts(FLOOD_URL, "🌊", (_, description, params) -> {
//                    String detection = getParam(params, "flashFloodDetection").toLowerCase();
//                    String damage = getParam(params, "flashFloodDamageThreat").toLowerCase();
//                    if (damage.contains("considerable") || damage.contains("catastrophic"))
//                        return AmbientColors.PDS_WARNING;
//                    return description.toLowerCase().contains("warning") ? AmbientColors.WARNING : AmbientColors.WATCH;
//                }));

        List<AlertEmbed> embeds = new ArrayList<>();
        for (CompletableFuture<List<AlertEmbed>> cf : List.of(tornadoCf, winterCf, tstormCf/*, floodCf*/)) {
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
                JsonNode parameters = props.get("parameters");

                String alertId    = feature.path("id").asString("");
                String event      = props.get("event").asString();
                String areaDesc   = props.get("areaDesc").asString();
                String description = props.get("description").asString();
                String nwsOffice = props.get("senderName").asString();
                String thunderstormDamageThreat = getParam(parameters, "thunderstormDamageThreat");
                String maxWindGust = getParam(parameters, "maxWindGust");
                String maxHailSize = getParam(parameters, "maxHailSize");
                String tornadoDetection = getParam(parameters, "tornadoDetection");
                String tornadoDamageThreat = getParam(parameters, "tornadoDamageThreat");
                String expiresRaw = props.path("expires").asString(null);

                Color color = colorResolver.resolve(event, description, parameters);

                if (!color.equals(AmbientColors.PDS_WARNING)) continue;

                String expiresValue = "Unknown";
                OffsetDateTime expiresTime = null;
                if (expiresRaw != null && !expiresRaw.isBlank()) {
                    expiresTime = OffsetDateTime.parse(expiresRaw);
                    expiresValue = "<t:" + expiresTime.toEpochSecond() + ":R>";
                }

                EmbedBuilder builder = new EmbedBuilder()
                        .setTitle(nwsOffice + " has issued a:\n" + emoji + " " + event, url)
                        .setDescription("**Area:** " + areaDesc)
                        .addField("Damage Threat: ", thunderstormDamageThreat, false)
                        .addField("Max Wind Gust: ", maxWindGust, false)
                        .addField("Max Hail Size: ", maxHailSize, false)
                        .addField("Tornado: ", tornadoDetection, false)
                        .addField("Tornado Damage: ", tornadoDamageThreat, false)
                        .setColor(color)
                        .addField("Expires:", expiresValue, false);

                if (expiresTime != null) {
                    builder.setTimestamp(expiresTime);
                }

                embeds.add(new AlertEmbed(alertId, builder.build(), description, event));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return embeds;
    }

    private String getParam(JsonNode parameters, String key) {
        if (parameters == null || !parameters.has(key)) return "N/A";
        JsonNode arr = parameters.get(key);
        return (arr != null && arr.isArray() && arr.size() > 0) ? arr.get(0).asString() : "N/A";
    }
}
