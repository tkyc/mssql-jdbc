package com.microsoft.sqlserver.jdbc;

import org.junit.Assert;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Types;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;


public class Playground {

    public static void main(String[] args) throws Exception {
        //mainTest();
        callEscapeDefaultValueTest();
        //annotatedArgTest();
        //execTest();
        //mainTest2();
        //test0();
        //test1();
    }

    public static void callEscapeDefaultValueTest() throws Exception {
        String jdbc = "jdbc:sqlserver://localhost:1433;user=sa;password=;encrypt=false;trustServerCertificate=true;";
        String call0 = "{call [CallableStatementTest_conditionalSproc_jdbc_v-chowterry_'13ea7131-cba8-4e84-a827-914d01ed57b2] (?, ?, 1)}";
        String call1 = "{call [CallableStatementTest_conditionalSproc_jdbc_v-chowterry_'13ea7131-cba8-4e84-a827-914d01ed57b2] (?, ?, 2)}";

        try (Connection conn = DriverManager.getConnection(jdbc);CallableStatement cstmt = conn.prepareCall(call0)) {
            cstmt.setInt(1, 1);
            cstmt.setInt(2, 2);
            cstmt.execute();
            ResultSet rs = cstmt.getResultSet();
            rs.next();
            fail(TestResource.getResource("R_expectedFailPassed"));

        } catch (Exception e) {
            String msg = e.getMessage();
            assertTrue(TestResource
                    .getResource("R_nullPointerExceptionFromResultSet").equalsIgnoreCase(msg)
                    || msg == null);
        }

        try (Connection conn = DriverManager.getConnection(jdbc);CallableStatement cstmt = conn.prepareCall(call1)) {
            cstmt.setInt(1, 1);
            cstmt.setInt(2, 2);
            cstmt.execute();
            ResultSet rs = cstmt.getResultSet();
            rs.next();

            assertEquals(Integer.toString(5), rs.getString(1));
        }
    }

    public static void test1()  throws Exception {
        String jtds = "jdbc:sqlserver://localhost:1433;user=sa;password=;encrypt=false;trustServerCertificate=true;";
        //String storedProcCall = "{call doSomethingSp(?,?,?,?,?,?)}";
        String storedProcCall = "{call doSomethingSp(@Arg1 = ?, @Arg4 = ?, @Arg2 = ?, @Arg3 = ?, @Arg5 = ?, @Arg6 = ?)}";
        String ARG1 = "@Arg1";
        String ARG4 = "@Arg4";
        String ARG2 = "@Arg2";
        String ARG3 = "@Arg3";
        String ARG5 = "@Arg5";
        String ARG6 = "@Arg6";

        //try (Connection conn = DriverManager.getConnection(jtds); CallableStatement cs = conn.prepareCall(storedProcCall)) {
        //    cs.setString(1, "Test");
        //    cs.registerOutParameter(2, Types.VARCHAR);
        //    cs.registerOutParameter(3, Types.BIT);
        //    cs.setString(4, "Test2");
        //    cs.registerOutParameter(5, Types.VARCHAR);
        //    cs.registerOutParameter(6, Types.VARCHAR);

        //    cs.execute();

        //    System.out.println(cs.getString(2));
        //    System.out.println(cs.getBoolean(3));
        //    System.out.println(cs.getString(5));
        //    System.out.println(cs.getString(6));

        //}

        try (Connection conn = DriverManager.getConnection(jtds); CallableStatement cs = conn.prepareCall(storedProcCall)) {
            cs.setString(ARG1, "Test");
            cs.setString(ARG4, "Test1");
            cs.registerOutParameter(ARG2, Types.VARCHAR);
            cs.registerOutParameter(ARG3, Types.BIT);
            cs.registerOutParameter(ARG5, Types.VARCHAR);
            cs.registerOutParameter(ARG6, Types.VARCHAR);

            cs.execute();

            System.out.println(cs.getString(ARG2));
            System.out.println(cs.getBoolean(ARG3));
            System.out.println(cs.getString(ARG5));
            System.out.println(cs.getString(ARG6));

        }
    }

    public static void test0()  throws Exception {
        String jtds = "jdbc:sqlserver://localhost:1433;user=sa;password=;encrypt=false;trustServerCertificate=true;";
        String storedProcCall = "{call spCertificationType(@mode = ?, @result = ?)}";

        try (Connection conn = DriverManager.getConnection(jtds); CallableStatement cs = conn.prepareCall(storedProcCall)) {
            cs.registerOutParameter(1, Types.NVARCHAR);
            cs.setInt(2, 1);

            cs.execute();

            System.out.println(cs.getString(1));

        }
    }

    // doesnt work in main
    public static void mainTest2()  throws Exception {
        String jtds = "jdbc:sqlserver://localhost:1433;user=sa;password=;encrypt=false;trustServerCertificate=true;";
        String storedProcCall = "EXEC GetFirstRowWithOutputs2 @ExactNumericIntInput=7 ?, ?, ?, ?, ?, ?, ?, ?, ?";

        try (Connection conn = DriverManager.getConnection(jtds); CallableStatement cs = conn.prepareCall(storedProcCall)) {
            System.out.println("CONNECTED...");

            cs.registerOutParameter(1, Types.BIT);
            cs.registerOutParameter(2, Types.BIGINT);
            cs.registerOutParameter(3, Types.SMALLINT);
            cs.registerOutParameter(4, Types.INTEGER);
            cs.registerOutParameter(5, Types.BIGINT);
            cs.registerOutParameter(6, Types.DECIMAL);
            cs.registerOutParameter(7, Types.NUMERIC);
            cs.registerOutParameter(8, Types.DECIMAL); // SMALLMONEY
            cs.registerOutParameter(9, Types.DECIMAL); // MONEY


            cs.execute();

            // Retrieve the output parameters
            boolean bitValue = cs.getBoolean(1);
            int tinyIntValue = cs.getInt(2);
            short smallIntValue = cs.getShort(3);
            int intValue = cs.getInt(4);
            long bigIntValue = cs.getLong(5);
            BigDecimal decimalValue = cs.getBigDecimal(6);
            BigDecimal numericValue = cs.getBigDecimal(7);
            BigDecimal smallMoneyValue = cs.getBigDecimal(8);
            BigDecimal moneyValue = cs.getBigDecimal(9);

            System.out.println(bitValue);
            System.out.println(tinyIntValue);
            System.out.println(smallIntValue);
            System.out.println(intValue);
            System.out.println(bigIntValue);
            System.out.println(decimalValue);
            System.out.println(numericValue);
            System.out.println(smallMoneyValue);
            System.out.println(moneyValue);
        }
    }

    public static void mainTest() throws Exception {
        String jdbc = "jdbc:sqlserver://localhost:1433;user=sa;password=;encrypt=false;trustServerCertificate=true;";
        String storedProcCall = "{call GetFirstRowWithOutputs(?, ?, ?, ?, ?, ?, ?, ?, ?)}";

        try (Connection conn = DriverManager.getConnection(jdbc); CallableStatement cs = conn.prepareCall(storedProcCall)) {
            System.out.println("CONNECTED...");

            cs.registerOutParameter(1, Types.BIT);
            cs.registerOutParameter(2, Types.TINYINT);
            cs.registerOutParameter(3, Types.SMALLINT);
            cs.registerOutParameter(4, Types.INTEGER);
            cs.registerOutParameter(5, Types.BIGINT);
            cs.registerOutParameter(6, Types.DECIMAL);
            cs.registerOutParameter(7, Types.NUMERIC);
            cs.registerOutParameter(8, Types.DECIMAL); // SMALLMONEY
            cs.registerOutParameter(9, Types.DECIMAL); // MONEY

            // Execute the stored procedure
            cs.execute();

            // Retrieve the output parameters
            boolean bitValue = cs.getBoolean(1);
            int tinyIntValue = cs.getInt(2);
            short smallIntValue = cs.getShort(3);
            int intValue = cs.getInt(4);
            long bigIntValue = cs.getLong(5);
            BigDecimal decimalValue = cs.getBigDecimal(6);
            BigDecimal numericValue = cs.getBigDecimal(7);
            BigDecimal smallMoneyValue = cs.getBigDecimal(8);
            BigDecimal moneyValue = cs.getBigDecimal(9);

            System.out.println(bitValue);
            System.out.println(tinyIntValue);
            System.out.println(smallIntValue);
            System.out.println(intValue);
            System.out.println(bigIntValue);
            System.out.println(decimalValue);
            System.out.println(numericValue);
            System.out.println(smallMoneyValue);
            System.out.println(moneyValue);

        }
    }

    public static void annotatedArgTest() throws Exception {
        String jdbc = "jdbc:sqlserver://localhost:1433;user=sa;password=;encrypt=false;trustServerCertificate=true;";
        String storedProcCall = "{? = call [CallableStatementTest_simpleSproc_jdbc_v-chowterry_'b44f7b95-1412-45ce-b489-00d950bc8707] (@Arg1 = ?)}";

        int expectedValue = 1; // The sproc should return this value

        try (Connection conn = DriverManager.getConnection(jdbc); CallableStatement cs = conn.prepareCall(storedProcCall)) {
            cs.registerOutParameter(1, Types.INTEGER);
            cs.setInt(1, 2);
            cs.setString(2, "foo");
            cs.execute();

            Assert.assertEquals(expectedValue, cs.getInt(1));
        }
    }

    public static void execTest() throws Exception {
        String jdbc = "jdbc:sqlserver://localhost:1433;user=sa;password=;encrypt=false;trustServerCertificate=true;";
        String storedProcCall = "exec sp_sproc_columns_100 ?, @ODBCVer=3, @fUsePattern=0";

        try (Connection conn = DriverManager.getConnection(jdbc); CallableStatement cs = conn.prepareCall(storedProcCall)) {
            System.out.println("CONNECTED...");

            cs.setString(1, "sp_getapplock");

            cs.execute();
        }
    }
}
