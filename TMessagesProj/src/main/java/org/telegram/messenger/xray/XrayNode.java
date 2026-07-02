package org.telegram.messenger.xray;

public class XrayNode {
    public static final String PROTOCOL_VLESS = "vless";
    public static final String PROTOCOL_TROJAN = "trojan";

    public String id;
    public String protocol = PROTOCOL_VLESS; // "vless" or "trojan"
    public String address;
    public int port;
    public String uuid;   // vless: UUID; trojan: password
    public String password; // trojan password (same as uuid for trojan)
    public String flow;
    public String network; // type parameter in URI (e.g. grpc, tcp, ws, xhttp)
    public String security; // security parameter (e.g. reality, tls, none)
    public String sni;
    public String fingerprint; // fp parameter
    public String publicKey; // pbk parameter
    public String shortId; // sid parameter
    public String path;
    public String remark; // fragment (name of node)
    public String serviceName; // serviceName parameter
    public String xhttpMode; // xhttp mode
    public String rawJson; // full raw JSON config parameter
    public boolean allowInsecure;
    public int priority;

    @Override
    public String toString() {
        return "XrayNode{" +
                "protocol='" + protocol + '\'' +
                ", address='" + address + '\'' +
                ", port=" + port +
                ", security='" + security + '\'' +
                ", remark='" + remark + '\'' +
                '}';
    }
}
