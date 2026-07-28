package dev.zachdehooge.Utilities;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.OffsetDateTime;

/**
 * Builds IEM (Iowa Environmental Mesonet) radar imagery URLs for a given NWS VTEC-identified warning:
 * a radar composite centered on the storm at issuance time, and, if the storm has been updated more
 * than once (i.e. more than one VTEC product/pane exists), a Storm Based Warning history strip.
 */
public class RadarSnippet {

    private static final String RADMAP_URL = "https://mesonet.agron.iastate.edu/GIS/radmap.php";
    private static final String SBW_HISTORY_URL = "https://mesonet.agron.iastate.edu/GIS/sbw-history.php";
    private static final String VTEC_EVENT_URL = "https://mesonet.agron.iastate.edu/json/vtec_event.py";

    public record RadarImages(String compositeUrl, String historyUrl) {}

    private record Vtec(String wfo, String phenomena, String significance, int etn) {}

    /**
     * @param vtecRaw raw VTEC string from an alert's parameters, e.g. "/O.NEW.KRNK.SV.W.0132.260728T1451Z-260728T1545Z/"
     * @param sentRaw the alert's "sent" timestamp, used to resolve the VTEC event year
     * @return radar image URLs, or null if the VTEC string couldn't be parsed
     */
    public static RadarImages getRadarImages(String vtecRaw, String sentRaw) {
        Vtec vtec = parseVtec(vtecRaw);
        if (vtec == null) return null;

        int year;
        try {
            year = OffsetDateTime.parse(sentRaw).getYear();
        } catch (Exception e) {
            return null;
        }

        String vtecId = year + "." + vtec.wfo() + "." + vtec.phenomena() + "." + vtec.significance()
                + "." + String.format("%04d", vtec.etn());

        String compositeUrl = RADMAP_URL + "?layers[]=nexrad&layers[]=sbw&layers[]=sbwh&layers[]=uscounties&vtec=" + vtecId;
        String historyUrl = hasMultiplePanes(vtec, year) ? SBW_HISTORY_URL + "?vtec=" + vtecId : null;

        return new RadarImages(compositeUrl, historyUrl);
    }

    private static Vtec parseVtec(String vtecRaw) {
        if (vtecRaw == null || vtecRaw.isBlank()) return null;
        String[] parts = vtecRaw.replace("/", "").split("\\.");
        if (parts.length < 6) return null;
        try {
            return new Vtec(parts[2], parts[3], parts[4], Integer.parseInt(parts[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean hasMultiplePanes(Vtec vtec, int year) {
        try {
            String url = VTEC_EVENT_URL + "?wfo=" + vtec.wfo() + "&year=" + year
                    + "&phenomena=" + vtec.phenomena() + "&significance=" + vtec.significance()
                    + "&etn=" + vtec.etn();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(URI.create(url).toURL().openStream());
            JsonNode svs = root.get("svs");
            return svs != null && svs.isArray() && !svs.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
