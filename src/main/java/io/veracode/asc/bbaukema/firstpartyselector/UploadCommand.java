package io.veracode.asc.bbaukema.firstpartyselector;

import com.veracode.apiwrapper.services.impl.DefaultCredentialsService;
import com.veracode.apiwrapper.wrappers.ResultsAPIWrapper;
import com.veracode.apiwrapper.wrappers.UploadAPIWrapper;
import com.veracode.http.Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.Collectors;

@ShellComponent
public class UploadCommand {
    private static Logger LOG = LoggerFactory
            .getLogger(UploadCommand.class);

    private UploadAPIWrapper uploadWrapper;
    private ResultsAPIWrapper resultsWrapper;

    private String appId;

    public UploadCommand() {
        Optional<Credentials> credentials = DefaultCredentialsService.createInstance().getCredentials();
        if (credentials.isEmpty()) {
            throw new RuntimeException("No credentials present, please ensure credentials are installed");
        }

        uploadWrapper = new UploadAPIWrapper();
        uploadWrapper.setUpApiCredentials(credentials.get());

        resultsWrapper = new ResultsAPIWrapper();
        resultsWrapper.setUpApiCredentials(credentials.get());

        appId = "1530825";
    }

    @ShellMethod(value = "upload")
    public void upload() throws IOException, ParserConfigurationException, SAXException, InterruptedException {
        LOG.info("EXECUTING : upload");

        ArrayList<String> scaThirdPartyFilenames = doScanForThirdpartyFilenames();

        String scanName = "STATIC SCAN - ";
        scanName += new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss").format(new java.util.Date()).toString();
        String buildInfoXml = uploadWrapper.createBuild(appId, scanName);
        LOG.info("Created build: "+ buildInfoXml);

        Document buildInfoDocument = safelyParseXml(buildInfoXml);
        String build_id = buildInfoDocument.getElementsByTagName("build").item(0).getAttributes().getNamedItem("build_id").getNodeValue();
        LOG.info("Build ID: " + build_id);

        String uploadFileXml = uploadWrapper.uploadFile(appId, "src/test/resources/veracode.zip");
        LOG.info("Uploaded file: " + uploadFileXml);

        String prescanXml = uploadWrapper.beginPreScan(appId);
        LOG.info("Prescan started: " + prescanXml);

        String prescanResultsXml = uploadWrapper.getPreScanResults(appId);
        LOG.info("Prescan results: " + prescanResultsXml);

        while (prescanResultsXml.contains("not available")) {
            LOG.info("No prescan results, waiting 15 seconds.");
            Thread.sleep(15000);

            prescanResultsXml = uploadWrapper.getPreScanResults(appId);
            LOG.info("Prescan results: " + prescanResultsXml);
        }

        Document prescanDocument = safelyParseXml(prescanResultsXml);
        NodeList moduleElements = prescanDocument.getElementsByTagName("module");
        ArrayList<String> moduleNames = new ArrayList<String>();
        for (int i=0; i < moduleElements.getLength(); i++) {
            String moduleName   = moduleElements.item(i).getAttributes().getNamedItem("name").getNodeValue();
            String status       = moduleElements.item(i).getAttributes().getNamedItem("status").getNodeValue();
            String isDependency = moduleElements.item(i).getAttributes().getNamedItem("is_dependency").getNodeValue();

            if (!status.equals("OK")) {
                LOG.info("IGNORING " + moduleName + " DUE TO STATUS " + status);
                continue;
            }

            if (isDependency.equals("true")) {
                LOG.info("IGNORING " + moduleName + " AS IT'S A DEPENDENCY");
                continue;
            }

            if (scaThirdPartyFilenames.contains(moduleName)) {
                LOG.info("IGNORING THIRD PARTY MODULE: " + moduleName);
                continue;
            } else {
                LOG.info("ADDING FIRST PARY MODULE: " + moduleName);
            }

            moduleNames.add(moduleName);
        }

        LOG.info("Starting scan with modules: "+ String.join(",", moduleNames));
        uploadWrapper.beginScan(appId, String.join(",", moduleNames), "false");
    }

    private ArrayList<String> doScanForThirdpartyFilenames() throws ParserConfigurationException, IOException, SAXException, InterruptedException {
        String scanName = "SCA SCAN - ";
        scanName += new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss").format(new java.util.Date()).toString();

        String buildInfoXml = uploadWrapper.createBuild(appId, scanName);
        LOG.info("Created build: "+ buildInfoXml);

        Document buildInfoDocument = safelyParseXml(buildInfoXml);
        String build_id = buildInfoDocument.getElementsByTagName("build").item(0).getAttributes().getNamedItem("build_id").getNodeValue();
        LOG.info("Build ID: " + build_id);

        String uploadFileXml = uploadWrapper.uploadFile(appId, "src/test/resources/veracode.zip");
        LOG.info("Uploaded file: " + uploadFileXml);

        String prescanXml = uploadWrapper.beginPreScan(appId);
        LOG.info("Prescan started: " + prescanXml);

        String prescanResultsXml = uploadWrapper.getPreScanResults(appId);
        LOG.info("Prescan results: " + prescanResultsXml);

        while (prescanResultsXml.contains("not available")) {
            LOG.info("No prescan results, waiting 15 seconds.");
            Thread.sleep(15000);

            prescanResultsXml = uploadWrapper.getPreScanResults(appId);
            LOG.info("Prescan results: " + prescanResultsXml);
        }


        String detailedReportXml = resultsWrapper.detailedReport(build_id);
        LOG.info("Detailed report: " + detailedReportXml);

        while (detailedReportXml.contains("No report available")) {
            LOG.info("No report available, waiting 15 seconds.");
            Thread.sleep(15000);

            detailedReportXml = resultsWrapper.detailedReport(build_id);
            LOG.info("Detailed report: " + detailedReportXml);
        }

        Document detailedReportDocument = safelyParseXml(detailedReportXml);
        NodeList componentElements = detailedReportDocument.getElementsByTagName("component");
        ArrayList<String> componentElementFilenames = new ArrayList<String>();
        for (int i=0; i < componentElements.getLength(); i++) {
            componentElementFilenames.add(componentElements.item(i).getAttributes().getNamedItem("file_name").getNodeValue());
        }
        return componentElementFilenames;
    }

    private Document safelyParseXml(String xml) throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        String FEATURE = null;

        // This is the PRIMARY defense. If DTDs (doctypes) are disallowed, almost all
        // XML entity attacks are prevented
        // Xerces 2 only - http://xerces.apache.org/xerces2-j/features.html#disallow-doctype-decl
        FEATURE = "http://apache.org/xml/features/disallow-doctype-decl";
        dbf.setFeature(FEATURE, true);

        // If you can't completely disable DTDs, then at least do the following:
        // Xerces 1 - http://xerces.apache.org/xerces-j/features.html#external-general-entities
        // Xerces 2 - http://xerces.apache.org/xerces2-j/features.html#external-general-entities
        // JDK7+ - http://xml.org/sax/features/external-general-entities
        //This feature has to be used together with the following one, otherwise it will not protect you from XXE for sure
        FEATURE = "http://xml.org/sax/features/external-general-entities";
        dbf.setFeature(FEATURE, false);

        // Xerces 1 - http://xerces.apache.org/xerces-j/features.html#external-parameter-entities
        // Xerces 2 - http://xerces.apache.org/xerces2-j/features.html#external-parameter-entities
        // JDK7+ - http://xml.org/sax/features/external-parameter-entities
        //This feature has to be used together with the previous one, otherwise it will not protect you from XXE for sure
        FEATURE = "http://xml.org/sax/features/external-parameter-entities";
        dbf.setFeature(FEATURE, false);

        // Disable external DTDs as well
        FEATURE = "http://apache.org/xml/features/nonvalidating/load-external-dtd";
        dbf.setFeature(FEATURE, false);

        // and these as well, per Timothy Morgan's 2014 paper: "XML Schema, DTD, and Entity Attacks"
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);

        // And, per Timothy Morgan: "If for some reason support for inline DOCTYPEs are a requirement, then
        // ensure the entity settings are disabled (as shown above) and beware that SSRF attacks
        // (http://cwe.mitre.org/data/definitions/918.html) and denial
        // of service attacks (such as billion laughs or decompression bombs via "jar:") are a risk."

        // Load XML file or stream using a XXE agnostic configured parser...
        DocumentBuilder documentBuilder = dbf.newDocumentBuilder();
        return documentBuilder.parse(new ByteArrayInputStream(xml.getBytes()));
    }
}
