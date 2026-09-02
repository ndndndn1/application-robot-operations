package io.career262.fleet;

import com.fasterxml.jackson.databind.JsonNode;

interface RobotGateway {
    String identity();

    JsonNode state(String robotId);

    JsonNode product(String productId);

    JsonNode submit(JsonNode request);

    JsonNode status(String commandId);

    JsonNode cancel(String commandId);
}
