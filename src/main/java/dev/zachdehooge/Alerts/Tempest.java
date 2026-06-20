package dev.zachdehooge.Alerts;

import dev.zachdehooge.AlertEmbed;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.EmbedBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.awt.*;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class Tempest {

    String envPath = System.getProperty("env.path", ".");
    Dotenv env = Dotenv.configure().directory(envPath).filename(".env").load();

    private final String TEMPEST_URL = String.format("https://swd.weatherflow.com/swd/rest/observations/station/212384?token=%s", env.get("TEMPEST_TOKEN"));

    LocalDateTime myDateObj = LocalDateTime.now();
    DateTimeFormatter myFormatObj = DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm:ss");
    String formattedDate = myDateObj.format(myFormatObj);

    public List<AlertEmbed> getObs() {
        List<AlertEmbed> embeds = new ArrayList<>();

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new URL(TEMPEST_URL).openStream());
            JsonNode observations = root.get("obs");

            for (JsonNode obs : observations) {
                JsonNode airTemp = obs.get("air_temperature");
                JsonNode humidity = obs.get("relative_humidity");
                JsonNode pressure = obs.get("sea_level_pressure");
                JsonNode feelsLike = obs.get("feels_like");

                EmbedBuilder builder = new EmbedBuilder()
                        .setTitle("Tempest Metrics for: " + formattedDate)
                        .setColor(Color.CYAN)
                        .addField("Temperature", airTemp != null ? airTemp.asText() + " °C" : "N/A", true)
                        .addField("Feels Like", feelsLike != null ? feelsLike.asText() + " °C" : "N/A", true)
                        .addField("Humidity", humidity != null ? humidity.asText() + "%" : "N/A", true)
                        .addField("Sea Level Pressure", pressure != null ? pressure.asText() + " mb" : "N/A", true);

                embeds.add(new AlertEmbed("tempest-obs", builder.build(), ""));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return embeds;
    }
}
