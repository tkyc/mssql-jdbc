# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Structure

```
mssql-jdbc/
├── src/
│   ├── main/java/com/microsoft/sqlserver/jdbc/    # 151 driver source files (flat package)
│   │   ├── dataclassification/                    # Column sensitivity label types
│   │   ├── dns/                                   # DNS resolution utilities
│   │   ├── osgi/                                  # OSGi bundle activator
│   │   └── spatialdatatypes/                      # Geography / Geometry spatial types
│   ├── test/java/com/microsoft/sqlserver/
│   │   ├── jdbc/                                  # Feature-area test packages
│   │   │   ├── AlwaysEncrypted/                   # Column-encryption tests
│   │   │   ├── bulkCopy/                          # Bulk insert tests
│   │   │   ├── bvt/                               # Build-verification smoke tests
│   │   │   ├── connection/ preparedStatement/      # Core JDBC feature tests
│   │   │   ├── fedauth/ kerberos/ ssl/             # Authentication & transport tests
│   │   │   ├── statemachinetest/                  # Model-based stateful interaction tests
│   │   │   └── unit/                              # Unit tests (some SQL-Server-free)
│   │   └── testframework/                         # AbstractTest, TestUtils, RandomUtil
│   └── samples/                                   # Runnable examples (adaptive, AE, AAD, etc.)
├── .github/
│   ├── workflows/                                 # CI pipeline definitions
│   ├── instructions/                              # Architecture, patterns, glossary docs for AI
│   └── prompts/                                   # Copilot prompt library
├── pom.xml                                        # Maven build (primary; use this)
├── build.gradle                                   # Gradle build (alternative)
└── mssql-jdbc_formatter.xml                       # Eclipse code formatter — apply to changed lines only
```

## Build

The project uses Maven as its primary build tool with multiple JRE profiles. Always specify a profile explicitly.

```bash
# Build and install (most common — targets Java 11+, JDBC 4.3)
mvn install -Pjre11

# Other supported profiles: jre8 (JDBC 4.2), jre17, jre21, jre25, jre26
mvn install -Pjre8

# Gradle alternative
gradle build -PbuildProfile=jre11
```

Output JAR lands in `target/` (Maven) or `build/libs/` (Gradle).

## Tests

All integration tests require a live SQL Server instance. Set the connection string before running:

```bash
# Windows
$env:mssql_jdbc_test_connection_properties = "jdbc:sqlserver://localhost;databaseName=master;user=sa;password=..."

# Run all tests
mvn test -Pjre11

# Run a single test class
mvn test -Pjre11 -Dtest=SQLServerConnectionTest

# Run a single test method
mvn test -Pjre11 -Dtest=SQLServerConnectionTest#testLoginTimeout
```

Extend `AbstractTest` (`src/test/java/com/microsoft/sqlserver/testframework/AbstractTest.java`) for any test that needs a database connection. Do not hardcode connection strings.

### Test tags (JUnit 5 `@Tag`)

Tests are filtered by group. The default excluded groups are:

```
xSQLv12, xSQLv15, NTLM, MSI, reqExternalSetup, clientCertAuth, fedAuth, kerberos, vectorTest, JSONTest, vectorFloat16Test
```

Tag your tests correctly so CI does not skip or fail unexpectedly:

| Tag | Meaning |
|-----|---------|
| `xSQLv12` | Requires SQL Server 2016+ (excludes 2008 R2–2014) |
| `xSQLv15` | Requires SQL Server 2022+ (excludes 2019) |
| `reqExternalSetup` | Needs manual infrastructure beyond SQL Server |
| `fedAuth` | Azure AD / federated auth required |
| `kerberos` | Kerberos environment required |
| `vectorTest` | Vector data type support required |

## Code Conventions

**Formatter:** All changed code must be formatted with the Eclipse formatter config at `mssql-jdbc_formatter.xml`. Apply it only to lines you changed, not entire files.

**Copyright header:** Every Java file must start with:
```java
/*
 * Microsoft JDBC Driver for SQL Server
 *
 * Copyright(c) Microsoft Corporation All rights reserved.
 *
 * This program is made available under the terms of the MIT License.
 * See the LICENSE file in the project root for more information.
 */
```

**Null checks:** Use Yoda-style comparisons — `null == obj`, not `obj == null`.

**Logging:** Always guard log statements with a level check. Never log sensitive data.
```java
if (logger.isLoggable(Level.FINER)) {
    logger.finer(toString() + " methodName: param=" + safeParam);
}
// NEVER: logger.fine("password=" + password);
```

**Exceptions:** Use the driver's factory method — never throw raw `SQLException`:
```java
SQLServerException.makeFromDriverError(
    con,                                                     // SQLServerConnection or null
    this,                                                    // source object
    SQLServerException.getErrString("R_yourErrorKey"),       // from SQLServerResource.java
    null,                                                    // SQL state (null = default)
    false
);
```

Add new error messages to `SQLServerResource.java` as `{"R_yourKey", "message text with {0} placeholder."}`.

**Adding a connection property:** Three-step pattern:
1. Add to `SQLServerDriverBooleanProperty` (or `SQLServerDriverStringProperty` / `SQLServerDriverIntProperty`) enum
2. Add getter/setter to `SQLServerDataSource`
3. Read via `activeConnectionProperties.getBooleanProperty(...)` inside `SQLServerConnection`

**Cross-profile compatibility:** Code must compile under jre8 through jre26. Do not use APIs unavailable in Java 8 without a profile-guarded block.

## Architecture

### Architecture Summary

**Tech stack:** Pure Java (JDBC 4.2/4.3), Maven multi-profile build (`jre8`–`jre26`), JUnit 5 + Mockito. Runtime dependencies: Azure Identity (MSAL4J) for Azure AD auth, Bouncy Castle for cryptography, ANTLR for SQL parsing, GSON for JSON.

**Design patterns in use:**
- *Factory* — `SQLServerDriver` creates connections; `SQLServerEncryptionAlgorithmFactoryList` manages Always Encrypted algorithms
- *Strategy* — `ISQLServerEnclaveProvider` and `SQLServerColumnEncryptionKeyStoreProvider` are pluggable
- *Singleton* — `FailOverMapSingleton`, `SQLServerSymmetricKeyCache`, `ParameterMetaDataCache`
- *Template Method* — `SQLServerSpatialDatatype` base for `Geography`/`Geometry`
- *Resource Bundle* — `SQLServerResource` (extends `ListResourceBundle`) owns all error strings

**Main data flow:** `SQLServerDriver` parses the connection URL and delegates to `SQLServerConnection`, which opens a TCP socket via `TDSChannel` (inside `IOBuffer.java`), negotiates SSL, and exchanges a TDS LOGIN7 packet. Statements produce `TDSCommand` objects written by `TDSWriter` and read back by `TDSReader`. The stateless `tdsparser` dispatches tokens (COLMETADATA → ROW → DONE) to populate a `SQLServerResultSet`. All Java↔SQL type conversions route through the stateless `DDC` utility.

### Layer overview

```
Application
    │
    ▼
JDBC API Layer        SQLServerDriver → SQLServerConnection
                      SQLServerStatement / PreparedStatement / CallableStatement
                      SQLServerResultSet / ResultSetMetaData
    │
    ▼
TDS Protocol Layer    IOBuffer.java: TDSChannel (socket+SSL) / TDSWriter / TDSReader
                      tdsparser.java: stateless token dispatcher
    │
    ▼
SQL Server / Azure SQL
```

### Typical query execution path

```
DriverManager.getConnection(url)
└─ SQLServerDriver.connect()
     └─ parseAndMergeProperties() → SQLServerConnection.connect()
          ├─ TDSChannel.open()          TCP socket + SSL handshake
          ├─ TDSWriter.sendLogin7()     LOGIN7 TDS packet
          └─ tdsparser.parse()          LOGINACK token → connection ready

connection.prepareStatement(sql)
└─ SQLServerPreparedStatement
     └─ CityHash128Key(sql)            Hash used for handle cache lookup

pstmt.setInt(1, 42) → Parameter → dtv (data type value container)

pstmt.executeQuery()
└─ connection.executeCommand(TDSCommand)
     ├─ TDSWriter → sp_prepexec / sp_execute RPC packet on the wire
     └─ TDSReader → tdsparser.parse()
          ├─ COLMETADATA token → column schema
          ├─ ROW tokens       → adaptive buffer
          └─ DONE token       → SQLServerResultSet returned

rs.getString("col") → Column.getValue() → DDC (type conversion) → Java value
```

### Key class map

| Class / File | Role |
|---|---|
| `SQLServerDriver` | Entry point. Thin factory — parses URL, delegates to `SQLServerConnection`. |
| `SQLServerConnection` | Core connection object. Owns `TDSChannel`, prepared-statement handle cache, auth state. **God class — 12,000+ LOC; add here sparingly.** |
| `IOBuffer.java` | Contains `TDSChannel`, `TDSWriter`, `TDSReader` as inner classes (~9,800 LOC total). Isolates all wire-level TDS I/O. |
| `tdsparser.java` | Stateless TDS token dispatcher. Called by `TDSReader`. |
| `SQLServerStatement` | Base statement. `executeStatement()` → `connection.executeCommand()`. |
| `SQLServerPreparedStatement` | Adds prepared-handle caching (`CityHash128Key` → `PreparedStatementHandle`). Supports `sp_prepexec` and `sp_prepare` paths. |
| `SQLServerResultSet` | 350 JDBC accessor methods + cursor + adaptive buffering. |
| `DDC` | Stateless type-conversion utility. All Java↔SQL type conversions go here. |
| `dtv.java` | "Data Type Value" — type-agnostic container for parameter values. Central to parameter binding. |
| `SQLServerResource` | Resource bundle for all error strings. Keys are `R_`-prefixed. |
| `SQLServerException` | Driver exception. Always use `makeFromDriverError()`. |
| `SQLServerBulkCopy` | High-performance bulk insert. Builder pattern + `AutoCloseable`. |
| `ConfigurableRetryLogic` | Rule-based retry framework for transient errors. |

### Sub-packages

- `dataclassification/` — sensitivity labels on result sets
- `dns/` — DNS resolution
- `osgi/` — OSGi bundle support
- `spatialdatatypes/` — `Geography` / `Geometry` types

## Known Structural Quirks

These are pre-existing architectural decisions. Do not work around them with hacks; understand them so changes stay coherent.

**`SQLServerConnection` is a God class.** It handles TCP, SSL, five auth mechanisms, prepared-statement caching, column encryption, XA, idle resiliency, and retry logic in a single ~12,400-line class. When adding features, minimize what you add here — prefer delegating to existing subsystem classes.

**`IOBuffer.java` contains three conceptual classes.** `TDSChannel`, `TDSWriter`, and `TDSReader` are large inner classes (3,000–4,000 lines each) inside a single file. When navigating TDS I/O code, search within this one file rather than looking for separate files.

**`dtv.java` and `tdsparser.java` are lowercase.** These violate Java's class-file naming convention. The actual class names are `DTV` and `TDSParser`. Use the full class name when searching; do not create new lowercase-named files.

**`TDSChannel` and `TDSReader` implement `Serializable` but cannot survive a serialization round-trip.** Both hold `transient` `Socket` / `InputStream` fields that cannot be reconstructed after deserialization. Do not add any code path that attempts to serialize these objects.

**Timeout properties use inconsistent units — this is a public API and cannot be changed:**

| Property | Unit |
|----------|------|
| `loginTimeout` | seconds |
| `queryTimeout` | seconds |
| `cancelQueryTimeout` | seconds |
| `socketTimeout` | **milliseconds** |
| `lockTimeout` | **milliseconds** |

Setting `socketTimeout=30` means 30 ms, not 30 seconds.

**`accessToken`, `accessTokenCallback`, and `gsscredential` cannot be set in the connection URL.** They must be passed via `java.util.Properties`. Setting them in the URL string silently has no effect.

**Statement pooling requires two properties.** Setting only `statementPoolingCacheSize` is not enough — pooling remains disabled unless `disableStatementPooling=false` is also set.
