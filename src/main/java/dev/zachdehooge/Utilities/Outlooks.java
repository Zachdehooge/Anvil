package dev.zachdehooge.Utilities;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;

public class Outlooks {
    private static final String DAYONEOUTLOOK = "https://www.spc.noaa.gov/products/outlook/day1otlk.png";
    private static final String DAYTWOOUTLOOK = "https://www.spc.noaa.gov/products/outlook/day2otlk.png";
    private static final String DAYTHREEOUTLOOK = "https://www.spc.noaa.gov/products/outlook/day3otlk.png";
    private static final String DAYFOUREIGHTOUTLOOK = "https://www.spc.noaa.gov/products/exper/day4-8/";

    public static Object getOutlook1(String DAYONEURL){
        try {
            String url = "https://www.spc.noaa.gov/products/outlook/day1otlk.png";
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(URI.create(url).toURL().openStream());
            return root;
        } catch (Exception e) {
            return false;
        }
    }
}
