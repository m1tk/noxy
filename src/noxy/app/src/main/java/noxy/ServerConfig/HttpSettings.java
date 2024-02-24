package noxy.ServerConfig;

public class HttpSettings {
    public String scheme;
    public String host;
    public int port;
    public String uri;

    public static HttpSettings defaults() {
        HttpSettings def = new HttpSettings();
        def.scheme = "";
        def.host   = "";
        def.port   = 0;
        def.uri    = "";

        return def;
    }
}
