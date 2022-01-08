package nl.bassages.np30.service;

import nl.bassages.np30.domain.Np30ControllerUncheckedException;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class SoapActionExcecutor {
    private static final Logger LOG = LoggerFactory.getLogger(SoapActionExcecutor.class);
    private static final String DOUBLE_QUOTE = "\"";

    @Value("${np30.api.url}")
    private String np30BaseUrl;

    public String execute(String port, String soapAction, String body) {
        LOG.debug("Request body: {}", body);

        try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
            var httpPost = new HttpPost(np30BaseUrl + port);
            httpPost.setHeader("Content-Type", "text/xml; charset=\"utf-8\"");
            httpPost.setHeader("SOAPAction", DOUBLE_QUOTE + soapAction + DOUBLE_QUOTE);
            httpPost.setEntity(new StringEntity(body));

            String responseString;
            try (CloseableHttpResponse response = httpclient.execute(httpPost)) {
                HttpEntity entity = response.getEntity();
                responseString = EntityUtils.toString(entity);
            }
            return responseString;
        } catch (IOException e) {
            throw new Np30ControllerUncheckedException(e);
        }
    }
}
