package nl.bassages.np30.service;

import org.apache.commons.text.StringEscapeUtils;

import java.util.Optional;
import java.util.regex.Pattern;

public class XmlUtil {

    private XmlUtil() {
        // Hide utility constructor
    }

    public static Optional<String> getElementContent(String soapResponse, String elementName) {
        var pattern = Pattern.compile(".+<" + elementName + ">(.+)</" + elementName + ">.+", Pattern.DOTALL);
        var matcher = pattern.matcher(soapResponse);
        if (matcher.matches()) {
            return Optional.ofNullable(StringEscapeUtils.unescapeXml(matcher.group(1)));
        }
        return Optional.empty();
    }

}
