package dev.zachdehooge.Utilities;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the SPC Convective Outlook images. Days 1-3 are the categorical outlook PNGs;
 * days 4-8 are the experimental probabilistic outlook GIFs.
 */
public class Outlooks {

    public enum Day {
        ONE(1,   "outlookdayone",   "https://www.spc.noaa.gov/products/outlook/day1otlk.png"),
        TWO(2,   "outlookdaytwo",   "https://www.spc.noaa.gov/products/outlook/day2otlk.png"),
        THREE(3, "outlookdaythree", "https://www.spc.noaa.gov/products/outlook/day3otlk.png"),
        FOUR(4,  "outlookdayfour",  "https://www.spc.noaa.gov/products/exper/day4-8/day4prob.gif"),
        FIVE(5,  "outlookdayfive",  "https://www.spc.noaa.gov/products/exper/day4-8/day5prob.gif"),
        SIX(6,   "outlookdaysix",   "https://www.spc.noaa.gov/products/exper/day4-8/day6prob.gif"),
        SEVEN(7, "outlookdayseven", "https://www.spc.noaa.gov/products/exper/day4-8/day7prob.gif"),
        EIGHT(8, "outlookdayeight", "https://www.spc.noaa.gov/products/exper/day4-8/day8prob.gif");

        private final int number;
        private final String command;
        private final String url;

        Day(int number, String command, String url) {
            this.number = number;
            this.command = command;
            this.url = url;
        }

        public int number() {
            return number;
        }

        public String command() {
            return command;
        }

        public String url() {
            return url;
        }

        public String description() {
            return "Get the SPC Day " + number + " Convective Outlook";
        }

        /** The filename to attach the image under in Discord (matches the source extension). */
        public String fileName() {
            String ext = url.substring(url.lastIndexOf('.') + 1);
            return "day" + number + "otlk." + ext;
        }

        public static Day fromCommand(String command) {
            for (Day day : values()) {
                if (day.command.equals(command)) return day;
            }
            return null;
        }
    }

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** Matches the WMO header line of an SPC outlook text product, e.g. "ACUS01 KWNS 081615". */
    private static final Pattern ISSUANCE = Pattern.compile("ACUS0\\d\\s+KWNS\\s+(\\d{6})");

    private Outlooks() {}

    /** Days 1-3 publish a categorical text product whose issuance time we can watch for updates. */
    public static boolean hasIssuanceFeed(Day day) {
        return day.number() <= 3;
    }

    /**
     * Returns the SPC issuance token (DDHHMM) for the given day's current outlook text product,
     * or {@code null} if the day has no text feed or the product can't be read right now.
     */
    public static String fetchIssuanceId(Day day) {
        if (!hasIssuanceFeed(day)) return null;

        String textUrl = "https://www.spc.noaa.gov/products/outlook/day" + day.number() + "otlk.txt";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(textUrl))
                .GET()
                .build();

        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            Matcher m = ISSUANCE.matcher(response.body());
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Converts an SPC issuance token ({@code DDHHMM}, always UTC) into an {@link Instant}.
     * The token carries no month/year, so the current UTC month is assumed; if that lands more
     * than two days in the future the token is treated as belonging to the previous month.
     * Returns {@code null} if the token is missing or malformed.
     */
    public static Instant issuanceInstant(String token) {
        if (token == null || token.length() != 6) return null;
        try {
            int dd = Integer.parseInt(token.substring(0, 2));
            int hh = Integer.parseInt(token.substring(2, 4));
            int mm = Integer.parseInt(token.substring(4, 6));

            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            ZonedDateTime candidate = now.withDayOfMonth(1)
                    .withHour(hh).withMinute(mm).withSecond(0).withNano(0)
                    .withDayOfMonth(dd);
            if (candidate.isAfter(now.plusDays(2))) {
                candidate = candidate.minusMonths(1);
            }
            return candidate.toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Downloads the outlook image for the given day. */
    public static byte[] fetch(Day day) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(day.url()))
                .GET()
                .build();

        HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new IOException("SPC returned HTTP " + response.statusCode() + " for " + day.url());
        }

        return response.body();
    }
}
