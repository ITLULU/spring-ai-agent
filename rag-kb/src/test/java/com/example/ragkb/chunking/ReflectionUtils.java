package com.example.ragkb.chunking;

import java.lang.reflect.Field;

/** 测试辅助：切块器参数是 @Value 注入的私有字段，单元测试里用反射直接设置。 */
final class ReflectionUtils {

    private ReflectionUtils() {
    }

    static void set(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("无法设置字段 " + fieldName, e);
        }
    }
}
