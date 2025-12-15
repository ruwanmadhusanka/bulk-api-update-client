# Bulk API Updater (WSO2 APIM 4.2.0)
Bulk, config-driven updates for any API property via the Publisher v4 API. Supports multiple rules (e.g., throttling policies, descriptions, CORS flags) in one run.

## Recommendations
- Back up APIM_DB and SHARED_DB before running.
- Use a non-prod environment to test your rules first.

## Build
```bash
mvn clean install
```
Output JAR: `target/Update-Client-1.0-SNAPSHOT-jar-with-dependencies.jar`

## Prepare files
Place these together (plus a `logs/` folder):
```
/WorkingDirectory
  ├── Update-Client-1.0-SNAPSHOT-jar-with-dependencies.jar
  ├── logging.properties
  ├── client-truststore.jks
  ├── config.properties
  └── logs/
      └── app.log
```
Copy `client-truststore.jks` from `<APIM_HOME>/repository/resources/security`.

## Configure (`config.properties`)
- `TRUSTSTORE.PATH`, `TRUSTSTORE.PASSWORD`: TLS to APIM.
- `MAX.API.LIMIT`: Page size for listing APIs.
- `RESIDENTKM.USERNAME`, `RESIDENTKM.PASSWORD`: Tenant admin creds.
- `RESIDENTKM.TOKEN.URL`, `RESIDENTKM.DCR.URL`: Token + DCR endpoints.
- `PUBLISHER.REST.URL`: Publisher REST base (`.../v4/apis`).
- `API.REDEPLOY.THREAD.SLEEP.TIME`: Sleep between API updates (ms).
- `API.SKIP.LIST`: `[id1,id2]` to ignore.
- `ENABLE.EXPLICIT.API.UPDATE.MODE`: `true` limits to `EXPLICIT.API.UPDATE.LIST`.
- `EXPLICIT.API.UPDATE.LIST`: `[id1,id2]` processed only when explicit mode on.
- `UPDATE.RULES`: comma list of rule names (e.g., `RULE1,RULE2`).
- `<RULE>.JSON.PATH`: dotted path in API JSON; use `[]` to iterate arrays.
- `<RULE>.OLD.VALUE`: expected current value; leave empty to always overwrite.
- `<RULE>.NEW.VALUE`: replacement (string, number, boolean, JSON object/array).

### Path examples
- `operations[].throttlingPolicy` – each operation’s throttling policy.
- `description` – top-level description.
- `endpointConfig.production_endpoints.url` – production URL.
- `policies[]` – array elements.
- `authorizationHeader` – simple field (no array).

### Sample config (throttling default)
```
TRUSTSTORE.PATH = client-truststore.jks
TRUSTSTORE.PASSWORD = wso2carbon
MAX.API.LIMIT = 1000
RESIDENTKM.USERNAME = admin
RESIDENTKM.PASSWORD = admin
RESIDENTKM.TOKEN.URL = https://localhost:9443/oauth2/token
RESIDENTKM.DCR.URL = https://localhost:9443/client-registration/v0.17/register
PUBLISHER.REST.URL = https://localhost:9443/api/am/publisher/v4/apis
API.REDEPLOY.THREAD.SLEEP.TIME = 1000
API.SKIP.LIST = []
ENABLE.EXPLICIT.API.UPDATE.MODE = false
EXPLICIT.API.UPDATE.LIST = []

UPDATE.RULES = RULE1
RULE1.JSON.PATH = operations[].throttlingPolicy
RULE1.OLD.VALUE = Unlimited
RULE1.NEW.VALUE = testPolicy

# Example: update authorization header name everywhere
UPDATE.RULES = RULE1,RULE2
RULE2.JSON.PATH = authorizationHeader
RULE2.OLD.VALUE = Authorization
RULE2.NEW.VALUE = X-Custom-Auth
```

## logging.properties (sample)
```
handlers = java.util.logging.FileHandler
.level = INFO
java.util.logging.FileHandler.level = ALL
java.util.logging.FileHandler.pattern = logs/app.log
java.util.logging.FileHandler.append = true
java.util.logging.FileHandler.formatter = java.util.logging.SimpleFormatter
java.util.logging.SimpleFormatter.format = %1$tF %1$tT | %2$s | %3$s | %5$s %n
```

## Run
From the working directory:
```bash
java -Djava.util.logging.config.file=logging.properties \
     -jar Update-Client-1.0-SNAPSHOT-jar-with-dependencies.jar config.properties
```

## What the tool does
- Registers a DCR client, obtains a token, lists APIs, fetches each API, applies all rules, updates, creates/deploys a revision, and sleeps between calls.
- Skips non-PUBLISHED APIs, entries in `API.SKIP.LIST`, and (when enabled) APIs not in `EXPLICIT.API.UPDATE.LIST`.

## Tips
- Use multiple rules to update many fields in one pass.
- Leave `<RULE>.OLD.VALUE` blank to force overwrite regardless of current value.
- For JSON objects/arrays in `NEW.VALUE`, provide valid JSON (e.g., `{"corsConfigurationEnabled":true}`).
- Monitor `logs/app.log` for progress and applied rules.



