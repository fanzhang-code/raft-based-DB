package com.raftDB.raft.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;

public class ConfigLoader {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static NodeConfig load(String resourcePath) {
        try (InputStream inputStream =
                     ConfigLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {

            if (inputStream == null) {
                throw new RuntimeException("Config file not found: " + resourcePath);
            }

            return objectMapper.readValue(inputStream, NodeConfig.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config: " + resourcePath, e);
        }
    }
}