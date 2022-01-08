package nl.bassages.np30.service;

import nl.bassages.np30.domain.Np30ControllerUncheckedException;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static java.lang.String.format;

@Service
public class NavigatorService {
    private static final String NAVIGATOR_NAME = "04dd4eb4-f24f-4f34-9650-96981a55b848_NP";

    private static final String IS_REGISTERED_NAVIGATORNAME_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                <s:Body>
                    <u:IsRegisteredNavigatorName xmlns:u="urn:UuVol-com:service:UuVolControl:5">
                        <NavigatorName>%s</NavigatorName>
                    </u:IsRegisteredNavigatorName>
                </s:Body>
            </s:Envelope>""";

    private static final String REGISTER_NAVIGATORNAME_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                <s:Body>
                    <u:RegisterNamedNavigator xmlns:u="urn:UuVol-com:service:UuVolControl:5">
                        <NewNavigatorName>%s</NewNavigatorName>
                    </u:RegisterNamedNavigator>
                </s:Body>
            </s:Envelope>""";

    private final SoapActionExcecutor soapActionExcecutor;

    public NavigatorService(SoapActionExcecutor soapActionExcecutor) {
        this.soapActionExcecutor = soapActionExcecutor;
    }

    public String registerNavigatorWhenNotAlreadyDone() {
        String isRegisteredNavigatorNameResponse = isRegisteredNavigatorName();
        return XmlUtil.getElementContent(isRegisteredNavigatorNameResponse, "RetNavigatorId")
                .orElseGet(() -> {
                    String registerNavigatorNameResponse = registerNavigatorName();
                    final Optional<String> retNavigatorId = XmlUtil.getElementContent(registerNavigatorNameResponse, "RetNavigatorId");
                    return retNavigatorId.orElseThrow(() -> new Np30ControllerUncheckedException("Failed to determine navigatorId. SoapResponse: " + isRegisteredNavigatorNameResponse));
                });
    }

    private String isRegisteredNavigatorName() {
        return soapActionExcecutor.execute("/RecivaRadio/invoke.xml", "urn:UuVol-com:service:UuVolControl:5#IsRegisteredNavigatorName",
                format(IS_REGISTERED_NAVIGATORNAME_TEMPLATE, NAVIGATOR_NAME));
    }

    private String registerNavigatorName() {
        return soapActionExcecutor.execute("/RecivaRadio/invoke.xml", "urn:UuVol-com:service:UuVolControl:5#RegisterNamedNavigator",
                format(REGISTER_NAVIGATORNAME_TEMPLATE, NAVIGATOR_NAME));
    }

}
