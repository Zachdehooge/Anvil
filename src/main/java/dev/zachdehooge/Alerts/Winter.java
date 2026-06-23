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

public class Winter {

    private static final String WINTER_URL = "https://api.weather.gov/alerts/active?status=actual&message_type=alert,update&event=winter%20storm%20warning,blizzard%20warning,ice%20storm%20warning,heavy%20snow%20warning,snow%20squall%20warning,lake%20effect%20snow%20warning,freezing%20rain%20advisory,wind%20chill%20warning";

    public List<AlertEmbed> getWinter() {
        List<AlertEmbed> embeds = new ArrayList<>();

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new URL(WINTER_URL).openStream());
            JsonNode features = root.get("features");

            for (JsonNode feature : features) {
                JsonNode props = feature.get("properties");

                String alertId = feature.path("id").asText("");
                String event = props.get("event").asText();
                String areaDesc = props.get("areaDesc").asText();
                String description = props.get("description").asText();
                String expiresRaw = props.path("expires").asText(null);

                Color color = event.toLowerCase().contains("warning") ? AmbientColors.WARNING : AmbientColors.WATCH;

                String expiresValue = "Unknown";
                OffsetDateTime expiresTime = null;
                if (expiresRaw != null && !expiresRaw.isBlank()) {
                    expiresTime = OffsetDateTime.parse(expiresRaw);
                    expiresValue = "<t:" + expiresTime.toEpochSecond() + ":R>";
                }

                EmbedBuilder builder = new EmbedBuilder()
                        .setTitle("❄ " + event, WINTER_URL)
                        .setDescription("**Area:** " + areaDesc)
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
}
