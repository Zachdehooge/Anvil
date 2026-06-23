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
import java.util.Optional;

public class Tempest {

    public record TempestObsRaw(double airTemp, double feelsLike, double humidity, double pressure,
                                double dewPoint, double windAvg, double windGust, double rainAccumLocal, int lightningCount) {}

    String envPath = System.getProperty("env.path", ".");
    Dotenv env = Dotenv.configure().directory(envPath).filename(".env").load();

    private final String TEMPEST_URL = String.format("https://swd.weatherflow.com/swd/rest/observations/station/212384?token=%s", env.get("TEMPEST_TOKEN"));

    LocalDateTime myDateObj = LocalDateTime.now();
    DateTimeFormatter myFormatObj = DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm:ss");
    String formattedDate = myDateObj.format(myFormatObj);

    private JsonNode cachedObservations = null;

    private JsonNode fetchObservations() throws Exception {
        if (cachedObservations == null) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(new URL(TEMPEST_URL).openStream());
            cachedObservations = root.get("obs");
        }
        return cachedObservations;
    }

    public Optional<TempestObsRaw> getRawObs() {
        try {
            JsonNode observations = fetchObservations();
            if (observations == null || !observations.isArray() || observations.isEmpty()) return Optional.empty();

            JsonNode obs = observations.get(0);
            JsonNode airTemp        = obs.get("air_temperature");
            JsonNode feelsLike      = obs.get("feels_like");
            JsonNode humidity       = obs.get("relative_humidity");
            JsonNode pressure       = obs.get("sea_level_pressure");
            JsonNode dewPoint       = obs.get("dew_point");
            JsonNode windAvg        = obs.get("wind_avg");
            JsonNode windGust       = obs.get("wind_gust");
            JsonNode rainAccum      = obs.get("precip_accum_local_day");
            JsonNode lightningCount = obs.get("lightning_strike_count");

            if (airTemp == null || feelsLike == null || humidity == null || pressure == null) return Optional.empty();

            return Optional.of(new TempestObsRaw(
                airTemp.asDouble(),
                feelsLike.asDouble(),
                humidity.asDouble(),
                pressure.asDouble(),
                dewPoint       != null ? dewPoint.asDouble()       : 0.0,
                windAvg        != null ? windAvg.asDouble()        : 0.0,
                windGust       != null ? windGust.asDouble()       : 0.0,
                rainAccum      != null ? rainAccum.asDouble()      : 0.0,
                lightningCount != null ? lightningCount.asInt()    : 0
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    private double toF(double celsius) {
        return celsius * 9.0 / 5.0 + 32.0;
    }

    public List<AlertEmbed> getObs() {
        List<AlertEmbed> embeds = new ArrayList<>();

        try {
            JsonNode observations = fetchObservations();
            if (observations == null) return embeds;

            for (JsonNode obs : observations) {
                JsonNode airTemp        = obs.get("air_temperature");
                JsonNode humidity       = obs.get("relative_humidity");
                JsonNode pressure       = obs.get("sea_level_pressure");
                JsonNode feelsLike      = obs.get("feels_like");
                JsonNode dewPoint       = obs.get("dew_point");
                JsonNode windAvg        = obs.get("wind_avg");
                JsonNode windGust       = obs.get("wind_gust");
                JsonNode rainAccum      = obs.get("precip_accum_local_day");
                JsonNode lightningCount = obs.get("lightning_strike_count");

                EmbedBuilder builder = new EmbedBuilder()
                        .setTitle("Tempest Metrics for: " + formattedDate)
                        .setColor(Color.CYAN)
                        .addField("Temperature",        airTemp        != null ? String.format("%.1f °F", toF(airTemp.asDouble()))                   : "N/A", true)
                        .addField("Feels Like",         feelsLike      != null ? String.format("%.1f °F", toF(feelsLike.asDouble()))                 : "N/A", true)
                        .addField("Dew Point",          dewPoint       != null ? String.format("%.1f °F", toF(dewPoint.asDouble()))                  : "N/A", true)
                        .addField("Humidity",           humidity       != null ? String.format("%.0f%%", humidity.asDouble())                        : "N/A", true)
                        .addField("Wind Avg",           windAvg        != null ? String.format("%.1f mph", windAvg.asDouble() * 2.23694)             : "N/A", true)
                        .addField("Wind Gust",          windGust       != null ? String.format("%.1f mph", windGust.asDouble() * 2.23694)            : "N/A", true)
                        .addField("Rain Today",         rainAccum      != null ? String.format("%.2f in", rainAccum.asDouble() * 0.0393701)          : "N/A", true)
                        .addField("Lightning (period)", lightningCount != null ? lightningCount.asInt() + " strikes"                                 : "N/A", true)
                        .addField("Sea Level Pressure", pressure       != null ? String.format("%.1f mb", pressure.asDouble())                       : "N/A", true);

                embeds.add(new AlertEmbed("tempest-obs", builder.build(), "", "Tempest Observation"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return embeds;
    }
}
