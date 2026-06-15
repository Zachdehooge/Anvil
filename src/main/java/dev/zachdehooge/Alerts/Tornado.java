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

public class Tornado {

    private static final String TORNADO_URL = "https://api.weather.gov/alerts/active?status=actual&message_type=alert,update&event=tornado%20watch,tornado%20warning,tornado%20emergency";

    public List<AlertEmbed> getTornado() {
        List<AlertEmbed> embeds = new ArrayList<>();

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new URL(TORNADO_URL).openStream());
            JsonNode features = root.get("features");

            for (JsonNode feature : features) {
                JsonNode props = feature.get("properties");

                String alertId = feature.path("id").asText("");
                String event = props.get("event").asText();
                String areaDesc = props.get("areaDesc").asText();
                String description = props.get("description").asText();
                String expiresRaw = props.path("expires").asText(null);

                Color color;
                String descLower = description.toLowerCase();
                if (descLower.contains("confirmed") || descLower.contains("destructive")
                        || descLower.contains("damaging") || descLower.contains("observed")
                        || descLower.contains("particularly dangerous situation")
                        || event.toLowerCase().contains("tornado emergency")) {
                    color = new Color(0xAA00FF);
                } else if (event.toLowerCase().contains("warning")) {
                    color = Color.RED;
                } else {
                    color = Color.ORANGE;
                }

                String expiresValue = "Unknown";
                OffsetDateTime expiresTime = null;
                if (expiresRaw != null && !expiresRaw.isBlank()) {
                    expiresTime = OffsetDateTime.parse(expiresRaw);
                    expiresValue = "<t:" + expiresTime.toEpochSecond() + ":R>";
                }

                EmbedBuilder builder = new EmbedBuilder()
                        .setTitle("🌪️ " + event, TORNADO_URL)
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
