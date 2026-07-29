package dev.zachdehooge.Alerts;

import dev.zachdehooge.AlertEmbed;
import dev.zachdehooge.AmbientColors;
import dev.zachdehooge.Utilities.RadarSnippet;
import net.dv8tion.jda.api.EmbedBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.awt.Color;
import java.net.URL;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class SpecialWeatherStatement {

    private static final String SWS_URL = "https://api.weather.gov/alerts/active?event=special%20weather%20statement";

    public List<AlertEmbed> getSWS() {
        List<AlertEmbed> embeds = new ArrayList<>();

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new URL(SWS_URL).openStream());
            JsonNode features = root.get("features");

            for (JsonNode feature : features) {
                JsonNode props = feature.get("properties");
                JsonNode parameters = props.get("parameters");

                String alertId = feature.path("id").asText("");
                String event = props.get("event").asText();
                String areaDesc = props.get("areaDesc").asText();
                String description = props.get("description").asText();
                String nwsOffice = props.get("senderName").asString();
                String expiresRaw = props.path("expires").asText(null);
                String sentRaw = props.path("sent").asText(null);

                Color color = AmbientColors.SWS;
                String expiresValue = "Unknown";
                OffsetDateTime expiresTime = null;
                if (expiresRaw != null && !expiresRaw.isBlank()) {
                    expiresTime = OffsetDateTime.parse(expiresRaw);
                    expiresValue = "<t:" + expiresTime.toEpochSecond() + ":R>";
                }

                EmbedBuilder builder = new EmbedBuilder()
                        .setTitle(nwsOffice + " has issued a:\n⚠️ " + event, SWS_URL)
                        .setDescription("**Area:** " + areaDesc)
                        .setColor(color)
                        .addField("Expires:", expiresValue, false);

                if (expiresTime != null) {
                    builder.setTimestamp(expiresTime);
                }

                RadarSnippet.RadarImages radar = RadarSnippet.getRadarImages(getParam(parameters, "VTEC"), sentRaw);
                String historyImageUrl = null;
                if (radar != null) {
                    builder.setImage(radar.compositeUrl());
                    historyImageUrl = radar.historyUrl();
                }

                embeds.add(new AlertEmbed(alertId, builder.build(), description, event, historyImageUrl));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return embeds;
    }

    private String getParam(JsonNode parameters, String key) {
        if (parameters == null || !parameters.has(key)) return null;
        JsonNode arr = parameters.get(key);
        return (arr != null && arr.isArray() && !arr.isEmpty()) ? arr.get(0).asString() : null;
    }
}
