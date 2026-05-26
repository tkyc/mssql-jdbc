# Direct RPC Execution for Stored Procedures — Implementation Context

## What Was Built

A new `prepareMethod=directRpc` connection property value that causes `CallableStatement` / `PreparedStatement` stored procedure calls to be sent to SQL Server as a **direct TDS RPC request** — using the actual procedure name in the packet header — rather than being routed through system stored procedure wrappers like `sp_executesql`, `sp_prepexec`, or `sp_execute`.

---

## Background: The Problem

Before this change, every parameterized execution in the driver went through a system stored procedure wrapper:

```
Client → sp_prepexec(@handle OUT, @params, @sql)   ← wraps your EXEC myProc
Client → sp_execute(@handle, @p1, @p2)             ← wraps your EXEC myProc
```

The `prepareMethod` connection property controlled which wrapper was used:
- `prepexec` (default) — `sp_prepexec` + `sp_execute`
- `prepare` — `sp_prepare` then `sp_execute`
- `none` — embed parameter values directly into SQL string (no parameterization)
- `scopeTempTablesToConnection` — `none` when temp tables detected, else `prepexec`

None of these executed stored procedures **directly**. ODBC and other SQL Server clients regularly use direct RPC (the TDS RPC packet type with the procedure name written into the packet header), which avoids the overhead of the wrapping layer.

---

## TDS Protocol Background

The TDS RPC packet format (`PKT_RPC`) supports two modes for identifying the target:

**System procedure mode (used by all pre-existing paths):**
```
writeShort(0xFFFF)             // sentinel: "use PROCID not name"
writeShort(TDS.PROCID_SP_*)    // numeric ID of the system proc
```

**Named procedure mode (new direct RPC path):**
```
writeShort(procedureName.length())  // character count of name
writeString(procedureName)          // procedure name in Unicode
```

Parameters follow immediately in both cases; the TDS structure is identical from that point on.

---

## Files Changed

### `src/main/java/com/microsoft/sqlserver/jdbc/SQLServerDriver.java`

Added one enum value to `PrepareMethod`:

```java
DIRECT_RPC("directRpc"); // direct TDS RPC for stored procedure calls (no system proc wrapper)
```

The `valueOfString` static method on `PrepareMethod` is case-insensitive and already handles unknown values by throwing `R_InvalidConnectionSetting`, so the new value is automatically validated.

No changes were needed to `SQLServerDataSource`, `SQLServerConnection`, `ISQLServerDataSource`, or `ISQLServerConnection` — the `prepareMethod` property infrastructure (getter, setter, URL parsing via `PrepareMethod.valueOfString(sPropValue).toString()`) already existed and handles any valid enum value generically.

---

### `src/main/java/com/microsoft/sqlserver/jdbc/SQLServerPreparedStatement.java`

Three additions:

#### 1. New field `isDirectRPCExecution`

```java
private final boolean isDirectRPCExecution;
```

Set in the constructor alongside the existing `isDirectSqlExecution` and `usePrepExec` finals. It is `true` only when all of:
- `prepareMethod=directRpc`
- `procedureName != null` (the SQL is a stored procedure call)
- `bReturnValueSyntax == false` (see Limitation section below)

All other `PrepareMethod` branches set it to `false`.

#### 2. New method `buildDirectRPCParams(TDSWriter)`

```java
private void buildDirectRPCParams(TDSWriter tdsWriter) throws SQLServerException {
    expectPrepStmtHandle = false;
    executedSqlDirectly = true;
    expectCursorOutParams = false;
    outParamIndexAdjustment = 0;     // ← key: no system-proc params precede user's OUT params
    resetPrepStmtHandle(false);

    tdsWriter.writeShort((short) procedureName.length());
    tdsWriter.writeString(procedureName);
    tdsWriter.writeByte((byte) 0x00); // RPC option flags 1
    tdsWriter.writeByte((byte) 0x00); // RPC option flags 2
}
```

The critical difference from all existing `build*Params` methods is `outParamIndexAdjustment = 0`. In all other paths the adjustment skips the system-proc internal OUT params (e.g. `sp_prepexec` adds 3 — `@handle`, `@params`, `@stmt`). For direct RPC there are no such internal params, so no adjustment is needed.

#### 3. Branch in `doPrepExec()`

Inserted between the `isDirectSqlExecution` early-return and the cursor/sp_prepexec decision tree:

```java
if (isDirectRPCExecution) {
    buildDirectRPCParams(tdsWriter);
    sendParamsByRPC(tdsWriter, params);
    return false;   // no prepare handle needed or produced
}
```

Returning `false` from `doPrepExec` (i.e., `needsPrepare=false`) suppresses all prepare-handle lifecycle code in `doExecutePreparedStatement`.

---

### `src/test/java/com/microsoft/sqlserver/jdbc/callablestatement/DirectRPCTest.java` (new)

Integration test class covering:

| Test | What it validates |
|---|---|
| `testInParamsReturnsResultSet` | IN-only params, result set returned correctly |
| `testReExecutionProducesCorrectResults` | Re-executing same statement with different values (no stale handle) |
| `testOutputParameter` | OUT parameter value correctly returned, `outParamIndexAdjustment=0` is correct |
| `testNoParams` | Zero-parameter procedure works |
| `testMultipleResultSets` | All result sets accessible via `getMoreResults()` |
| `testNonProcedureSqlFallsBackToExistingPath` | Callable statement still works end-to-end |
| `testDefaultConnectionNotAffected` | Default `prepareMethod` connection is unchanged |

---

## Files NOT Changed (and Why)

| File | Reason not changed |
|---|---|
| `SQLServerDataSource.java` | `getPrepareMethod()` / `setPrepareMethod()` already exist |
| `SQLServerConnection.java` | URL parsing uses `PrepareMethod.valueOfString()` generically; handles new enum value automatically |
| `ISQLServerDataSource.java` / `ISQLServerConnection.java` | Interfaces for the above; no new method added |
| `ParsedSQLMetadata.java` | `procedureName` field already existed and is already populated by `JDBCSyntaxTranslator` |
| `SQLServerCallableStatement.java` | `outParamIndexAdjustment` is set on the parent class; OUT param reading already handles `adjustment=0` correctly |
| `SQLServerResource.java` | No new exception needed — non-procedure SQL with `directRpc` silently falls back to `sp_executesql` |

---

## Key Limitation: `bReturnValueSyntax` Not Supported

`{ ? = call myProc() }` syntax (JDBC return value syntax) is explicitly excluded from direct RPC:

```java
isDirectRPCExecution = (null != procedureName) && !bReturnValueSyntax;
```

**Why:** When using system proc wrappers, the SQL sent to the server is transformed to `EXEC @p0 = myProc @p1, @p2`. SQL Server captures the `RETURN` value into `@p0` and returns it as a RETVALUE OUT parameter token — which the driver then maps to `inOutParam[0]`.

For direct RPC, the procedure's `RETURN n` statement produces a RETURNSTATUS TDS token, not a RETVALUE token. The existing driver does not map RETURNSTATUS to `inOutParam[0]`; it just stores it in `procedureRetStatToken` inside the `NextResult` handler but never routes it to the parameter array. Supporting this would require non-trivial changes to the response parsing layer.

The fallback behavior (when `bReturnValueSyntax=true` with `prepareMethod=directRpc`) is the existing `sp_executesql` path — correct and transparent to the user.

---

## Output Parameter Ordinal Mapping

SQL Server returns OUT parameter values as RETVALUE TDS tokens, each carrying a 0-indexed ordinal matching the parameter's position in the procedure's parameter declaration.

In `SQLServerCallableStatement.skipOutParameters()`:
```java
outParamIndex = outParamHandler.srv.getOrdinalOrLength();  // 0-indexed ordinal from server
outParamIndex -= outParamIndexAdjustment;                  // 0 for direct RPC
// result maps directly into inOutParam[]
```

Example: `myProc(@in INT, @out INT OUTPUT)` — @out has ordinal 1 from the server. With `outParamIndexAdjustment=0`, it maps to `inOutParam[1]`, which is the user's second parameter (1-indexed JDBC parameter 2). This is correct.

---

## How to Use

```java
// Via connection URL
String url = "jdbc:sqlserver://localhost;databaseName=mydb;prepareMethod=directRpc;...";
Connection conn = DriverManager.getConnection(url);

// Via DataSource
SQLServerDataSource ds = new SQLServerDataSource();
ds.setPrepareMethod("directRpc");
Connection conn = ds.getConnection();

// Via Connection setter (after connect)
((SQLServerConnection) conn).setPrepareMethod("directRpc");

// Then use CallableStatement as normal
try (CallableStatement cs = conn.prepareCall("{call myProc(?, ?)}")) {
    cs.setInt(1, 42);
    cs.registerOutParameter(2, Types.INTEGER);
    cs.execute();
    int result = cs.getInt(2);
}
```

Non-procedure `PreparedStatement` SQL (e.g. `SELECT ? + ?`) with `prepareMethod=directRpc` has `isDirectRPCExecution=false` (because `procedureName` is null) and falls back to the `sp_executesql` / `sp_prepexec` path transparently.
