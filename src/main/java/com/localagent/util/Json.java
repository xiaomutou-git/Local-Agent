package com.localagent.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * JSON 工具类：统一封装 Jackson ObjectMapper。
 * 设计思路：全应用共用一个配置好的 mapper（不输出失败时抛 JsonException 包装），
 * 避免各处重复配置；开启宽松类型转换以容忍 SQLite 取出的数字/字符串混存。
 */
public final class Json {
    private static final ObjectMapper M = new ObjectMapper()
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private Json() {}

    /** 任意对象序列化为 JSON 字符串；失败抛出非受检异常。 */
    public static String stringify(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    /** 反序列化为指定类型；失败抛出非受检异常。 */
    public static <T> T parse(String s, Class<T> clazz) {
        try {
            return M.readValue(s, clazz);
        } catch (Exception e) {
            throw new RuntimeException("JSON 反序列化失败: " + e.getMessage(), e);
        }
    }

    /** 反序列化为泛型类型（如 Map/List），用 Object 承接后再由调用方转型。 */
    public static Object parseAny(String s) {
        try {
            return M.readValue(s, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static ObjectMapper mapper() { return M; }
}
