package io.career262.fleet;

import com.fasterxml.jackson.databind.JsonNode;

interface RobotGateway {
    JsonNode submit(JsonNode request);

    JsonNode status(String commandId);

    JsonNode cancel(String commandId);
}
