# Logflow
Logflow is a lightweight software to globally collect and analyse logs. Storing logs is called "ingestion" and Logflow has 3 ingestion modes: HTTP, Websockets and normal TCP-Sockets. The logs are stored in a database and can be queried using SQL or our API. Logs can also be viewed in the web interface.

- [Authorization](#authorization)
- [Ingestion](#ingestion)
- [Application Arguments](#starting-the-application)

## Authorization
When the web interface is first setup, the default user and password is `admin`. This can be changed after logging in. Ingesting requires a token. This token can be generated in the web interface. The token can be passed in the header `Authorization` for HTTP and Websockets. For TCP-Sockets, the token is passed as the first line of the message.
### Local token
Logflow can solely run with a local token. This token is passed with an environment variable "LOGFLOW_LOCAL_AUTH_TOKEN". If you don't provide a token, a random one will be generated on each start and printed to the console. If you use a random token, be aware that it will change on each restart.

## Ingestion
Please note that HTTP and Websockets use the same web server, so any SSL changes will affect both. TCP-Sockets is a separate server and can be configured separately. Also, TCP-Sockets do not support PEM-certificates - only Java KeyStores.
### HTTP
The HTTP ingestion is the easiest to use. It is a simple POST request to the endpoint `/log`. The header `Authorization` must be set to the token. The body must equal the following JSON (example):
```json
{
  "timestamp": 1691572414,
  "level": "INFO",
  "source": "node-1",
  "content": "Node 1 is running",
  "context": "cluster-5",
  "tags": [
    "node",
    "status",
    "running"
  ],
  "metadata": "healthcheck:ok"
}
```
The fields `timestamp`, `level`, `source` and `context` are required. The other fields are optional.

### Websockets
The Websockets ingestion is similar to the HTTP ingestion. The only difference is that the data is sent as a JSON string message. The header `Authorization` must be set to the token. The message must equal the following JSON (example):
```json
{
  "timestamp": 1691572414,
  "level": "INFO",
  ...
}
```
The fields `timestamp`, `level`, `source` and `context` are required. The other fields are optional.
### TCP-Sockets
The TCP-Sockets ingestion is the most flexible. It is a simple TCP connection, it supports SSL and plain (see [Application Arguments](#applicationArgs)). The first line of the message must be the token. The rest of the message must equal the following JSON (example):
```json
{
  "timestamp": 1691572414,
  "level": "INFO",
  ...
}
```
---
# Starting the application

## Java Arguments
| Argument              | Description                                |
|-----------------------|--------------------------------------------|
| `-noWebServer`        | Disable the web server entirely            |
| `-noWsIngest`         | Do not support log ingest over web sockets |
| `-noHttpIngest`       | Do not support log ingest over HTTP        |
| `-noSocketIngest`     | Do not start socket server for log ingest  |
| `-webUseSSL`          | Use SSL for all web services               |
| `-socketUseSSL`       | Use SSL for socket server                  |

## Environment Variables
| Variable                   | Description                              |
|----------------------------|------------------------------------------|
| `LOGFLOW_LOCAL_AUTH_TOKEN` | Local token for authorization (optional) |
| `LOGFLOW_HIKARI_JDBC_URL`  | Database URL (MySQL driver present)      |
| `LOGFLOW_HIKARI_USERNAME`  | Database username                        |
| `LOGFLOW_HIKARI_PASSWORD`  | Database password                        |
| `LOGFLOW_HIKARI_POOL_SIZE` | Database connection pool size (optional) |
| `LOGFLOW_WEB_PORT`         | Web server port (optional)               |
| `LOGFLOW_SOCKET_PORT`      | Socket server port (optional)            |

## JVM Arguments
| Argument                           | Description                                |
|------------------------------------|--------------------------------------------|
| `-Djavax.net.ssl.keyStore`         | Path to Java KeyStore for SSL |
| `-Djavax.net.ssl.keyStorePassword` | Password for Java KeyStore |
| `-Dlogflow.ssl.pem.cert`           | Path to PEM certificate for SSL |
| `-Dlogflow.ssl.pem.privateKey`     | Path to PEM private key for SSL |