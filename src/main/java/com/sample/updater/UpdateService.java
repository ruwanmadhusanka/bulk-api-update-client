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
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class UpdateService {

    private static final Logger logger = Logger.getLogger(UpdateService.class.getName());
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
            logger.log(Level.INFO, "***** Starting API Bulk Update *****");
            logger.log(Level.INFO, "***** Number Of APIs : " + apis.size());

            // Load generic update rules
            List<UpdateRule> updateRules = loadUpdateRules(configLoader);
            if (updateRules.isEmpty()) {
                logger.log(Level.SEVERE, "No update rules configured. Please configure UPDATE.RULES in config.properties");
                System.exit(1);
            }

            List<String> skipList = configLoader.getListProperty("API.SKIP.LIST");
            boolean explicitMode = Boolean.parseBoolean(configLoader.getProperty("ENABLE.EXPLICIT.API.UPDATE.MODE"));
            List<String> explicitList = configLoader.getListProperty("EXPLICIT.API.UPDATE.LIST");
            int threadSleepTime = Integer.parseInt(configLoader.getProperty("API.REDEPLOY.THREAD.SLEEP.TIME"));

            // Process each API
            for (API api : apis) {
                logger.log(Level.INFO, "");
                logger.log(Level.INFO, "***** Starting Processing API with ID :" + api.getId());

                // Check if API should be skipped
                if (skipList.contains(api.getId())) {
                    logger.log(Level.INFO, "***** API : " + api.getName() + "|" + api.getContext() + "|" + api.getVersion() + " is defined in APISkipList. Hence Skipping this API");
                    logger.log(Level.INFO, "***** Finished Processing API with Id : " + api.getId());
                    continue;
                }

                // Check explicit mode
                if (explicitMode && !explicitList.contains(api.getId())) {
                    logger.log(Level.INFO, "***** Explicit mode enabled. API : " + api.getName() + "|" + api.getContext() + "|" + api.getVersion() + " is not in explicit list. Skipping.");
                    logger.log(Level.INFO, "***** Finished Processing API with Id : " + api.getId());
                    continue;
                }

                // Check API lifecycle status
                if (!"PUBLISHED".equals(api.getLifeCycleStatus())) {
                    logger.log(Level.INFO, "***** API : " + api.getName() + "|" + api.getContext() + "|" + api.getVersion() + " is not in PUBLISHED state. Current state: " + api.getLifeCycleStatus());
                    logger.log(Level.INFO, "***** Finished Processing API with Id : " + api.getId());
                    continue;
                }

                logger.log(Level.INFO, "***** API : " + api.getName() + "|" + api.getContext() + "|" + api.getVersion() + " is in PUBLISHED State. Proceeding with Update.");

                // Get full API details
                String apiDetails = restRequest.getAPIDetails(api.getId(), accessToken);
                if (apiDetails == null) {
                    logger.log(Level.SEVERE, "***** Failed to get API details for " + api.getId());
                    continue;
                }

                // Apply generic update rules
                String updatedApiDetails = applyUpdateRules(apiDetails, updateRules);

                if (updatedApiDetails == null) {
                    logger.log(Level.INFO, "***** No matching updates applied for API : " + api.getName());
                    logger.log(Level.INFO, "***** Finished Processing API with Id : " + api.getId());
                    continue;
                }

                // Update the API with the modified JSON
                String updateResponse = restRequest.updateAPI(api.getId(), updatedApiDetails, accessToken);
                if (updateResponse != null) {
                    logger.log(Level.INFO, "***** API throttling policy updated successfully.");

                    // Handle revision management
                    handleRevisionManagement(api, accessToken);

                    logger.log(Level.INFO, "***** Completed Updating API : " + api.getName() + "|" + api.getContext() + "|" + api.getVersion());
                } else {
                    logger.log(Level.SEVERE, "***** Failed to update API : " + api.getName());
                } 

                logger.log(Level.INFO, "***** Finished Processing API with Id : " + api.getId());

                // Sleep to avoid overwhelming the server
                Thread.sleep(threadSleepTime);
            }

            logger.log(Level.INFO, "***** API Bulk Update Completed Successfully *****");

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in main execution", e);
            System.exit(1);
        }
    }

    private static class UpdateRule {
        String name;
        String jsonPath;
        String oldValue;
        JsonElement newValue;
    }

    private static List<UpdateRule> loadUpdateRules(ConfigLoader configLoader) {
        List<String> ruleNames = configLoader.getListProperty("UPDATE.RULES");
        List<UpdateRule> rules = new ArrayList<>();

        for (String ruleName : ruleNames) {
            String path = configLoader.getProperty(ruleName + ".JSON.PATH");
            String oldValue = configLoader.getProperty(ruleName + ".OLD.VALUE");
            String newValueRaw = configLoader.getProperty(ruleName + ".NEW.VALUE");

            if (path.isEmpty() || newValueRaw.isEmpty()) {
                logger.log(Level.WARNING, "Skipping rule " + ruleName + " due to missing JSON.PATH or NEW.VALUE");
                continue;
            }

            UpdateRule rule = new UpdateRule();
            rule.name = ruleName;
            rule.jsonPath = path;
            rule.oldValue = oldValue;
            rule.newValue = parseValue(newValueRaw);
            rules.add(rule);
            logger.log(Level.INFO, "Loaded update rule: " + ruleName + " targeting path: " + path);
        }

        return rules;
    }

    private static JsonElement parseValue(String raw) {
        String trimmed = raw.trim();
        try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return JsonParser.parseString(trimmed);
            }
            if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
                return gson.toJsonTree(Boolean.parseBoolean(trimmed));
            }
            if (trimmed.matches("-?\\d+(\\.\\d+)?")) {
                if (trimmed.contains(".")) {
                    return gson.toJsonTree(Double.parseDouble(trimmed));
                } else {
                    return gson.toJsonTree(Long.parseLong(trimmed));
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to parse value '" + raw + "' as JSON. Falling back to string.");
        }
        return gson.toJsonTree(trimmed);
    }

    private static String applyUpdateRules(String apiDetails, List<UpdateRule> rules) {
        try {
            JsonObject apiJson = JsonParser.parseString(apiDetails).getAsJsonObject();
            AtomicBoolean hasUpdates = new AtomicBoolean(false);

            for (UpdateRule rule : rules) {
                List<String> pathSegments = new ArrayList<>(Arrays.asList(rule.jsonPath.split("\\.")));
                applyRuleRecursive(apiJson, pathSegments, rule, hasUpdates);
            }

            // Return the updated JSON string if there were updates, otherwise null
            return hasUpdates.get() ? apiJson.toString() : null;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error applying update rules", e);
            return null;
        }
    }

    private static void applyRuleRecursive(JsonElement currentElement, List<String> pathSegments, UpdateRule rule,
                                           AtomicBoolean hasUpdates) {
        if (pathSegments.isEmpty()) {
            return;
        }

        String segment = pathSegments.get(0);
        boolean isArray = segment.endsWith("[]");
        String key = isArray ? segment.substring(0, segment.length() - 2) : segment;
        List<String> remaining = pathSegments.size() > 1
                ? pathSegments.subList(1, pathSegments.size())
                : Collections.emptyList();

        if (!currentElement.isJsonObject()) {
            return;
        }

        JsonObject currentObject = currentElement.getAsJsonObject();
        if (!currentObject.has(key)) {
            return;
        }

        JsonElement child = currentObject.get(key);

        if (isArray) {
            if (child != null && child.isJsonArray()) {
                JsonArray array = child.getAsJsonArray();
                for (int i = 0; i < array.size(); i++) {
                    JsonElement arrayElement = array.get(i);

                    if (remaining.isEmpty()) {
                        // Updating array values directly (e.g., policies[])
                        if (shouldReplace(arrayElement, rule.oldValue)) {
                            array.set(i, deepCopy(rule.newValue));
                            hasUpdates.set(true);
                            logger.log(Level.INFO, "***** Updated array element in path '" + rule.jsonPath + "' using rule " + rule.name);
                        }
                    } else {
                        applyRuleRecursive(arrayElement, remaining, rule, hasUpdates);
                    }
                }
            }
        } else {
            if (remaining.isEmpty()) {
                // Final segment - perform replacement
                if (shouldReplace(child, rule.oldValue)) {
                    currentObject.add(key, deepCopy(rule.newValue));
                    hasUpdates.set(true);
                    logger.log(Level.INFO, "***** Updated path '" + rule.jsonPath + "' using rule " + rule.name);
                }
            } else {
                applyRuleRecursive(child, remaining, rule, hasUpdates);
            }
        }
    }

    private static boolean shouldReplace(JsonElement existing, String expectedOldValue) {
        if (expectedOldValue == null || expectedOldValue.isEmpty()) {
            return true;
        }
        if (existing == null || existing.isJsonNull()) {
            return false;
        }

        if (existing.isJsonPrimitive()) {
            return expectedOldValue.equals(existing.getAsString());
        }
        return expectedOldValue.equals(existing.toString());
    }

    private static JsonElement deepCopy(JsonElement element) {
        return element == null ? null : JsonParser.parseString(element.toString());
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

    private static void handleRevisionManagement(API api, String accessToken) {
        try {
            // Get revision count
            int revisionCount = restRequest.getRevisionCount(api.getId(), accessToken);
            logger.log(Level.INFO, "***** Revision Count for API : " + api.getName() + "|" + api.getContext() + "|" + api.getVersion() + " is : " + revisionCount);

            // Delete oldest revision if count is 5
            if (revisionCount >= 5) {
                logger.log(Level.INFO, "***** Revision Count for API is " + revisionCount + ". Deleting Oldest Revision.");
                String oldestRevisionId = restRequest.getOldestRevisionId(api.getId(), accessToken);
                if (oldestRevisionId != null) {
                    restRequest.deleteRevision(api.getId(), oldestRevisionId, accessToken);
                }
            }

            // Create new revision
            String newRevisionId = restRequest.createRevision(api.getId(), accessToken);
            if (newRevisionId != null) {
                logger.log(Level.INFO, "***** New Revision created with id : " + newRevisionId + " for API : " + api.getName() + "|" + api.getContext() + "|" + api.getVersion());

                // Deploy revision
                String deploymentPayload = restRequest.getDeploymentEnvironments(api.getId(), accessToken);
                logger.log(Level.INFO, "***** New Revision going to be deployed with payload : " + deploymentPayload);
                restRequest.deployRevision(api.getId(), newRevisionId, deploymentPayload, accessToken);
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error in revision management", e);
        }
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