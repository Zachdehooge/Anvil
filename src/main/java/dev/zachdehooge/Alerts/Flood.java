package dev.zachdehooge.Alerts;

import dev.zachdehooge.AlertEmbed;
import net.dv8tion.jda.api.EmbedBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.awt.Color;
import java.net.URL;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class Flood {

    private static final String FLOOD_URL = "https://api.weather.gov/alerts/active?status=actual&message_type=alert,update&event=flash%20flood%20emergency,flash%20flood%20warning,flash%20flood%20watch,flood%20warning,flood%20watch,flood%20advisory";

    public List<AlertEmbed> getFlood() {
        List<AlertEmbed> embeds = new ArrayList<>();

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new URL(FLOOD_URL).openStream());
            JsonNode features = root.get("features");

            for (JsonNode feature : features) {
                JsonNode props = feature.get("properties");

                String alertId = feature.path("id").asText("");
                String event = props.get("event").asText();
                String areaDesc = props.get("areaDesc").asText();
                String description = props.get("description").asText();
                String expiresRaw = props.path("expires").asText(null);

                String e = event.toLowerCase();
                String d = description.toLowerCase();
                Color color;
                if (e.contains("emergency") || d.contains("catastrophic") || d.contains("life-threatening")) {
                    color = new Color(0xAA00FF);
                } else if (e.contains("warning")) {
                    color = Color.RED;
                } else {
                    color = Color.GREEN;
                }

                String expiresValue = "Unknown";
                OffsetDateTime expiresTime = null;
                if (expiresRaw != null && !expiresRaw.isBlank()) {
                    expiresTime = OffsetDateTime.parse(expiresRaw);
                    expiresValue = "<t:" + expiresTime.toEpochSecond() + ":R>";
                }

                EmbedBuilder builder = new EmbedBuilder()
                        .setTitle("🌊 " + event, FLOOD_URL)
                        .setDescription("**Area:** " + areaDesc)
                        .setColor(color)
                        .addField("Expires:", expiresValue, false);

                if (expiresTime != null) {
                    builder.setTimestamp(expiresTime);
                }

                embeds.add(new AlertEmbed(alertId, builder.build(), description));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return embeds;
    }
}
