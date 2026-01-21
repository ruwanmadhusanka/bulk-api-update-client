## APIM 3.2.0 basic_auth scanner
Scan all APIs in WSO2 API Manager 3.2.0 Publisher v1 and list/count the ones that have `basic_auth` in their `securityScheme`.

## Build
```bash
mvn clean install
```
Output JAR: `target/Update-Client-1.0-SNAPSHOT-jar-with-dependencies.jar`

## Prepare files
Place these together:
```
/WorkingDirectory
  ├── Update-Client-1.0-SNAPSHOT-jar-with-dependencies.jar
  ├── logging.properties
  ├── client-truststore.jks
  ├── config.properties
  └── app.log
```
Copy `client-truststore.jks` from `<APIM_HOME>/repository/resources/security`.

## Configure (`config.properties`)
Minimal example for APIM 3.2.0 running on `https://localhost:9443`:

````
## ===== APIM 3.2.0 basic_auth scanner configuration =====

## Trust-store (use APIM client-truststore.jks)
TRUSTSTORE.PATH = client-truststore.jks
TRUSTSTORE.PASSWORD = wso2carbon

## Paging for GET /apis
MAX.API.LIMIT = 1000

## Tenant admin / resident KM
ADMIN.USERNAME = admin
ADMIN.PASSWORD = admin
TOKEN.URL = https://localhost:9443/oauth2/token
DCR.URL = https://localhost:9443/client-registration/v0.17/register

## OAuth scopes used when getting the token
## For scan-only, apim:api_view is enough
TOKEN.SCOPES = apim:api_view

## Publisher v1 APIs endpoint (APIM 3.2.0)
PUBLISHER.REST.URL = https://localhost:9443/api/am/publisher/v1/apis

## Sleep between API detail calls (milliseconds)
API.CALL.THREAD.SLEEP.TIME = 200

## Optional: skip or explicitly include APIs by ID
## Examples:
##   API.SKIP.LIST = [a62ca2a7-a1d2-4919-9f5c-642e36d07099,352a7d6c-5bec-4964-b059-850ac6c95006]
##   EXPLICIT.API.UPDATE.LIST = [ef350818-dfb2-44a5-8021-aee494574fa1]
API.SKIP.LIST = []
ENABLE.EXPLICIT.API.UPDATE.MODE = false
EXPLICIT.API.UPDATE.LIST = []
````

## logging.properties (sample)
````
handlers = java.util.logging.FileHandler
.level = INFO
java.util.logging.FileHandler.level = ALL
java.util.logging.FileHandler.pattern = app.log
java.util.logging.FileHandler.append = true
java.util.logging.FileHandler.formatter = java.util.logging.SimpleFormatter
java.util.logging.SimpleFormatter.format = %1$tF %1$tT | %2$s | %3$s | %5$s %n
````

## Run
From the working directory:
```bash
java -Djava.util.logging.config.file=logging.properties \
     -jar Update-Client-1.0-SNAPSHOT-jar-with-dependencies.jar config.properties
```

## What the tool does
- Registers a DCR client and obtains an access token using password grant and the configured scopes.
- Calls `GET /api/am/publisher/v1/apis` (paged) to list all APIs.
- For each API, calls `GET /api/am/publisher/v1/apis/{apiId}` and inspects the `securityScheme` array.
- If `basic_auth` is present, logs the API name and increments a counter.
- At the end, logs the total number of APIs that have `basic_auth` defined.

## Notes
- Use `API.SKIP.LIST` and `ENABLE.EXPLICIT.API.UPDATE.MODE` / `EXPLICIT.API.UPDATE.LIST` if you want to limit which APIs are scanned.
- Check `app.log` for per-API logs and the final summary line.



