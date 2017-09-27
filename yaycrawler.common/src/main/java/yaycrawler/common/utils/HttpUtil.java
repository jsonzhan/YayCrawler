package yaycrawler.common.utils;

import com.alibaba.fastjson.JSON;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.cookie.Cookie;
import org.apache.http.cookie.CookieOrigin;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.impl.cookie.BestMatchSpec;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.Map.Entry;


public class HttpUtil {
    private static final Logger logger = LoggerFactory.getLogger(HttpUtil.class);
    private static int bufferSize = 1024;
    private static volatile HttpUtil instance;
    private ConnectionConfig connConfig;
    private SocketConfig socketConfig;
    private ConnectionSocketFactory plainSF;
    private KeyStore trustStore;
    private SSLContext sslContext;
    private LayeredConnectionSocketFactory sslSF;
    private Registry<ConnectionSocketFactory> registry;
    private PoolingHttpClientConnectionManager connManager;
    private volatile CloseableHttpClient client;
    private volatile BasicCookieStore cookieStore;
    public static final String defaultEncoding = "utf-8";


    private HttpUtil() {
        //设置连接参数
        connConfig = ConnectionConfig.custom().setCharset(Charset.forName(defaultEncoding)).build();
        socketConfig = SocketConfig.custom().setSoTimeout(5000).build();
        RegistryBuilder<ConnectionSocketFactory> registryBuilder = RegistryBuilder.<ConnectionSocketFactory>create();
        plainSF = new PlainConnectionSocketFactory();
        registryBuilder.register("http", plainSF);
        //指定信任密钥存储对象和连接套接字工厂
        try {
            trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            sslContext = SSLContexts.custom().useTLS().loadTrustMaterial(trustStore, new AnyTrustStrategy()).build();
            sslSF = new SSLConnectionSocketFactory(sslContext, SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
            registryBuilder.register("https", sslSF);
        } catch (KeyStoreException | KeyManagementException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        registry = registryBuilder.build();
        //设置连接管理器
        connManager = new PoolingHttpClientConnectionManager(registry);
        connManager.setDefaultConnectionConfig(connConfig);
        connManager.setDefaultSocketConfig(socketConfig);
        connManager.setMaxTotal(400);
        connManager.setDefaultMaxPerRoute(connManager.getMaxTotal());
        //指定cookie存储对象
        cookieStore = new BasicCookieStore();
        //构建客户端
        client = HttpClientBuilder.create().setDefaultCookieStore(cookieStore).setConnectionManager(connManager).build();
    }

    public static HttpUtil getInstance() {
        synchronized (HttpUtil.class) {
            if (HttpUtil.instance == null) {
                instance = new HttpUtil();
            }
            return instance;
        }
    }

    /**
     * 发送请求获取响应中的cookie，如： Set-Cookie:WEB=97324480; path=/
     *
     * @param cookieUrl
     * @param headers
     * @return
     */
    public List<Cookie> doGetCookies(String cookieUrl, ArrayList<Header> headers) {
        try {
            return getCookiesFromResponse(cookieUrl, doGet(cookieUrl, null, headers));
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
            return null;
        }
    }

    public List<Cookie> doPostCookies(String cookieUrl, Map<String, String> formParams, List<Header> headerList) {
        try {
            return getCookiesFromResponse(cookieUrl, doPost(cookieUrl, null, formParams, headerList));
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
            return null;
        }
    }

    /**
     * 基本的Get请求
     *
     * @param url         请求url
     * @param queryParams 请求头的查询参数
     * @return
     * @throws URISyntaxException
     * @throws IOException
     * @throws ClientProtocolException
     */
    public HttpResponse doGet(String url, Map<String, String> queryParams, List<Header> headerList) throws URISyntaxException, IOException {
        HttpGet gm = new HttpGet();
        RequestConfig requestConfig = getRequestConfig();
        gm.setConfig(requestConfig);
        setHeaders(headerList, gm);
        URIBuilder builder = new URIBuilder(url);
        //填入查询参数
        if (queryParams != null && !queryParams.isEmpty()) {
            builder.setParameters(HttpUtil.paramsConverter(queryParams));
        }
        gm.setURI(builder.build());
        HttpResponse response = execute(gm);
        //保存Cookies
//        saveCookies(url, response);
        return response;
    }

    public HttpResponse doGet(String url, Map<String, String> queryParams) throws IOException, URISyntaxException {
        return doGet(url, queryParams, null);
    }

    public InputStream doGetForStream(String url, Map<String, String> queryParams) throws URISyntaxException, IOException {
        HttpResponse response = this.doGet(url, queryParams);
        return response != null ? response.getEntity().getContent() : null;
    }


    public Map<String, Object> doGetForMap(String url, Map<String, String> queryParams, List<Header> headerList) throws IOException, URISyntaxException {
        HttpResponse response = doGet(url, queryParams, headerList);
        return readResponseToMap(response);
    }

    public Map<String, Object> doPostForMap(String url, Map<String, String> queryParams, Map<String, String> formParams, List<Header> headerList) throws IOException, URISyntaxException {
        HttpResponse response = doPost(url, queryParams, formParams, headerList);
        return readResponseToMap(response);
    }

    public InputStream doPostForStream(String url, Map<String, String> queryParams) throws URISyntaxException, ClientProtocolException, IOException {
        HttpResponse response = this.doPost(url, queryParams, null, null);
        return response != null ? response.getEntity().getContent() : null;
    }

    public InputStream doPostForStream(String url, Map<String, String> queryParams, List<Header> headers) throws URISyntaxException, ClientProtocolException, IOException {
        HttpResponse response = this.doPost(url, queryParams, null, headers);
        return response != null ? response.getEntity().getContent() : null;
    }

    public String doPostForString(String url, Map<String, String> queryParams) throws URISyntaxException, ClientProtocolException, IOException {
        return HttpUtil.readStream(this.doPostForStream(url, queryParams), null);
    }

    public String doPostForString(String url, Map<String, String> queryParams, List<Header> headers) throws URISyntaxException, ClientProtocolException, IOException {
        return HttpUtil.readStream(this.doPostForStream(url, queryParams, headers), null);
    }

    public InputStream doPostForStream(String url, Map<String, String> queryParams, Map<String, String> formParams) throws URISyntaxException, ClientProtocolException, IOException {
        HttpResponse response = this.doPost(url, queryParams, formParams, null);
        return response != null ? response.getEntity().getContent() : null;
    }

    public String doPostRetString(String url, Map<String, String> queryParams, Map<String, String> formParams) throws URISyntaxException, ClientProtocolException, IOException {
        return HttpUtil.readStream(this.doPostForStream(url, queryParams, formParams), null);
    }

    /**
     * 基本的Post请求
     *
     * @param url         请求url
     * @param queryParams 请求头的查询参数
     * @param formParams  post表单的参数
     * @return
     * @throws URISyntaxException
     * @throws IOException
     * @throws ClientProtocolException
     */
    public HttpResponse doPost(String url, Map<String, String> queryParams, Map<String, String> formParams, List<Header> headerList) throws URISyntaxException, IOException {
        HttpPost pm = new HttpPost();
        URIBuilder builder = new URIBuilder(url);
        setHeaders(headerList, pm);
        //填入查询参数
        if (queryParams != null && !queryParams.isEmpty()) {
            builder.setParameters(HttpUtil.paramsConverter(queryParams));
        }
        pm.setURI(builder.build());
        //填入表单参数
        if (formParams != null && !formParams.isEmpty()) {
            pm.setEntity(new UrlEncodedFormEntity(HttpUtil.paramsConverter(formParams)));
        }
        RequestConfig requestConfig = getRequestConfig();
        pm.setConfig(requestConfig);
        HttpResponse response = execute(pm);
        //保存Cookies
//        saveCookies(url, response);
        return response;
    }


    /**
     * 获取当前Http客户端状态中的Cookie
     *
     * @param domain    作用域
     * @param port      端口 传null 默认80
     * @param path      Cookie路径 传null 默认"/"
     * @param useSecure Cookie是否采用安全机制 传null 默认false
     * @return
     */
    public Map<String, Cookie> getCookie(String domain, Integer port, String path, Boolean useSecure) {
        if (domain == null) {
            return null;
        }
        if (port == null) {
            port = 80;
        }
        if (path == null) {
            path = "/";
        }
        if (useSecure == null) {
            useSecure = false;
        }
        List<Cookie> cookies = cookieStore.getCookies();
        if (cookies == null || cookies.isEmpty()) {
            return null;
        }

        CookieOrigin origin = new CookieOrigin(domain, port, path, useSecure);
        BestMatchSpec cookieSpec = new BestMatchSpec();
        Map<String, Cookie> retVal = new HashMap<String, Cookie>();
        for (Cookie cookie : cookies) {
            if (cookieSpec.match(cookie, origin)) {
                retVal.put(cookie.getName(), cookie);
            }
        }
        return retVal;
    }

    private RequestConfig getRequestConfig() {
//        HttpHost proxy = new HttpHost("127.0.0.1", 8888);
        RequestConfig.Builder build = RequestConfig.custom().setCookieSpec(CookieSpecs.IGNORE_COOKIES)
                //.setProxy(proxy)
                .setConnectTimeout(30000).setConnectionRequestTimeout(30000)
                .setSocketTimeout(30000);
        return build.build();
    }

    private void setHeaders(List<Header> headerList, HttpRequestBase request) {
//        if (headerList != null) {
//            headerList.add(new BasicHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.101 Safari/537.36"));
//        }
        if (headerList != null && headerList.size() > 0) {
            Header[] headerArray = new Header[headerList.size()];
            for (int i = 0; i < headerList.size(); i++) {
                headerArray[i] = headerList.get(i);
            }
            request.setHeaders(headerArray);
        }
    }

    private HttpResponse execute(HttpRequestBase request) throws IOException {
        try {
            HttpResponse response = client.execute(request);
            if (response == null)
                logger.error("no exception,but response is null!");
            return response;
        } catch (IOException e) {
            request.abort();
            throw e;
        }
    }

    private static List<NameValuePair> paramsConverter(Map<String, String> params) {
        List<NameValuePair> nvps = new LinkedList<NameValuePair>();
        Set<Entry<String, String>> paramsSet = params.entrySet();
        for (Entry<String, String> paramEntry : paramsSet) {
            nvps.add(new BasicNameValuePair(paramEntry.getKey(), paramEntry.getValue()));
        }
        return nvps;
    }

    private static String readStream(InputStream in, String encoding) {
        if (in == null) {
            return null;
        }
        InputStreamReader inReader = null;
        try {
            if (encoding == null) {
                inReader = new InputStreamReader(in, defaultEncoding);
            } else {
                inReader = new InputStreamReader(in, encoding);
            }
            char[] buffer = new char[bufferSize];
            int readLen = 0;
            StringBuffer sb = new StringBuffer();
            while ((readLen = inReader.read(buffer)) != -1) {
                sb.append(buffer, 0, readLen);
            }
//            inReader.close();
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (inReader != null) try {
                inReader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }


    private static List<Cookie> getCookiesFromResponse(String url, HttpResponse response) {
        Header[] cookieHeaders = response.getHeaders("Set-Cookie");
        if (cookieHeaders == null || cookieHeaders.length == 0) return null;

        List<Cookie> cookieList = new ArrayList<>();
        String domain = UrlUtils.getDomain(url);
        String key, value, expires;
        String path = "/";
        for (int i = 0; i < cookieHeaders.length; i++) {
            String valueStr = cookieHeaders[i].getValue();
            String[] valueArray = valueStr.split(";");
            String[] pairArray = valueArray[0].split("=");
            key = pairArray[0];
            value = pairArray[1];
            for (int j = 1; j < pairArray.length; j++) {
                String pair = pairArray[j];
                if (pair.contains("Expires=")) {
                    pairArray = pair.split("=");
                    expires = pairArray[1];

                } else if (pair.contains("Path=")) {
                    pairArray = pair.split("=");
                    path = pairArray[1];
                }
            }
            BasicClientCookie cookie = new BasicClientCookie(key, value);
            cookie.setDomain(domain);
            cookie.setPath(path);
            cookie.setSecure(false);
            cookieList.add(cookie);
        }
        return cookieList;
    }

    /**
     * 保存response中返回的cookie
     *
     * @param url
     * @param response
     */
    private void saveCookies(String url, HttpResponse response) {
        List<Cookie> cookieList = getCookiesFromResponse(url, response);
        if (cookieList != null)
            for (Cookie cookie : cookieList) {
                cookieStore.addCookie(cookie);
            }
    }

    /**
     * 从响应中读取数据并转换为Map
     *
     * @param response
     * @return
     * @throws IOException
     */
    private Map readResponseToMap(HttpResponse response) throws IOException {
        if (200 == response.getStatusLine().getStatusCode()) {
            String data = HttpUtil.readStream(response.getEntity().getContent(), "utf-8");
            return JSON.parseObject(data, Map.class);
        } else throw new IOException("请求出现错误：" + response.getStatusLine().getStatusCode());
    }

//    private  String getDomain(String url) {
//        if (StringUtils.isBlank(url)) return null;
//        String domain = "";
//        Pattern p = Pattern.compile("(?<=http://|\\.)[^.]*?\\.(com|cn|net|org|biz|info|cc|tv)", Pattern.CASE_INSENSITIVE);
//        Matcher matcher = p.matcher(url);
//        if (matcher.find())
//            domain = matcher.group();
//        return domain;
//    }
//    /**
//     * 设置单个Cookie
//     *
//     * @param key       Cookie键
//     * @param value     Cookie值
//     * @param domain    作用域 不可为空
//     * @param path      路径 传null默认为"/"
//     * @param useSecure 是否使用安全机制 传null 默认为false
//     * @return 是否成功设置cookie
//     */
//    private boolean setCookie(String key, String value, String domain, String path, Boolean useSecure) {
//        Map<String, String> cookies = new HashMap<String, String>();
//        cookies.put(key, value);
//        return setCookie(cookies, domain, path, useSecure);
//    }


}
