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

public class SevereThunderstorm {

    private static final String TSTORM_URL = "https://api.weather.gov/alerts/active?status=actual&message_type=alert,update&event=severe%20thunderstorm%20warning,severe%20thunderstorm%20watch,thunderstorm%20warning,thunderstorm%20watch";

    public List<AlertEmbed> getSvrTStorm() {
        List<AlertEmbed> embeds = new ArrayList<>();

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new URL(TSTORM_URL).openStream());
            JsonNode features = root.get("features");

            for (JsonNode feature : features) {
                JsonNode props = feature.get("properties");

                String alertId = feature.path("id").asText("");
                String event = props.get("event").asText();
                String areaDesc = props.get("areaDesc").asText();
                String description = props.get("description").asText();
                String expiresRaw = props.path("expires").asText(null);

                String truncatedDesc = description.length() > 500
                        ? description.substring(0, 500) + "..."
                        : description;

                Color color;
                String descLower = description.toLowerCase();
                if (descLower.contains("confirmed") || descLower.contains("destructive") || descLower.contains("considerable")
                        || description.contains("Damaging") || descLower.contains("observed")) {
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
                        .setTitle("🌩️ " + event, TSTORM_URL)
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
