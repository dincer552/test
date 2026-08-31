package web;

import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.OAuth1AccessToken;
import com.github.scribejava.core.model.OAuth1RequestToken;
import com.github.scribejava.core.model.OAuthRequest;
import com.github.scribejava.core.model.Response;
import com.github.scribejava.core.model.Verb;
import com.github.scribejava.core.oauth.OAuth10aService;
import core.net.HattrickAPI;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public final class PlayerWebApplication {
    private static final Map<String, OAuth1RequestToken> REQUEST_TOKENS = new ConcurrentHashMap<>();
    private static final Map<String, OAuth1AccessToken> ACCESS_TOKENS = new ConcurrentHashMap<>();
    private static final String TEAM_URL = "https://chpp.hattrick.org/chppxml.ashx?file=teamdetails&version=3.0";
    private static final String PLAYERS_URL = "https://chpp.hattrick.org/chppxml.ashx?file=players&version=1.3&actionType=view&orderBy=PlayerNumber";

    private PlayerWebApplication() {}

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "10000"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", PlayerWebApplication::root);
        server.createContext("/health", PlayerWebApplication::health);
        server.createContext("/oauth/start", PlayerWebApplication::oauthStart);
        server.createContext("/oauth/callback", PlayerWebApplication::oauthCallback);
        server.createContext("/team", PlayerWebApplication::teamPage);
        server.createContext("/players", PlayerWebApplication::playersPage);
        server.createContext("/api/players", PlayerWebApplication::playersApi);
        server.setExecutor(null);
        server.start();
        System.out.println("HO Web player server started on port " + port);
    }

    private static OAuth10aService oauthService() {
        String key = System.getenv("CHPP_CONSUMER_KEY");
        String secret = System.getenv("CHPP_CONSUMER_SECRET");
        String callback = System.getenv("CHPP_CALLBACK_URL");
        if (key == null || secret == null || callback == null) throw new IllegalStateException("CHPP environment variables are missing");
        return new ServiceBuilder(key).apiSecret(secret).callback(callback).build(HattrickAPI.instance());
    }

    private static OAuth1AccessToken accessToken(HttpExchange exchange) {
        String session = cookie(exchange, "HO_SESSION");
        return session == null ? null : ACCESS_TOKENS.get(session);
    }

    private static void oauthStart(HttpExchange exchange) throws IOException {
        try {
            OAuth10aService service = oauthService();
            OAuth1RequestToken token = service.getRequestToken();
            String session = UUID.randomUUID().toString();
            REQUEST_TOKENS.put(session, token);
            exchange.getResponseHeaders().add("Set-Cookie", "HO_SESSION=" + session + "; Path=/; HttpOnly; Secure; SameSite=Lax");
            redirect(exchange, service.getAuthorizationUrl(token));
        } catch (Exception e) { send(exchange, 500, "CHPP OAuth başlatılamadı: " + e.getMessage(), "text/plain; charset=UTF-8"); }
    }

    private static void oauthCallback(HttpExchange exchange) throws IOException {
        try {
            Map<String,String> q = query(exchange.getRequestURI());
            String verifier = q.get("oauth_verifier");
            String session = cookie(exchange, "HO_SESSION");
            OAuth1RequestToken request = session == null ? null : REQUEST_TOKENS.remove(session);
            if (verifier == null || request == null) { send(exchange, 400, "OAuth oturumu geçersiz.", "text/plain; charset=UTF-8"); return; }
            ACCESS_TOKENS.put(session, oauthService().getAccessToken(request, verifier));
            redirect(exchange, "/players");
        } catch (Exception e) { send(exchange, 500, "CHPP OAuth callback hatası: " + e.getMessage(), "text/plain; charset=UTF-8"); }
    }

    private static void root(HttpExchange exchange) throws IOException {
        send(exchange, 200, "<html lang='tr'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>HO Web</title><style>body{font:14px system-ui;margin:0;padding:32px;background:#f5f7fb}main{max-width:720px;margin:auto;background:#fff;padding:28px;border-radius:16px}a{display:inline-block;margin:6px 6px 0 0;padding:10px 14px;background:#1677ff;color:#fff;text-decoration:none;border-radius:8px}</style></head><body><main><h1>Hattrick Organizer Web</h1><p>CHPP bağlantısı hazır.</p><a href='/oauth/start'>Hattrick hesabını bağla</a><a href='/players'>Kadroyu göster</a></main></body></html>", "text/html; charset=UTF-8");
    }

    private static void health(HttpExchange exchange) throws IOException { send(exchange, 200, "OK\n", "text/plain; charset=UTF-8"); }

    private static void teamPage(HttpExchange exchange) throws IOException {
        if (accessToken(exchange) == null) { redirect(exchange, "/oauth/start"); return; }
        send(exchange, 200, "<html lang='tr'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Takım</title></head><body><h1>Takım</h1><a href='/players'>Oyuncular / Kadro</a></body></html>", "text/html; charset=UTF-8");
    }

    private static void playersPage(HttpExchange exchange) throws IOException {
        if (accessToken(exchange) == null) { redirect(exchange, "/oauth/start"); return; }
        String html = """
                <!doctype html><html lang="tr"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>HO - Kadro</title>
                <style>
                body{font-family:system-ui,sans-serif;margin:0;padding:18px;background:#f5f7fb;color:#18202a}
                main{max-width:1400px;margin:auto;background:#fff;padding:18px;border-radius:14px;box-shadow:0 6px 24px rgba(0,0,0,.07)}
                h1{font-size:22px;margin:0 0 10px}.toolbar{display:flex;gap:8px;align-items:center;margin:10px 0}.toolbar input{flex:1;padding:8px;border:1px solid #ccd3dc;border-radius:8px}
                .wrap{overflow:auto}.status{font-size:12px;color:#667085;margin:6px 0}table{border-collapse:collapse;width:100%;font-size:11px;white-space:nowrap}th,td{padding:5px 7px;border-bottom:1px solid #e7e9ee;text-align:left}th{position:sticky;top:0;background:#f0f3f7;cursor:pointer}.num{text-align:right}.inj{color:#b42318;font-weight:600}.ok{color:#027a48}
                </style></head><body><main><h1>Oyuncular / Kadro</h1><div class="toolbar"><input id="q" placeholder="Oyuncu ara..."><span id="count" class="status"></span></div><div id="status" class="status">CHPP'den kadro alınıyor...</div><div class="wrap"><table><thead><tr>
                <th>#</th><th>Oyuncu</th><th>Yaş</th><th>TSI</th><th>Form</th><th>Kond.</th><th>GK</th><th>DEF</th><th>PM</th><th>PAS</th><th>KAN</th><th>GOL</th><th>SP</th><th>Tecr.</th><th>Lid.</th><th>Maaş</th><th>Uzmanlık</th><th>Sak.</th><th>Kart</th><th>Transfer</th>
                </tr></thead><tbody id="players"></tbody></table></div></main>
                <script>
                const q=document.getElementById('q'), body=document.getElementById('players'), status=document.getElementById('status'), count=document.getElementById('count'); let data=[];
                q.addEventListener('input',render);
                fetch('/api/players').then(async r=>{const x=await r.json();if(!r.ok)throw new Error(x.error||'Hata');return x}).then(x=>{data=x.players||[];status.textContent='Kadro yüklendi';status.className='status ok';render();}).catch(e=>{status.textContent=e.message;status.className='status inj'});
                function esc(s){return String(s??'').replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',"'":'&#39;'}[c]));}
                function render(){const s=q.value.toLowerCase();const rows=data.filter(p=>(p.name||'').toLowerCase().includes(s));count.textContent=rows.length+' / '+data.length;body.innerHTML=rows.map(p=>`<tr><td class='num'>${esc(p.number)}</td><td><b>${esc(p.name)}</b></td><td>${esc(p.age)}${p.ageDays!==''?'/'+esc(p.ageDays):''}</td><td class='num'>${esc(p.tsi)}</td><td class='num'>${esc(p.form)}</td><td class='num'>${esc(p.stamina)}</td><td class='num'>${esc(p.keeper)}</td><td class='num'>${esc(p.defender)}</td><td class='num'>${esc(p.playmaker)}</td><td class='num'>${esc(p.passing)}</td><td class='num'>${esc(p.winger)}</td><td class='num'>${esc(p.scorer)}</td><td class='num'>${esc(p.setPieces)}</td><td class='num'>${esc(p.experience)}</td><td class='num'>${esc(p.leadership)}</td><td class='num'>${esc(p.salary)}</td><td>${esc(p.specialty)}</td><td class='num ${Number(p.injuryLevel)>=0?'inj':''}'>${esc(p.injuryLevel)}</td><td class='num'>${esc(p.cards)}</td><td>${p.transferListed==='1'?'Evet':''}</td></tr>`).join('');}
                </script></body></html>
                """;
        send(exchange, 200, html, "text/html; charset=UTF-8");
    }

    private static void playersApi(HttpExchange exchange) throws IOException {
        try {
            OAuth1AccessToken token = accessToken(exchange);
            if (token == null) { send(exchange, 401, "{\"error\":\"CHPP bağlantısı yok\"}", "application/json; charset=UTF-8"); return; }
            OAuthRequest req = new OAuthRequest(Verb.GET, PLAYERS_URL);
            OAuth10aService service = oauthService();
            service.signRequest(token, req);
            Response response = service.execute(req);
            if (!response.isSuccessful()) { send(exchange, 502, "{\"error\":\"CHPP API HTTP " + response.getCode() + "\"}", "application/json; charset=UTF-8"); return; }
            Document doc = secureXml(response.getBody());
            NodeList nodes = doc.getElementsByTagName("Player");
            StringBuilder json = new StringBuilder("{\"players\":[");
            for (int i=0;i<nodes.getLength();i++) {
                Element p=(Element)nodes.item(i);
                if(i>0)json.append(',');
                json.append('{')
                    .append("\"id\":\"").append(j(child(p,"PlayerID"))).append("\",")
                    .append("\"name\":\"").append(j(child(p,"PlayerName"))).append("\",")
                    .append("\"number\":\"").append(j(child(p,"PlayerNumber"))).append("\",")
                    .append("\"age\":\"").append(j(child(p,"Age"))).append("\",")
                    .append("\"ageDays\":\"").append(j(child(p,"AgeDays"))).append("\",")
                    .append("\"tsi\":\"").append(j(child(p,"TSI"))).append("\",")
                    .append("\"form\":\"").append(j(child(p,"PlayerForm"))).append("\",")
                    .append("\"stamina\":\"").append(j(child(p,"StaminaSkill"))).append("\",")
                    .append("\"keeper\":\"").append(j(child(p,"KeeperSkill"))).append("\",")
                    .append("\"defender\":\"").append(j(child(p,"DefenderSkill"))).append("\",")
                    .append("\"playmaker\":\"").append(j(child(p,"PlaymakerSkill"))).append("\",")
                    .append("\"passing\":\"").append(j(child(p,"PassingSkill"))).append("\",")
                    .append("\"winger\":\"").append(j(child(p,"WingerSkill"))).append("\",")
                    .append("\"scorer\":\"").append(j(child(p,"ScorerSkill"))).append("\",")
                    .append("\"setPieces\":\"").append(j(child(p,"SetPiecesSkill"))).append("\",")
                    .append("\"experience\":\"").append(j(child(p,"Experience"))).append("\",")
                    .append("\"leadership\":\"").append(j(child(p,"Leadership"))).append("\",")
                    .append("\"salary\":\"").append(j(child(p,"Salary"))).append("\",")
                    .append("\"specialty\":\"").append(j(specialty(child(p,"Specialty")))).append("\",")
                    .append("\"injuryLevel\":\"").append(j(child(p,"InjuryLevel"))).append("\",")
                    .append("\"cards\":\"").append(j(child(p,"Cards"))).append("\",")
                    .append("\"transferListed\":\"").append(j(child(p,"TransferListed"))).append('}') ;
            }
            json.append("]}");
            send(exchange,200,json.toString(),"application/json; charset=UTF-8");
        } catch(Exception e) { e.printStackTrace(); send(exchange,502,"{\"error\":\"Kadro alınamadı: " + j(e.getMessage()) + "\"}","application/json; charset=UTF-8"); }
    }

    private static Document secureXml(String xml) throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setXIncludeAware(false); f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }
    private static String child(Element p,String tag){NodeList n=p.getElementsByTagName(tag);return n.getLength()==0?"":n.item(0).getTextContent();}
    private static String specialty(String v){return switch(v){case "1"->"Teknik";case "2"->"Hızlı";case "3"->"Güçlü";case "4"->"Öngörülemez";case "5"->"Kafa uzmanı";default->"";};}
    private static String j(String v){if(v==null)return "";return v.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n");}
    private static Map<String,String> query(URI u){Map<String,String> m=new HashMap<>();if(u.getRawQuery()==null)return m;for(String s:u.getRawQuery().split("&")){String[]p=s.split("=",2);m.put(java.net.URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length>1?java.net.URLDecoder.decode(p[1],StandardCharsets.UTF_8):"");}return m;}
    private static String cookie(HttpExchange e,String name){String h=e.getRequestHeaders().getFirst("Cookie");if(h==null)return null;for(String c:h.split(";")){String[]p=c.trim().split("=",2);if(p.length==2&&p[0].equals(name))return p[1];}return null;}
    private static void redirect(HttpExchange e,String loc)throws IOException{e.getResponseHeaders().set("Location",loc);e.sendResponseHeaders(302,-1);e.close();}
    private static void send(HttpExchange e,int status,String body,String type)throws IOException{byte[]b=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type",type);e.sendResponseHeaders(status,b.length);try(OutputStream o=e.getResponseBody()){o.write(b);}}
}
