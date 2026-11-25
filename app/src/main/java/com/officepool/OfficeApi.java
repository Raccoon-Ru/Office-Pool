package com.officepool;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class OfficeApi {
    private final String base;

    OfficeApi(String base) {
        this.base = base.replaceAll("/+$", "");
    }

    JSONObject get(String path, String token) throws Exception {
        return request("GET", path, token, null, null);
    }

    JSONObject post(String path, String token, JSONObject body) throws Exception {
        return requestJson("POST", path, token, body);
    }

    JSONObject delete(String path, String token, JSONObject body) throws Exception {
        return requestJson("DELETE", path, token, body);
    }

    JSONObject put(String path, String token, JSONObject body) throws Exception {
        return requestJson("PUT", path, token, body);
    }

    JSONObject upload(String path, String token, File file, String mime) throws Exception {
        HttpURLConnection connection = open("POST", path, token);
        try {
            connection.setReadTimeout(20_000);
            prepareBody(connection, file.length(), mime);
            try (InputStream in = new FileInputStream(file);
                    OutputStream out = connection.getOutputStream()) {
                copy(in, out, Long.MAX_VALUE);
            }
            return parseJson(read(connection));
        } finally {
            connection.disconnect();
        }
    }

    void download(String path, String token, File destination, long maxBytes) throws Exception {
        HttpURLConnection connection = open("GET", path, token);
        try {
            connection.setReadTimeout(20_000);
            requireSuccess(connection, connection.getResponseCode());
            try (InputStream in = connection.getInputStream();
                    OutputStream out = new FileOutputStream(destination)) {
                copy(in, out, maxBytes);
            }
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject requestJson(String method, String path, String token, JSONObject body)
            throws Exception {
        byte[] payload = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        return request(method, path, token, payload, "application/json; charset=utf-8");
    }

    private JSONObject request(String method, String path, String token, byte[] body, String mime)
            throws Exception {
        HttpURLConnection connection = open(method, path, token);
        try {
            if (body != null) {
                prepareBody(connection, body.length, mime);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            return parseJson(read(connection));
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String method, String path, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3500);
        connection.setReadTimeout(6000);
        connection.setUseCaches(false);
        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        return connection;
    }

    private void prepareBody(HttpURLConnection connection, long length, String mime) {
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", mime);
        connection.setFixedLengthStreamingMode(length);
    }

    private JSONObject parseJson(byte[] bytes) throws org.json.JSONException {
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    private byte[] read(HttpURLConnection connection) throws Exception {
        requireSuccess(connection, connection.getResponseCode());
        return readStream(connection.getInputStream(), Long.MAX_VALUE);
    }

    private void requireSuccess(HttpURLConnection connection, int code) throws ApiException {
        if (code >= 200 && code < 300) return;
        String message = "Server returned " + code;
        try {
            message =
                    parseJson(readStream(connection.getErrorStream(), 65_536))
                            .optString("error", message);
        } catch (Exception ignored) {
            // An empty or non-JSON response still has an HTTP status code.
        }
        throw new ApiException(code, message);
    }

    private byte[] readStream(InputStream input, long limit) throws IOException {
        if (input == null) return new byte[0];
        try (InputStream in = input;
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out, limit);
            return out.toByteArray();
        }
    }

    static void copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int bytes;
        while ((bytes = in.read(buffer)) != -1) {
            total += bytes;
            if (total > limit) throw new IOException("File is too large.");
            out.write(buffer, 0, bytes);
        }
    }

    static final class ApiException extends Exception {
        final int code;

        ApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
