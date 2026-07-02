package org.telegram.messenger.xray;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.GsonBuilder;

public class XrayConfigBuilder {
    public static String buildConfig(XrayNode node, int localPort) {
        if (node == null) {
            return "";
        }
        
        if ("json".equals(node.protocol) && node.rawJson != null) {
            try {
                JsonObject obj = com.google.gson.JsonParser.parseString(node.rawJson).getAsJsonObject();
                if (obj.has("inbounds")) {
                    JsonArray inbounds = obj.getAsJsonArray("inbounds");
                    if (inbounds.size() > 0) {
                        inbounds.get(0).getAsJsonObject().addProperty("port", localPort);
                    }
                }
                return new GsonBuilder().setPrettyPrinting().create().toJson(obj);
            } catch (Exception e) {
                return node.rawJson;
            }
        }
        
        JsonObject config = new JsonObject();

        // Inbounds
        JsonObject inbound = new JsonObject();
        inbound.addProperty("listen", "127.0.0.1");
        inbound.addProperty("port", localPort);
        inbound.addProperty("protocol", "socks");
        
        JsonObject inboundSettings = new JsonObject();
        inboundSettings.addProperty("auth", "noauth");
        inboundSettings.addProperty("udp", true);
        inboundSettings.addProperty("ip", "127.0.0.1");
        inbound.add("settings", inboundSettings);

        JsonArray inbounds = new JsonArray();
        inbounds.add(inbound);
        config.add("inbounds", inbounds);

        // Outbounds
        JsonObject outbound = new JsonObject();

        if (XrayNode.PROTOCOL_TROJAN.equalsIgnoreCase(node.protocol)) {
            outbound.addProperty("protocol", "trojan");

            JsonObject outboundSettings = new JsonObject();
            JsonArray servers = new JsonArray();
            JsonObject server = new JsonObject();
            server.addProperty("address", node.address);
            server.addProperty("port", node.port);
            server.addProperty("password", node.password != null ? node.password : (node.uuid != null ? node.uuid : ""));
            servers.add(server);
            outboundSettings.add("servers", servers);
            outbound.add("settings", outboundSettings);

        } else {
            // Default: VLESS
            outbound.addProperty("protocol", "vless");

            JsonObject outboundSettings = new JsonObject();
            JsonArray vnext = new JsonArray();
            JsonObject server = new JsonObject();
            server.addProperty("address", node.address);
            server.addProperty("port", node.port);

            JsonArray users = new JsonArray();
            JsonObject user = new JsonObject();
            user.addProperty("id", node.uuid);
            user.addProperty("encryption", "none");
            if (node.flow != null && !node.flow.isEmpty()) {
                user.addProperty("flow", node.flow);
            }
            users.add(user);
            server.add("users", users);
            vnext.add(server);
            outboundSettings.add("vnext", vnext);
            outbound.add("settings", outboundSettings);
        }

        // Stream settings (shared for both protocols)
        JsonObject streamSettings = new JsonObject();
        String network = (node.network != null && !node.network.isEmpty()) ? node.network : "tcp";
        streamSettings.addProperty("network", network);
        
        String security = (node.security != null && !node.security.isEmpty()) ? node.security : "none";
        streamSettings.addProperty("security", security);

        if ("tls".equalsIgnoreCase(security)) {
            JsonObject tlsSettings = new JsonObject();
            if (node.sni != null && !node.sni.isEmpty()) {
                tlsSettings.addProperty("serverName", node.sni);
            }
            if (node.fingerprint != null && !node.fingerprint.isEmpty()) {
                tlsSettings.addProperty("fingerprint", node.fingerprint);
            }
            tlsSettings.addProperty("allowInsecure", node.allowInsecure);
            streamSettings.add("tlsSettings", tlsSettings);
        } else if ("reality".equalsIgnoreCase(security)) {
            JsonObject realitySettings = new JsonObject();
            realitySettings.addProperty("show", false);
            if (node.sni != null && !node.sni.isEmpty()) {
                realitySettings.addProperty("serverName", node.sni);
            }
            if (node.fingerprint != null && !node.fingerprint.isEmpty()) {
                realitySettings.addProperty("fingerprint", node.fingerprint);
            }
            if (node.publicKey != null && !node.publicKey.isEmpty()) {
                realitySettings.addProperty("publicKey", node.publicKey);
            }
            if (node.shortId != null && !node.shortId.isEmpty()) {
                realitySettings.addProperty("shortId", node.shortId);
            }
            realitySettings.addProperty("spiderX", "");
            streamSettings.add("realitySettings", realitySettings);
        }

        // Network transport specific settings
        if ("ws".equalsIgnoreCase(network)) {
            JsonObject wsSettings = new JsonObject();
            if (node.path != null && !node.path.isEmpty()) {
                wsSettings.addProperty("path", node.path);
            }
            if (node.sni != null && !node.sni.isEmpty()) {
                JsonObject headers = new JsonObject();
                headers.addProperty("Host", node.sni);
                wsSettings.add("headers", headers);
            }
            streamSettings.add("wsSettings", wsSettings);
        } else if ("grpc".equalsIgnoreCase(network)) {
            JsonObject grpcSettings = new JsonObject();
            if (node.serviceName != null && !node.serviceName.isEmpty()) {
                grpcSettings.addProperty("serviceName", node.serviceName);
            } else if (node.path != null && !node.path.isEmpty()) {
                grpcSettings.addProperty("serviceName", node.path);
            }
            grpcSettings.addProperty("multiMode", true);
            streamSettings.add("grpcSettings", grpcSettings);
        } else if ("xhttp".equalsIgnoreCase(network)) {
            JsonObject xhttpSettings = new JsonObject();
            if (node.path != null && !node.path.isEmpty()) {
                xhttpSettings.addProperty("path", node.path);
            }
            if (node.sni != null && !node.sni.isEmpty()) {
                xhttpSettings.addProperty("host", node.sni);
            }
            if (node.xhttpMode != null && !node.xhttpMode.isEmpty()) {
                xhttpSettings.addProperty("mode", node.xhttpMode);
            }
            streamSettings.add("xhttpSettings", xhttpSettings);
        }

        outbound.add("streamSettings", streamSettings);

        JsonArray outbounds = new JsonArray();
        outbounds.add(outbound);
        config.add("outbounds", outbounds);

        return new GsonBuilder().setPrettyPrinting().create().toJson(config);
    }
}
