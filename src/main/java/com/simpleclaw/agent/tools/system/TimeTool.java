package com.simpleclaw.agent.tools.system;

import com.simpleclaw.agent.tools.BaseTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 【时间工具类 - 获取当前时间信息】
 * 
 * 功能说明：
 * 此工具允许 Agent 获取当前系统时间，支持多种格式和时区。
 * 主要用于：
 * - 定时任务设置时需要知道当前时间
 * - 回答与时间相关的问题
 * - 计算时间差或倒计时
 * 
 * 提供的功能：
 * 1. 获取当前时间戳（毫秒）
 * 2. 获取格式化日期时间（ISO 8601 格式）
 * 3. 获取指定时区的当前时间
 * 4. 获取当前星期几
 * 
 * 使用示例：
 * - 获取当前时间：{"format": "iso", "timezone": "Asia/Shanghai"}
 * - 获取时间戳：{"format": "timestamp"}
 * - 获取当前日期：{"format": "date", "timezone": "UTC"}
 */
public class TimeTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "time";
    }

    @Override
    public String getDescription() {
        return "获取当前系统时间信息，支持多种格式和时区，用于定时任务设置和时间相关查询";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> format = new HashMap<>();
        format.put("type", "string");
        format.put("enum", new String[]{"iso", "iso8601", "timestamp", "date", "time", "full"});
        format.put("description", "时间格式：iso(ISO8601格式), timestamp(时间戳毫秒), date(仅日期), time(仅时间), full(完整信息)");
        properties.put("format", format);
        
        Map<String, Object> timezone = new HashMap<>();
        timezone.put("type", "string");
        timezone.put("description", "时区，如 Asia/Shanghai(中国), UTC, America/New_York 等");
        properties.put("timezone", timezone);
        
        Map<String, Object> params = new HashMap<>();
        params.put("type", "object");
        params.put("properties", properties);
        return params;
    }

    @Override
    public String execute(Map<String, Object> params) {
        try {
            // 解析参数
            String format = params.containsKey("format") ? (String) params.get("format") : "iso";
            String timezone = params.containsKey("timezone") ? (String) params.get("timezone") : "Asia/Shanghai";
            
            // 获取当前时间
            Instant now = Instant.now();
            ZoneId zoneId;
            try {
                zoneId = ZoneId.of(timezone);
            } catch (Exception e) {
                zoneId = ZoneId.systemDefault();
                timezone = zoneId.getId();
            }
            ZonedDateTime zdt = now.atZone(zoneId);
            
            // 根据格式返回结果
            ObjectNode result = MAPPER.createObjectNode();
            result.put("timezone", timezone);
            result.put("timestamp_ms", now.toEpochMilli());
            
            switch (format.toLowerCase()) {
                case "timestamp":
                    // 仅返回时间戳
                    result.put("value", now.toEpochMilli());
                    break;
                    
                case "iso":
                case "iso8601":
                    // ISO 8601 格式
                    result.put("datetime", zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    result.put("value", zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    break;
                    
                case "date":
                    // 仅日期
                    String dateStr = zdt.format(DateTimeFormatter.ISO_LOCAL_DATE);
                    result.put("date", dateStr);
                    result.put("value", dateStr);
                    break;
                    
                case "time":
                    // 仅时间
                    String timeStr = zdt.format(DateTimeFormatter.ISO_LOCAL_TIME);
                    result.put("time", timeStr);
                    result.put("value", timeStr);
                    break;
                    
                case "full":
                default:
                    // 完整信息
                    result.put("datetime", zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    result.put("date", zdt.format(DateTimeFormatter.ISO_LOCAL_DATE));
                    result.put("time", zdt.format(DateTimeFormatter.ISO_LOCAL_TIME));
                    result.put("year", zdt.getYear());
                    result.put("month", zdt.getMonthValue());
                    result.put("day", zdt.getDayOfMonth());
                    result.put("hour", zdt.getHour());
                    result.put("minute", zdt.getMinute());
                    result.put("second", zdt.getSecond());
                    result.put("day_of_week", zdt.getDayOfWeek().toString());
                    result.put("day_of_week_cn", getChineseDayOfWeek(zdt.getDayOfWeek().toString()));
                    result.put("value", zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    break;
            }
            
            return result.toString();
            
        } catch (Exception e) {
            ObjectNode error = MAPPER.createObjectNode();
            error.put("error", "获取时间失败: " + e.getMessage());
            error.put("timestamp_ms", System.currentTimeMillis());
            return error.toString();
        }
    }

    /**
     * 【获取中文星期】
     */
    private String getChineseDayOfWeek(String englishDay) {
        Map<String, String> dayMap = new HashMap<>();
        dayMap.put("MONDAY", "星期一");
        dayMap.put("TUESDAY", "星期二");
        dayMap.put("WEDNESDAY", "星期三");
        dayMap.put("THURSDAY", "星期四");
        dayMap.put("FRIDAY", "星期五");
        dayMap.put("SATURDAY", "星期六");
        dayMap.put("SUNDAY", "星期日");
        return dayMap.getOrDefault(englishDay, englishDay);
    }
}
