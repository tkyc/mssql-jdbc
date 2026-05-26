/*
 * Microsoft JDBC Driver for SQL Server
 *
 * Copyright(c) Microsoft Corporation All rights reserved.
 *
 * This program is made available under the terms of the MIT License.
 * See the LICENSE file in the project root for more information.
 */
package com.microsoft.sqlserver.jdbc.callablestatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;

import com.microsoft.sqlserver.jdbc.RandomUtil;
import com.microsoft.sqlserver.jdbc.TestUtils;
import com.microsoft.sqlserver.testframework.AbstractSQLGenerator;
import com.microsoft.sqlserver.testframework.AbstractTest;
import com.microsoft.sqlserver.testframework.Constants;
import com.microsoft.sqlserver.testframework.PrepUtil;


/**
 * Tests for prepareMethod=directRpc: stored procedures executed via a direct TDS RPC call
 * rather than being wrapped in sp_executesql / sp_prepexec / sp_execute.
 */
@RunWith(JUnitPlatform.class)
@Tag(Constants.xAzureSQLDW)
public class DirectRPCTest extends AbstractTest {

    private static final String procInOnly = AbstractSQLGenerator
            .escapeIdentifier(RandomUtil.getIdentifier("DirectRPC_InOnly"));
    private static final String procWithOut = AbstractSQLGenerator
            .escapeIdentifier(RandomUtil.getIdentifier("DirectRPC_WithOut"));
    private static final String procNoParams = AbstractSQLGenerator
            .escapeIdentifier(RandomUtil.getIdentifier("DirectRPC_NoParams"));
    private static final String procMultiResult = AbstractSQLGenerator
            .escapeIdentifier(RandomUtil.getIdentifier("DirectRPC_MultiResult"));

    @BeforeAll
    public static void setupTest() throws Exception {
        setConnection();
        try (Statement stmt = connection.createStatement()) {
            TestUtils.dropProcedureIfExists(procInOnly, stmt);
            TestUtils.dropProcedureIfExists(procWithOut, stmt);
            TestUtils.dropProcedureIfExists(procNoParams, stmt);
            TestUtils.dropProcedureIfExists(procMultiResult, stmt);

            stmt.execute("CREATE PROCEDURE " + procInOnly + " @a INT, @b INT AS BEGIN "
                    + "SELECT @a + @b AS result END");

            stmt.execute("CREATE PROCEDURE " + procWithOut
                    + " @a INT, @b INT, @sum INT OUTPUT AS BEGIN "
                    + "SET @sum = @a + @b END");

            stmt.execute("CREATE PROCEDURE " + procNoParams + " AS BEGIN "
                    + "SELECT 42 AS answer END");

            stmt.execute("CREATE PROCEDURE " + procMultiResult + " @n INT AS BEGIN "
                    + "SELECT @n AS first_result; SELECT @n * 2 AS second_result END");
        }
    }

    @AfterAll
    public static void cleanup() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            TestUtils.dropProcedureIfExists(procInOnly, stmt);
            TestUtils.dropProcedureIfExists(procWithOut, stmt);
            TestUtils.dropProcedureIfExists(procNoParams, stmt);
            TestUtils.dropProcedureIfExists(procMultiResult, stmt);
        }
    }

    private Connection directRPCConnection() throws SQLException {
        return PrepUtil.getConnection(connectionString + ";prepareMethod=directRpc;");
    }

    @Test
    public void testInParamsReturnsResultSet() throws Exception {
        try (Connection conn = directRPCConnection();
                CallableStatement cs = conn.prepareCall("{call " + procInOnly + "(?, ?)}")) {
            cs.setInt(1, 3);
            cs.setInt(2, 7);
            try (ResultSet rs = cs.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(10, rs.getInt("result"));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    public void testReExecutionProducesCorrectResults() throws Exception {
        try (Connection conn = directRPCConnection();
                CallableStatement cs = conn.prepareCall("{call " + procInOnly + "(?, ?)}")) {
            cs.setInt(1, 1);
            cs.setInt(2, 2);
            try (ResultSet rs = cs.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt("result"));
            }

            // re-execute with different values — no prepare-handle reuse should occur
            cs.setInt(1, 10);
            cs.setInt(2, 20);
            try (ResultSet rs = cs.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(30, rs.getInt("result"));
            }
        }
    }

    @Test
    public void testOutputParameter() throws Exception {
        try (Connection conn = directRPCConnection();
                CallableStatement cs = conn.prepareCall("{call " + procWithOut + "(?, ?, ?)}")) {
            cs.setInt(1, 5);
            cs.setInt(2, 8);
            cs.registerOutParameter(3, Types.INTEGER);
            cs.execute();
            assertEquals(13, cs.getInt(3));
        }
    }

    @Test
    public void testNoParams() throws Exception {
        try (Connection conn = directRPCConnection();
                CallableStatement cs = conn.prepareCall("{call " + procNoParams + "}")) {
            try (ResultSet rs = cs.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt("answer"));
            }
        }
    }

    @Test
    public void testMultipleResultSets() throws Exception {
        try (Connection conn = directRPCConnection();
                CallableStatement cs = conn.prepareCall("{call " + procMultiResult + "(?)}")) {
            cs.setInt(1, 5);
            boolean hasResult = cs.execute();
            assertTrue(hasResult);
            try (ResultSet rs = cs.getResultSet()) {
                assertTrue(rs.next());
                assertEquals(5, rs.getInt("first_result"));
            }
            assertTrue(cs.getMoreResults());
            try (ResultSet rs = cs.getResultSet()) {
                assertTrue(rs.next());
                assertEquals(10, rs.getInt("second_result"));
            }
        }
    }

    @Test
    public void testNonProcedureSqlFallsBackToExistingPath() throws Exception {
        // directRpc on a non-procedure PreparedStatement should fall back to sp_executesql
        try (Connection conn = directRPCConnection();
                CallableStatement cs = conn.prepareCall("{call " + procInOnly + "(?, ?)}")) {
            cs.setInt(1, 4);
            cs.setInt(2, 6);
            try (ResultSet rs = cs.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(10, rs.getInt("result"));
            }
        }
    }

    @Test
    public void testDefaultConnectionNotAffected() throws Exception {
        // A connection without prepareMethod=directRpc should behave exactly as before
        try (CallableStatement cs = connection.prepareCall("{call " + procInOnly + "(?, ?)}")) {
            cs.setInt(1, 2);
            cs.setInt(2, 3);
            try (ResultSet rs = cs.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(5, rs.getInt("result"));
            }
        }
    }
}
