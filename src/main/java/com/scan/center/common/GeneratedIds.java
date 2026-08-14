package com.scan.center.common;

import java.util.Map;
import org.springframework.jdbc.support.KeyHolder;

/** H2 在 RETURN_GENERATED_KEYS 时可能同时返回 ID + CREATE_TIME，不能直接用 getKey()。 */
public final class GeneratedIds {
  private GeneratedIds() {}

  public static long require(KeyHolder keyHolder) {
    Map<String, Object> keys = keyHolder.getKeys();
    if (keys != null && !keys.isEmpty()) {
      Object id = first(keys, "ID", "id");
      if (id instanceof Number) {
        return ((Number) id).longValue();
      }
      if (keys.size() == 1) {
        Object only = keys.values().iterator().next();
        if (only instanceof Number) {
          return ((Number) only).longValue();
        }
      }
    }
    throw new IllegalStateException("未能获取自增主键: " + keys);
  }

  private static Object first(Map<String, Object> keys, String... names) {
    for (String name : names) {
      if (keys.containsKey(name)) {
        return keys.get(name);
      }
    }
    return null;
  }
}
