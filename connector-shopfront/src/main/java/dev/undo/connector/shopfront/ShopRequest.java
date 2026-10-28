package dev.undo.connector.shopfront;

import java.util.Map;

public record ShopRequest(String method, String path, Map<String, String> headers, byte[] body) {}
