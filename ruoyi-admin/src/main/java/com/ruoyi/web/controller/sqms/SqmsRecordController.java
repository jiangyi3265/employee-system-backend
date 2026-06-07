package com.ruoyi.web.controller.sqms;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.utils.StringUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * SQMS business data API.
 *
 * The mobile app prototype stores all business entities in named tables. This
 * controller keeps that contract while moving the source of truth to the
 * backend, so admin web and app can read/write the same records.
 */
@Anonymous
@RestController
@RequestMapping("/sqms")
public class SqmsRecordController implements InitializingBean
{
    private static final List<String> TABLES = Arrays.asList(
            "employees", "customers", "suppliers", "competitors", "products",
            "quoteOrders", "quoteItems", "competitorQuotes", "follows",
            "purchaseOrders", "purchaseItems", "requestOrders", "requestItems",
            "suggestions", "messages", "settings"
    );

    private static final Set<String> RESERVED_QUERY_KEYS = new HashSet<>(Arrays.asList(
            "pageNum", "pageSize", "orderByColumn", "isAsc", "keyword", "_", "t"
    ));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void afterPropertiesSet()
    {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sqms_record (" +
                "id BIGINT NOT NULL AUTO_INCREMENT," +
                "table_name VARCHAR(64) NOT NULL," +
                "record_id VARCHAR(80) NOT NULL," +
                "record_json LONGTEXT NOT NULL," +
                "create_time DATETIME DEFAULT CURRENT_TIMESTAMP," +
                "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "PRIMARY KEY (id)," +
                "UNIQUE KEY uk_sqms_record (table_name, record_id)," +
                "KEY idx_sqms_record_table (table_name)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    @GetMapping("/tables")
    public AjaxResult tables()
    {
        return AjaxResult.success(TABLES);
    }

    @GetMapping("/{table}/list")
    public TableDataInfo list(@PathVariable String table, @RequestParam Map<String, String> query)
    {
        checkTable(table);
        List<Map<String, Object>> rows = filterRows(table, query);
        sortRows(rows, query.get("orderByColumn"), query.get("isAsc"));

        int pageNum = parseInt(query.get("pageNum"), 1);
        int pageSize = parseInt(query.get("pageSize"), rows.size() == 0 ? 10 : rows.size());
        pageNum = Math.max(pageNum, 1);
        pageSize = Math.max(pageSize, 1);
        int from = Math.max((pageNum - 1) * pageSize, 0);
        int to = Math.min(from + pageSize, rows.size());
        List<Map<String, Object>> page = from >= rows.size() ? Collections.emptyList() : rows.subList(from, to);

        TableDataInfo rspData = new TableDataInfo();
        rspData.setCode(200);
        rspData.setMsg("查询成功");
        rspData.setRows(page);
        rspData.setTotal(rows.size());
        return rspData;
    }

    @GetMapping("/{table}/{id}")
    public AjaxResult get(@PathVariable String table, @PathVariable String id)
    {
        checkTable(table);
        Map<String, Object> record = getRecord(table, id);
        return record == null ? AjaxResult.error("记录不存在") : AjaxResult.success(record);
    }

    @PostMapping("/{table}")
    public AjaxResult add(@PathVariable String table, @RequestBody Map<String, Object> body)
    {
        checkTable(table);
        return AjaxResult.success(upsert(table, body));
    }

    @PutMapping("/{table}/{id}")
    public AjaxResult edit(@PathVariable String table, @PathVariable String id, @RequestBody Map<String, Object> body)
    {
        checkTable(table);
        body.put("_id", id);
        return AjaxResult.success(upsert(table, body));
    }

    @DeleteMapping("/{table}/{ids}")
    public AjaxResult remove(@PathVariable String table, @PathVariable String ids)
    {
        checkTable(table);
        int count = 0;
        for (String id : ids.split(","))
        {
            count += jdbcTemplate.update("DELETE FROM sqms_record WHERE table_name = ? AND record_id = ?", table, id);
        }
        return AjaxResult.success(count);
    }

    @GetMapping("/sync/pull")
    public AjaxResult pull()
    {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String table : TABLES)
        {
            data.put(table, loadTable(table));
        }
        data.put("serverTime", System.currentTimeMillis());
        return AjaxResult.success(data);
    }

    @PostMapping("/sync/push")
    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings("unchecked")
    public AjaxResult push(@RequestBody Map<String, Object> payload)
    {
        Object tablesObj = payload.get("tables");
        if (!(tablesObj instanceof Map))
        {
            return AjaxResult.error("缺少 tables 数据");
        }
        Map<String, Object> tables = (Map<String, Object>) tablesObj;
        int count = 0;
        for (Map.Entry<String, Object> entry : tables.entrySet())
        {
            String table = entry.getKey();
            checkTable(table);
            if (!(entry.getValue() instanceof List))
            {
                continue;
            }
            List<?> records = (List<?>) entry.getValue();
            Set<String> incomingIds = new HashSet<>();
            for (Object record : records)
            {
                if (record instanceof Map)
                {
                    Map<String, Object> saved = upsert(table, new LinkedHashMap<>((Map<String, Object>) record));
                    incomingIds.add(asString(saved.get("_id")));
                    count++;
                }
            }
            deleteMissingRecords(table, incomingIds);
        }
        AjaxResult result = AjaxResult.success();
        result.put("count", count);
        result.put("serverTime", System.currentTimeMillis());
        return result;
    }

    @PostMapping("/auth/login")
    public AjaxResult login(@RequestBody Map<String, Object> body)
    {
        String role = asString(body.get("role"));
        String phone = asString(body.get("phone"));
        String password = asString(body.get("password"));
        String table = "customer".equals(role) ? "customers" : "employees";

        for (Map<String, Object> user : loadTable(table))
        {
            if (phone.equals(asString(user.get("phone"))))
            {
                if (!password.equals(asString(user.get("password"))))
                {
                    return AjaxResult.error("密码错误");
                }
                if ("customers".equals(table) && !Boolean.TRUE.equals(user.get("approved")))
                {
                    return AjaxResult.error("账号待管理员审核通过");
                }
                if (Boolean.TRUE.equals(user.get("disabled")))
                {
                    return AjaxResult.error("账号已停用");
                }
                String userRole = "customers".equals(table) ? "customer" : asString(user.get("role"));
                Map<String, Object> session = new LinkedHashMap<>();
                session.put("role", StringUtils.isEmpty(userRole) ? "employee" : userRole);
                session.put("id", user.get("_id"));
                session.put("name", user.get("name"));
                AjaxResult result = AjaxResult.success();
                result.put("session", session);
                result.put("user", sanitizeUser(user));
                return result;
            }
        }
        return AjaxResult.error("手机号未注册");
    }

    @PostMapping("/auth/register")
    public AjaxResult register(@RequestBody Map<String, Object> body)
    {
        String phone = asString(body.get("phone"));
        for (Map<String, Object> customer : loadTable("customers"))
        {
            if (phone.equals(asString(customer.get("phone"))))
            {
                return AjaxResult.error("该手机号已注册");
            }
        }
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", body.get("name"));
        customer.put("phone", phone);
        customer.put("password", body.get("password"));
        customer.put("company", body.getOrDefault("company", ""));
        customer.put("grade", "C");
        customer.put("pool", "public");
        customer.put("ownerId", "");
        customer.put("approved", false);
        return AjaxResult.success(sanitizeUser(upsert("customers", customer)));
    }

    private List<Map<String, Object>> filterRows(String table, Map<String, String> query)
    {
        String keyword = query.get("keyword");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : loadTable(table))
        {
            if (!matchKeyword(row, keyword))
            {
                continue;
            }
            if (!matchFields(row, query))
            {
                continue;
            }
            rows.add(row);
        }
        return rows;
    }

    private boolean matchKeyword(Map<String, Object> row, String keyword)
    {
        if (StringUtils.isEmpty(keyword))
        {
            return true;
        }
        return JSON.toJSONString(row).toLowerCase().contains(keyword.toLowerCase());
    }

    private boolean matchFields(Map<String, Object> row, Map<String, String> query)
    {
        for (Map.Entry<String, String> entry : query.entrySet())
        {
            if (RESERVED_QUERY_KEYS.contains(entry.getKey()) || StringUtils.isEmpty(entry.getValue()))
            {
                continue;
            }
            Object value = row.get(entry.getKey());
            if (value == null || !String.valueOf(value).contains(entry.getValue()))
            {
                return false;
            }
        }
        return true;
    }

    private void sortRows(List<Map<String, Object>> rows, String sortBy, String isAsc)
    {
        String field = StringUtils.isEmpty(sortBy) ? "updateTime" : sortBy;
        boolean asc = "ascending".equals(isAsc) || "asc".equals(isAsc);
        rows.sort((a, b) -> {
            Comparable va = comparable(a.get(field));
            Comparable vb = comparable(b.get(field));
            int ret = va.compareTo(vb);
            return asc ? ret : -ret;
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Comparable comparable(Object value)
    {
        if (value == null)
        {
            return "";
        }
        return String.valueOf(value);
    }

    private Map<String, Object> upsert(String table, Map<String, Object> body)
    {
        long now = System.currentTimeMillis();
        String id = asString(body.get("_id"));
        if (StringUtils.isEmpty(id))
        {
            id = table + "_" + Long.toString(now, 36) + Integer.toString(Math.abs(body.hashCode()), 36);
            body.put("_id", id);
        }
        body.putIfAbsent("createTime", now);
        body.put("updateTime", now);

        jdbcTemplate.update("INSERT INTO sqms_record(table_name, record_id, record_json) VALUES(?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE record_json = VALUES(record_json), update_time = CURRENT_TIMESTAMP",
                table, id, JSON.toJSONString(body));
        return body;
    }

    private Map<String, Object> getRecord(String table, String id)
    {
        List<Map<String, Object>> rows = jdbcTemplate.query("SELECT record_json FROM sqms_record WHERE table_name = ? AND record_id = ?",
                (rs, rowNum) -> parseRecord(rs.getString("record_json")), table, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> loadTable(String table)
    {
        return jdbcTemplate.query("SELECT record_json FROM sqms_record WHERE table_name = ?",
                (rs, rowNum) -> parseRecord(rs.getString("record_json")), table);
    }

    private void deleteMissingRecords(String table, Set<String> incomingIds)
    {
        if (incomingIds.isEmpty())
        {
            jdbcTemplate.update("DELETE FROM sqms_record WHERE table_name = ?", table);
            return;
        }

        String placeholders = String.join(",", Collections.nCopies(incomingIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(table);
        args.addAll(incomingIds);
        jdbcTemplate.update("DELETE FROM sqms_record WHERE table_name = ? AND record_id NOT IN (" + placeholders + ")",
                args.toArray());
    }

    private Map<String, Object> parseRecord(String json)
    {
        JSONObject object = JSON.parseObject(json);
        return new LinkedHashMap<>(object);
    }

    private int parseInt(String value, int fallback)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (Exception e)
        {
            return fallback;
        }
    }

    private String asString(Object value)
    {
        return value == null ? "" : String.valueOf(value);
    }

    private Map<String, Object> sanitizeUser(Map<String, Object> user)
    {
        Map<String, Object> sanitized = new LinkedHashMap<>(user);
        sanitized.remove("password");
        return sanitized;
    }

    private void checkTable(String table)
    {
        if (!TABLES.contains(table))
        {
            throw new IllegalArgumentException("非法业务表：" + table);
        }
    }
}
