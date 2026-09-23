package com.ruoyi.web.controller.sqms;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.AjaxResult;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class SqmsRecordControllerStockTest
{
    private JdbcTemplate jdbc;
    private SqmsRecordController controller;

    @Before
    public void setUp() throws Exception
    {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:stocktest;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS sqms_record");
        jdbc.execute("CREATE TABLE sqms_record (table_name VARCHAR(64), record_id VARCHAR(80), " +
                "record_json CLOB, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "PRIMARY KEY (table_name, record_id))");
        controller = new SqmsRecordController();
        Field field = SqmsRecordController.class.getDeclaredField("jdbcTemplate");
        field.setAccessible(true);
        field.set(controller, jdbc);
    }

    private void insert(String table, String id, Map<String, Object> record)
    {
        record.put("_id", id);
        jdbc.update("INSERT INTO sqms_record(table_name, record_id, record_json) VALUES(?, ?, ?)",
                table, id, JSON.toJSONString(record));
    }

    private JSONObject record(String table, String id)
    {
        String json = jdbc.queryForObject(
                "SELECT record_json FROM sqms_record WHERE table_name = ? AND record_id = ?",
                String.class, table, id);
        return JSON.parseObject(json);
    }

    private Map<String, Object> row(Object... values)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2)
        {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    @Test
    public void stockInIsIdempotentAndProtectsInventoryFromStalePush()
    {
        insert("products", "p", row("stock", 5, "unitSmall", "个", "unitMedium", "包",
                "mediumToSmall", 12));
        insert("purchaseOrders", "o", row("status", "purchased"));
        insert("purchaseItems", "i", row("purchaseOrderId", "o", "productId", "p",
                "qty", 2, "unit", "包", "unitFactor", 12,
                "sourcePurchaseRequestItemIds", Arrays.asList("ri"),
                "sourcePurchaseRequestIds", Arrays.asList("r")));
        insert("purchaseRequests", "r", row("status", "purchased"));
        insert("purchaseRequestItems", "ri", row("requestId", "r", "status", "purchased"));

        AjaxResult result = controller.stockInPurchase(row("orderId", "o", "stockInBy", "管理员"));
        assertEquals(200, result.get("code"));
        assertEquals(0, new BigDecimal(record("products", "p").getString("stock"))
                .compareTo(new BigDecimal("29")));
        assertEquals(1L, record("products", "p").getLongValue("stockVersion"));
        assertEquals("approved", record("purchaseOrders", "o").getString("status"));
        assertEquals("converted", record("purchaseRequestItems", "ri").getString("status"));
        assertEquals("converted", record("purchaseRequests", "r").getString("status"));
        assertEquals(0, new BigDecimal(record("purchaseItems", "i").getString("stockInQty"))
                .compareTo(new BigDecimal("24")));

        controller.stockInPurchase(row("orderId", "o", "stockInBy", "管理员"));
        assertEquals(0, new BigDecimal(record("products", "p").getString("stock"))
                .compareTo(new BigDecimal("29")));

        controller.edit("purchaseItems", "i", row("purchaseOrderId", "o", "productId", "p", "qty", 100));
        assertEquals(2, record("purchaseItems", "i").getIntValue("qty"));
        controller.remove("purchaseItems", "i");
        controller.remove("purchaseOrders", "o");
        assertEquals("approved", record("purchaseOrders", "o").getString("status"));
        assertEquals(2, record("purchaseItems", "i").getIntValue("qty"));

        controller.edit("products", "p", row("stock", 5, "stockVersion", 0, "unitSmall", "个"));
        assertEquals(0, new BigDecimal(record("products", "p").getString("stock"))
                .compareTo(new BigDecimal("29")));
    }

    @Test
    public void existingStockedOrderWithoutItemMarkerCannotStockInAgain()
    {
        insert("products", "p", row("stock", 50, "unitSmall", "个"));
        insert("purchaseOrders", "o", row("status", "approved", "stockInTime", 123456L));
        insert("purchaseItems", "i", row("purchaseOrderId", "o", "productId", "p", "qty", 4));

        AjaxResult result = controller.stockInPurchase(row("orderId", "o"));
        assertEquals(200, result.get("code"));
        assertEquals(50, record("products", "p").getIntValue("stock"));
        assertEquals(0, record("products", "p").getLongValue("stockVersion"));
    }
}
