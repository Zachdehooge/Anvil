package dev.zachdehooge.Alerts;

import dev.zachdehooge.AlertEmbed;
import dev.zachdehooge.AmbientColors;
import net.dv8tion.jda.api.EmbedBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.awt.Color;
import java.net.URL;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class SevereThunderstorm {

    private static final String TSTORM_URL = "https://api.weather.gov/alerts/active?status=actual&message_type=alert,update&event=severe%20thunderstorm%20warning,thunderstorm%20warning";

    public List<AlertEmbed> getSvrTStorm() {
        List<AlertEmbed> embeds = new ArrayList<>();

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new URL(TSTORM_URL).openStream());
            JsonNode features = root.get("features");

            for (JsonNode feature : features) {
                JsonNode props = feature.get("properties");
                JsonNode parameters = props.get("parameters");

                String alertId = feature.path("id").asString("");
                String event = props.get("event").asString();
                String areaDesc = props.get("areaDesc").asString();
                String description = props.get("description").asString();
                String severity = props.get("severity").asString();
                String nwsOffice = props.get("senderName").asString();
                String maxWindGust = getParam(parameters, "maxWindGust");
                String maxHailSize = getParam(parameters, "maxHailSize");
                String tornadoDetection = getParam(parameters, "tornadoDetection");
                String expiresRaw = props.path("expires").asString(null);

                Color color = event.toLowerCase().contains("warning") ? AmbientColors.WARNING : AmbientColors.WATCH;

                String expiresValue = "Unknown";
                OffsetDateTime expiresTime = null;
                if (expiresRaw != null && !expiresRaw.isBlank()) {
                    expiresTime = OffsetDateTime.parse(expiresRaw);
                    expiresValue = "<t:" + expiresTime.toEpochSecond() + ":R>";
                }

                EmbedBuilder builder = new EmbedBuilder()
                        .setTitle(nwsOffice + " has issued a:\n🌩️ " + event, TSTORM_URL)
                        .setDescription("**Area:** " + areaDesc)
                        .addField("Severity: ", severity, false)
                        .addField("Max Wind Gust: ", maxWindGust, false)
                        .addField("Max Hail Size: ", maxHailSize, false)
                        .addField("Tornado: ", tornadoDetection, false)
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
