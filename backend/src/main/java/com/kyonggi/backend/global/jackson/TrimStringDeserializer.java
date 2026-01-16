package com.kyonggi.backend.global.jackson;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * 문자열 입력을 trim 처리하는 역직렬화기.
 *
 * - DTO 레벨에서 공백만 제거하고 싶을 때 사용한다.
 * - null은 그대로 null 유지한다.
 */
public class TrimStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String v = p.getValueAsString();
        return v == null ? null : v.trim();
    }
}
