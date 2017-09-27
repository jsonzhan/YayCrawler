package yaycrawler.spider.downloader;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import us.codecraft.webmagic.Page;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Site;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.downloader.AbstractDownloader;
import us.codecraft.webmagic.selector.PlainText;
import us.codecraft.webmagic.selector.Selectable;
import us.codecraft.webmagic.utils.HttpClientUtils;
import us.codecraft.webmagic.utils.UrlUtils;
import yaycrawler.api.engine.Engine;
import yaycrawler.api.process.SpringContextUtil;
import yaycrawler.api.resolver.CrawlerExpressionResolver;
import yaycrawler.common.model.CrawlerCookie;
import yaycrawler.common.model.EngineResult;
import yaycrawler.common.model.LoginParam;
import yaycrawler.common.model.PhantomCookie;
import yaycrawler.common.utils.HttpUtil;
import yaycrawler.dao.domain.PageInfo;
import yaycrawler.dao.domain.PageSite;
import yaycrawler.dao.domain.SiteCookie;
import yaycrawler.dao.service.PageCookieService;
import yaycrawler.dao.service.PageParserRuleService;
import yaycrawler.monitor.captcha.CaptchaIdentificationProxy;
import yaycrawler.monitor.login.AutoLoginProxy;
import yaycrawler.spider.cookie.DynamicCookieManager;
import yaycrawler.spider.listener.IPageParseListener;
import yaycrawler.spider.processor.GenericPageProcessor;
import yaycrawler.spider.service.PageSiteService;
import yaycrawler.spider.utils.RequestHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Created by ucs_yuananyun on 2016/5/27.
 */
@Component
public class GenericCrawlerDownLoader extends AbstractDownloader {

    private static final Logger logger  = LoggerFactory.getLogger(GenericCrawlerDownLoader.class);
    private static String DEFAULT_PAGE_SELECTOR = "page";
    @Autowired
    private PageParserRuleService pageParserRuleService;
    @Autowired
    private PageCookieService pageCookieService;
    @Autowired
    private DynamicCookieManager dynamicCookieManager;
    @Autowired
    private PageSiteService pageSiteService;
    @Autowired
    private AutoLoginProxy autoLoginProxy;

    @Autowired
    private CaptchaIdentificationProxy captchaIdentificationProxy;

//    @Autowired
//    private GenericPageProcessor genericPageProcessor;
    private CrawlerHttpClientDownloader httpClientDownloader;
    private PhantomJsMockDonwnloader mockDonwnloader;

    private static Pattern UNICODE_PATTERN = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    public GenericCrawlerDownLoader() {
        httpClientDownloader = new CrawlerHttpClientDownloader();
        mockDonwnloader = new PhantomJsMockDonwnloader();
    }

    private static Pattern redirectPattern = Pattern.compile("<script.*(?s)setInterval.*location.href\\s*=.*(?s).*</script>");

    @Override
    public Page download(Request request, Task task) {
        int i = 0;
        Page page = Page.fail();
        PageInfo pageInfo = pageParserRuleService.findOnePageInfoByRgx(request.getUrl());
        boolean doRecovery = false;
        try {
            Site site = task.getSite();
            while (i < 5 && !pageValidated(page, pageInfo.getPageValidationRule())) {

                if (pageInfo == null && request.getExtra("$pageInfo") != null) {
                    pageInfo = (PageInfo) request.getExtra("$pageInfo");
                }
                boolean isJsRendering = pageInfo != null && "1".equals(pageInfo.getIsJsRendering());
                String pageUrl = request.getUrl();
                String loginName = request.getExtra("loginName") != null ? request.getExtra("loginName").toString() : RequestHelper.getParam(request.getUrl(), "loginName");

                SiteCookie siteCookie = pageCookieService.getCookieByUrl(pageUrl, loginName);
                if ((i >= 1 && StringUtils.isNotEmpty(page.getRawText())) || (i > 2 && page.getRawText() == null)) {
                    siteCookie = null;
                }
                String cookie = "";
                int j = 0;
                while (StringUtils.isNotEmpty(loginName) && siteCookie == null && j <= 5) {
                    EngineResult engineResult = doAutomaticRecovery(Page.fail(), request, request.getUrl());
                    if (engineResult != null && engineResult.getStatus()) {
                        byte[] bytes = IOUtils.toByteArray(engineResult.getResult());
                        page.setBytes(bytes);
                        if (!request.isBinaryContent()) {
                            page.setCharset(site.getCharset());
                            String content = new String(bytes, site.getCharset());
                            //unicode编码处理
                            if (UNICODE_PATTERN.matcher(content).find())
                                content = StringEscapeUtils.unescapeJava(content.replace("\"", "\\\""));
                            page.setRawText(content);
                        }
                        page.setUrl(new PlainText(request.getUrl()));
                        page.setRequest(request);
                        doRecovery = engineResult.getStatus();
                        break;
                    }
                    j++;
                }
                if(doRecovery) {
                    continue;
                }
                if (siteCookie != null) {
                    cookie = siteCookie.getCookie();
//                String cookieId = siteCookie.getId();
//                request.putExtra("cookieId", cookieId);
                }
                //获取动态的cookies
                List<CrawlerCookie> dynamicCookieList = dynamicCookieManager.getCookiesByDomain(UrlUtils.getDomain(pageUrl));
                if (dynamicCookieList != null) {
                    cookie += ";";
                    for (CrawlerCookie crawlerCookie : dynamicCookieList) {
                        cookie += String.format("%s=%s", crawlerCookie.getName(), crawlerCookie.getValue());
                    }
                }
//            request = RequestHelper.createRequest(request.getUrl(),request.getMethod(),request.getExtras());
                page = !isJsRendering ? httpClientDownloader.download(request, task, cookie) : mockDonwnloader.download(request, task, cookie);
                if (!isJsRendering && (!"post".equalsIgnoreCase(request.getMethod()) && page != null) && page.getRawText() != null && redirectPattern.matcher(page.getRawText()).find())
                    page = mockDonwnloader.download(request, task, cookie);
                i++;
            }
        } catch (Exception e) {
            logger.error("pageUrl {},Exception",request.getUrl(),e);
            page = Page.fail();
        }
        if(page != null && page.getRawText() == null)
            return null;

        return page;
    }

    @Override
    public void setThread(int threadNum) {
        httpClientDownloader.setThread(threadNum);
        mockDonwnloader.setThread(threadNum);
    }

    /**
     * 验证是否正确的页面
     *
     * @param page
     * @param pageValidationExpression
     * @return
     */
    public boolean pageValidated(Page page, String pageValidationExpression) {
        if (StringUtils.isEmpty(pageValidationExpression)) return true;
        if(page.getRawText() == null) return false;
        Request request = page.getRequest();
        Selectable context = getPageRegionContext(page, request, pageValidationExpression);
        if (context == null) return false;
        if (context instanceof Selectable)
            return ((Selectable) context).match();
        else
            return StringUtils.isNotEmpty(String.valueOf(context));

    }

    /**
     * 页面自动恢复
     *
     * @param page
     * @param pageRequest
     * @param pageUrl
     */
    public EngineResult doAutomaticRecovery(Page page, Request pageRequest, String pageUrl) {
        PageSite pageSite = pageSiteService.getPageSiteByUrl(pageUrl);
        EngineResult engineResult = null;
        if (pageSite != null) {
            String login = pageSite.getLoginEngine();
            String encrypt = pageSite.getEncryptEngine();
            int status = pageSite.getStatus();
            String loginJudgeExpression = pageSite.getLoginJudgeExpression();
            String captchaJudgeExpression = pageSite.getCaptchaJudgeExpression();
            String loginJsFileName = pageSite.getLoginJsFileName();
            String captchaJsFileName = pageSite.getCaptchaJsFileName();
            String param = pageSite.getLoginParam() != null ? pageSite.getLoginParam() : "";
            String oldCookieId = String.valueOf(pageRequest.getExtra("cookieId"));
            if (status == 0) {
                if (StringUtils.isEmpty(encrypt))
                    encrypt = "encryptEngine";
                if (StringUtils.isEmpty(login))
                    login = "loginEngine";
                Engine loginEngine = (Engine) SpringContextUtil.getBean(login);
                Engine encryptEngine = (Engine) SpringContextUtil.getBean(encrypt);
                Map paramData = null;
                if (StringUtils.isEmpty(param)) {
                    paramData = Maps.newHashMap();
                } else {
                    paramData = JSON.parseObject(param, Map.class);
                }
                if (pageRequest.getExtras() != null && pageRequest.getExtras().size() > 0) {
                    paramData.putAll(pageRequest.getExtras());
                    paramData.remove("$pageInfo");
                } else {
                    paramData.put("loginName", RequestHelper.getParam(pageRequest.getUrl(),"loginName"));
                    paramData.put("loginPassword", RequestHelper.getParam(pageRequest.getUrl(),"loginPassword"));
                }
                engineResult = encryptEngine.execute(paramData);
                if(engineResult.getStatus() == null || !engineResult.getStatus())
                    return engineResult;
                LoginParam loginParam = engineResult.getLoginParam();
                loginParam.setUrl(pageRequest.getUrl());
                engineResult = loginEngine.execute(loginParam);
                if (engineResult.getStatus()) {
                    //需要登录了
                    List<PhantomCookie> phantomCookies = new ArrayList<>();
                    engineResult.getHeaders().forEach(header -> {
                        PhantomCookie phantomCookie = new PhantomCookie(header.getName(), header.getValue());
                        phantomCookies.add(phantomCookie);
                    });
                    String loginName = pageRequest.getExtra("loginName") != null? pageRequest.getExtra("loginName").toString():RequestHelper.getParam(pageRequest.getUrl(),"loginName");
                    pageCookieService.deleteCookieBySiteId(pageSite.getId(),loginName);
                    if (pageCookieService.saveCookies(UrlUtils.getDomain(pageUrl), pageSite.getId(), phantomCookies,loginName)) {
                        logger.info("保存新的cookie成功！");
                    } else
                        logger.info("保存新的cookie失败！");
                    //重新加入队列
//                    page.addTargetRequest(pageRequest);
                } else {
                    logger.info("登陆失败{}",JSON.toJSONString(pageRequest));
                }
            } else {
                Selectable judgeContext = StringUtils.isNotBlank(loginJsFileName) ? getPageRegionContext(page, pageRequest, loginJudgeExpression) : null;
                if (judgeContext != null && judgeContext.match()) {
                   engineResult.setStatus(Boolean.TRUE);
                    //需要登录了
                    autoLoginProxy.login(pageUrl, loginJsFileName, page.getRawText(), oldCookieId);
                    //重新加入队列
                    page.addTargetRequest(pageRequest);
                } else {
                    judgeContext = StringUtils.isNotBlank(captchaJsFileName) ? getPageRegionContext(page, pageRequest, captchaJudgeExpression) : null;
                    if (judgeContext != null && judgeContext.match()) {
                        engineResult.setStatus(Boolean.FALSE);
                        //需要刷新验证码了
                        captchaIdentificationProxy.recognition(pageUrl, captchaJsFileName, page.getRawText(), oldCookieId);
                    }
                }
            }

        }
        return engineResult;
    }

    /**
     * 获取一个region的上下文
     *
     * @param page
     * @param request
     * @param regionSelectExpression
     * @return
     */
    public Selectable getPageRegionContext(Page page, Request request, String regionSelectExpression) {
        Selectable context;
        if (StringUtils.isBlank(regionSelectExpression) || DEFAULT_PAGE_SELECTOR.equals(regionSelectExpression))
            context = page.getHtml();
        else if (regionSelectExpression.toLowerCase().contains("getjson()") || regionSelectExpression.toLowerCase().contains("jsonpath"))
            context = CrawlerExpressionResolver.resolve(request, page.getJson(), regionSelectExpression);
        else
            context = CrawlerExpressionResolver.resolve(request, page.getHtml(), regionSelectExpression);
        return context;
    }

}
