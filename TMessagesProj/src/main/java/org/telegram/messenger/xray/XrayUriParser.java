package org.telegram.messenger.xray;

import android.net.Uri;
import java.net.URLDecoder;

public class XrayUriParser {

    /**
     * Parses a VLESS or Trojan URI into an XrayNode.
     */
    public static XrayNode parseUri(String uriStr) {
        if (uriStr == null) {
            return null;
        }
        uriStr = uriStr.trim();
        Uri uri = Uri.parse(uriStr);
        String scheme = uri.getScheme();
        if ("vless".equals(scheme)) {
            return parseVless(uriStr);
        } else if ("trojan".equals(scheme)) {
            return parseTrojan(uriStr);
        } else {
            throw new IllegalArgumentException("Unsupported URI scheme: " + scheme + " in: " + uriStr);
        }
    }

    public static XrayNode parseVless(String uriStr) {
        if (uriStr == null) {
            return null;
        }
        uriStr = uriStr.trim();
        Uri uri = Uri.parse(uriStr);
        if (!"vless".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Not a VLESS URI: " + uriStr);
        }

        XrayNode node = new XrayNode();
        node.protocol = XrayNode.PROTOCOL_VLESS;
        node.uuid = uri.getUserInfo();
        node.address = uri.getHost();
        node.port = uri.getPort();

        // Uri.getFragment() automatically decodes the fragment (the part after #)
        String fragment = uri.getFragment();
        if (fragment != null) {
            try {
                node.remark = URLDecoder.decode(fragment, "UTF-8");
            } catch (Exception e) {
                node.remark = fragment;
            }
        }

        node.flow = uri.getQueryParameter("flow");
        node.network = uri.getQueryParameter("type");
        node.security = uri.getQueryParameter("security");
        node.sni = uri.getQueryParameter("sni");
        node.fingerprint = uri.getQueryParameter("fp");
        node.publicKey = uri.getQueryParameter("pbk");
        node.shortId = uri.getQueryParameter("sid");
        node.path = uri.getQueryParameter("path");
        node.serviceName = uri.getQueryParameter("serviceName");
        node.xhttpMode = uri.getQueryParameter("xhttpMode");
        
        String allowInsecureParam = uri.getQueryParameter("allowInsecure");
        node.allowInsecure = "true".equalsIgnoreCase(allowInsecureParam) || "1".equals(allowInsecureParam);

        return node;
    }

    public static XrayNode parseTrojan(String uriStr) {
        if (uriStr == null) {
            return null;
        }
        uriStr = uriStr.trim();
        Uri uri = Uri.parse(uriStr);
        if (!"trojan".equals(uri.getScheme())) {
            throw new IllegalArgumentException("Not a Trojan URI: " + uriStr);
        }

        XrayNode node = new XrayNode();
        node.protocol = XrayNode.PROTOCOL_TROJAN;
        // In trojan URIs, userInfo is the password
        node.password = uri.getUserInfo();
        node.uuid = node.password; // alias for convenience
        node.address = uri.getHost();
        node.port = uri.getPort();

        String fragment = uri.getFragment();
        if (fragment != null) {
            try {
                node.remark = URLDecoder.decode(fragment, "UTF-8");
            } catch (Exception e) {
                node.remark = fragment;
            }
        }

        // Trojan transport params
        node.network = uri.getQueryParameter("type");
        if (node.network == null) {
            // some clients use "headerType" instead of "type"
            node.network = uri.getQueryParameter("headerType");
        }
        node.sni = uri.getQueryParameter("sni");
        node.fingerprint = uri.getQueryParameter("fp");
        node.path = uri.getQueryParameter("path");
        node.serviceName = uri.getQueryParameter("serviceName");
        node.xhttpMode = uri.getQueryParameter("xhttpMode");

        // Trojan security: default to tls if sni is present, else none
        String security = uri.getQueryParameter("security");
        if (security != null && !security.isEmpty()) {
            node.security = security;
        } else if (node.sni != null && !node.sni.isEmpty()) {
            node.security = "tls";
        } else {
            node.security = "tls"; // trojan almost always uses TLS
        }

        String allowInsecureParam = uri.getQueryParameter("allowInsecure");
        node.allowInsecure = "true".equalsIgnoreCase(allowInsecureParam) || "1".equals(allowInsecureParam);

        return node;
    }
}
