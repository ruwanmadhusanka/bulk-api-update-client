package com.sample.updater;

import com.sample.bean.API;
import com.sample.utilities.ConfigLoader;
import com.sample.utilities.RestRequest;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BasicAuthScanner {

    private static final Logger logger = Logger.getLogger(BasicAuthScanner.class.getName());
    private static ConfigLoader configLoader;
    private static RestRequest restRequest;
    private static Gson gson = new Gson();

    public static void main(String[] args) {
        try {
            if (args.length != 1) {
                logger.log(Level.SEVERE, "Usage: java -jar UpdateClient.jar <config.properties>");
                System.exit(1);
            }

            // Load configuration
            configLoader = new ConfigLoader(args[0]);

            // Initialize SSL context
            initializeSSLContext();

            // Initialize REST request handler
            restRequest = new RestRequest(configLoader);

            // Get access token
            String accessToken = restRequest.getAccessToken();
            if (accessToken == null) {
                logger.log(Level.SEVERE, "Failed to obtain access token");
                System.exit(1);
            }

            // Get all APIs
            List<API> apis = getAllAPIs(accessToken);
            logger.log(Level.INFO, "***** Starting API basic_auth Scan (APIM 3.2.0 Publisher v1) *****");
            logger.log(Level.INFO, "***** Number Of APIs Found : " + apis.size());

            List<String> skipList = configLoader.getListProperty("API.SKIP.LIST");
            boolean explicitMode = Boolean.parseBoolean(configLoader.getProperty("ENABLE.EXPLICIT.API.UPDATE.MODE"));
            List<String> explicitList = configLoader.getListProperty("EXPLICIT.API.UPDATE.LIST");
            int threadSleepTime = Integer.parseInt(configLoader.getProperty("API.CALL.THREAD.SLEEP.TIME"));

            int basicAuthCount = 0;

            // Process each API
            for (API api : apis) {
                logger.log(Level.FINE, "");
                logger.log(Level.FINE, "***** Starting Processing API with ID :" + api.getId());

                // Check if API should be skipped
                if (skipList.contains(api.getId())) {
                    logger.log(Level.INFO, "***** API : " + api.getName() + "|" + api.getContext() + "|" + api.getVersion() + " is defined in APISkipList. Hence Skipping this API");
                    logger.log(Level.FINE, "***** Finished Processing API with Id : " + api.getId());
                    continue;
                }

                // Check explicit mode
                if (explicitMode && !explicitList.contains(api.getId())) {
                    logger.log(Level.INFO, "***** Explicit mode enabled. API : " + api.getName() + "|" + api.getContext() + "|" + api.getVersion() + " is not in explicit list. Skipping.");
                    logger.log(Level.FINE, "***** Finished Processing API with Id : " + api.getId());
                    continue;
                }

                // Get full API details
                String apiDetails = restRequest.getAPIDetails(api.getId(), accessToken);
                if (apiDetails == null) {
                    logger.log(Level.SEVERE, "***** Failed to get API details for " + api.getId());
                    continue;
                }

                try {
                    JsonObject apiJson = JsonParser.parseString(apiDetails).getAsJsonObject();
                    String apiName = apiJson.has("name") ? apiJson.get("name").getAsString() : api.getName();
                    JsonArray securitySchemes = apiJson.has("securityScheme") && apiJson.get("securityScheme").isJsonArray()
                            ? apiJson.getAsJsonArray("securityScheme")
                            : null;

                    if (securitySchemes != null && containsString(securitySchemes, "basic_auth")) {
                        basicAuthCount++;
                        logger.log(Level.INFO, "***** basic_auth FOUND | API: " + apiName + " | ID: " + api.getId());
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "***** Failed to parse API details JSON for API ID: " + api.getId(), e);
                }

                logger.log(Level.FINE, "***** Finished Processing API with Id : " + api.getId());

                // Sleep to avoid overwhelming the server
                Thread.sleep(threadSleepTime);
            }

            logger.log(Level.INFO, "");
            logger.log(Level.INFO, "***** basic_auth Scan Completed *****");
            logger.log(Level.INFO, "***** APIs with basic_auth : " + basicAuthCount + " / " + apis.size());

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in main execution", e);
            System.exit(1);
        }
    }

    private static boolean containsString(JsonArray array, String expected) {
        if (array == null || expected == null) {
            return false;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonElement el = array.get(i);
            if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                if (expected.equals(el.getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<API> getAllAPIs(String accessToken) {
        List<API> allAPIs = new ArrayList<>();
        int limit = Integer.parseInt(configLoader.getProperty("MAX.API.LIMIT"));
        int offset = 0;

        try {
            while (true) {
                String response = restRequest.getAPIs(limit, offset, accessToken);
                if (response == null) {
                    break;
                }

                JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
                JsonArray apiList = jsonResponse.getAsJsonArray("list");

                if (apiList == null || apiList.size() == 0) {
                    break;
                }

                for (int i = 0; i < apiList.size(); i++) {
                    JsonObject apiJson = apiList.get(i).getAsJsonObject();
                    API api = gson.fromJson(apiJson, API.class);
                    allAPIs.add(api);
                }

                // Check pagination
                JsonObject pagination = jsonResponse.getAsJsonObject("pagination");
                if (pagination != null) {
                    int total = pagination.get("total").getAsInt();
                    if (offset + limit >= total) {
                        break;
                    }
                    offset += limit;
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error fetching APIs", e);
        }

        return allAPIs;
    }

    private static void initializeSSLContext() {
        try {
            String truststorePath = configLoader.getProperty("TRUSTSTORE.PATH");
            String truststorePassword = configLoader.getProperty("TRUSTSTORE.PASSWORD");

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            try (FileInputStream trustStoreStream = new FileInputStream(truststorePath)) {
                trustStore.load(trustStoreStream, truststorePassword.toCharArray());
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);

            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to initialize SSL context", e);
            System.exit(1);
        }
    }
}
