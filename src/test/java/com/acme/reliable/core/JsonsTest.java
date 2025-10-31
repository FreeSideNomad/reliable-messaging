package com.acme.reliable.core;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JsonsTest {

    @Test
    void testToJson() {
        Map<String, String> data = Map.of("key1", "value1", "key2", "value2");
        String json = Jsons.toJson(data);
        assertNotNull(json);
        assertTrue(json.contains("key1"));
        assertTrue(json.contains("value1"));
    }

    @Test
    void testOf() {
        String json = Jsons.of("error", "something went wrong");
        assertNotNull(json);
        assertTrue(json.contains("error"));
        assertTrue(json.contains("something went wrong"));
    }

    @Test
    void testMerge() {
        Map<String, String> map1 = Map.of("a", "1", "b", "2");
        Map<String, String> map2 = Map.of("c", "3", "d", "4");
        Map<String, String> merged = Jsons.merge(map1, map2);

        assertEquals(4, merged.size());
        assertEquals("1", merged.get("a"));
        assertEquals("2", merged.get("b"));
        assertEquals("3", merged.get("c"));
        assertEquals("4", merged.get("d"));
    }

    @Test
    void testMergeOverwrite() {
        Map<String, String> map1 = Map.of("a", "1", "b", "2");
        Map<String, String> map2 = Map.of("b", "99", "c", "3");
        Map<String, String> merged = Jsons.merge(map1, map2);

        assertEquals(3, merged.size());
        assertEquals("1", merged.get("a"));
        assertEquals("99", merged.get("b")); // map2 overwrites
        assertEquals("3", merged.get("c"));
    }
}
