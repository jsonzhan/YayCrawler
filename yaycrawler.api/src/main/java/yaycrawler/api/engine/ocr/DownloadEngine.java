package yaycrawler.api.engine.ocr;

import com.google.common.io.Files;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import yaycrawler.api.engine.Engine;
import yaycrawler.common.model.BinaryDto;
import yaycrawler.common.model.EngineResult;
import yaycrawler.common.utils.HttpUtil;
import yaycrawler.common.utils.OCRHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * @author bill
 * @create 2017-08-29 11:35
 * @desc 验证码识别引擎
 **/

@Service("downloadEngine")
public class DownloadEngine implements Engine<BinaryDto> {

    @Override
    public EngineResult execute(BinaryDto info) {
        EngineResult engineResult = executeEngineWithFailover(info);
        return engineResult;
    }

    @Override
    public List<EngineResult> execute(List<BinaryDto> info) {
        return null;
    }

    public EngineResult executeEngineWithFailover(BinaryDto info) {
        EngineResult engineResult = new EngineResult();
        ArrayList<Header> headerList = new ArrayList<>();
        headerList.add(new BasicHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.101 Safari/537.36"));
        headerList.add(new BasicHeader("Cookie",info.getCookie() != null ?info.getCookie(): ""));
        HttpUtil httpUtil = HttpUtil.getInstance();
        try {
            while (true) {
                HttpResponse response = httpUtil.doGet(info.getImg(), null, headerList);
                if (response.getStatusLine().getStatusCode() != 200) {
                    continue;
                }
                Header[] headers = response.getHeaders("Set-Cookie");
                for(Header header:headers) {
                    headerList.add(new BasicHeader("Cookie",header.getValue()));
                }
                if (headers.length >= 1) {
                    engineResult.setHeaders(headerList);
                }
                byte[] bytes = EntityUtils.toByteArray(response.getEntity());
                String documentName = DigestUtils.sha1Hex(info.getImg()) + ".jpg";
                File document = new File(info.getSrc() + "/" + documentName);
                Files.createParentDirs(document);
                Files.write(bytes, document);
                engineResult.setResult(documentName);
                engineResult.setStatus(Boolean.TRUE);
                break;
            }
        }catch (Exception e) {
            engineResult = failureCallback(info,e);
            e.printStackTrace();
        }
        return engineResult;
    }
}